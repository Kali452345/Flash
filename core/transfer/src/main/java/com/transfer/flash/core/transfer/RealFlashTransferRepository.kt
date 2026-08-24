package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.persistence.db.dao.TransferChunkDao
import com.transfer.flash.core.persistence.db.dao.TransferDao
import com.transfer.flash.core.persistence.db.entity.TransferChunkEntity
import com.transfer.flash.core.persistence.db.entity.TransferEntity
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.transfer.chunked.ChunkSource
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.MultiStreamDispatcher
import com.transfer.flash.core.transfer.multistream.MultiStreamResult
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Functional stream source provider returning an [InputStream] given a source URI / descriptor.
 */
fun interface FileSourceOpener {
    fun open(fileUri: String): InputStream
}

/**
 * Concrete implementation of [FlashTransferRepository] (C5.2).
 * Orchestrates multi-stream chunked file transfers over [MultiStreamDispatcher],
 * persists transfer progress and resume states to Room DAOs, and exposes reactive UI state.
 */
class RealFlashTransferRepository(
    private val chunker: Chunker = Chunker(),
    private val streamChannelFactory: StreamChannelFactory,
    private val fileSourceOpener: FileSourceOpener,
    private val transferDao: TransferDao? = null,
    private val transferChunkDao: TransferChunkDao? = null,
    private val settingsDataStore: FlashSettingsDataStore? = null,
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultStreams: Int = 2,
) : FlashTransferRepository {

    private val _activeTransfers = MutableStateFlow<List<FlashTransfer>>(emptyList())
    override val activeTransfers: StateFlow<List<FlashTransfer>> = _activeTransfers.asStateFlow()

    /**
     * Receive-side intake control. Emitted when an INBOUND transfer is paused/resumed/cancelled.
     * The transport host stops draining the session's inbound channel while any inbound
     * transfer is paused — TCP backpressure then throttles the sender (no wire protocol change).
     */
    private val _incomingControl = MutableSharedFlow<IncomingControl>(extraBufferCapacity = 16)
    val incomingControl: MutableSharedFlow<IncomingControl> = _incomingControl

    data class IncomingControl(val transferId: String, val action: String)

    /**
     * Wire-level control frames to deliver to the counterpart peer (`FLASH_XFER`, ADR-018):
     * pause/resume/cancel so BOTH sides reflect state and stop/start deterministically instead
     * of relying on TCP backpressure alone (which cannot reach dedicated data channels).
     */
    private val _outgoingControl = MutableSharedFlow<OutgoingControl>(extraBufferCapacity = 16)
    val outgoingControl: MutableSharedFlow<OutgoingControl> = _outgoingControl

    data class OutgoingControl(val transferId: String, val peerDeviceId: String?, val action: String)

    companion object {
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_CANCEL = "cancel"
    }

    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val runningDispatchers = ConcurrentHashMap<String, MultiStreamDispatcher>()

    override suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashResult<FlashTransferId> {
        val transferIdString = UUID.randomUUID().toString()
        val transferId = FlashTransferId(transferIdString)
        val fileId = UUID.randomUUID().toString()

    val initialTransfer = FlashTransfer(
        id = transferId,
        peerName = targetDevice.friendlyName,
        fileName = displayName,
        direction = FlashTransferDirection.Sending,
        bytesDone = 0L,
        bytesTotal = fileSize,
        state = FlashTransferState.Queued,
        sourceUri = fileUri,
        wireFileId = fileId,
        peerDeviceId = targetDevice.id.value,
    )

    _activeTransfers.update { it + initialTransfer }
    transferDao?.insert(
        TransferEntity(
            transferId = transferIdString,
            totalBytes = fileSize,
            bytesDone = 0L,
            status = FlashTransferState.Queued.name,
        ),
    )

        val job = repositoryScope.launch(workerDispatcher) {
            executeSend(
                transferId = transferIdString,
                fileId = fileId,
                fileUri = fileUri,
                displayName = displayName,
                fileSize = fileSize,
                peerName = targetDevice.friendlyName,
                peerDeviceId = targetDevice.id.value,
            )
        }
        runningJobs[transferIdString] = job

        return FlashResult.Success(transferId)
    }

    private suspend fun executeSend(
        transferId: String,
        fileId: String,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        peerName: String,
        peerDeviceId: String? = null,
    ) {
        val meta = FileMeta(
            transferId = transferId,
            fileId = fileId,
            fileName = displayName,
            totalBytes = fileSize,
        )

        val doneIndexes = transferChunkDao?.doneChunks(transferId) ?: emptyList()
        val source = ChunkSource { fileSourceOpener.open(fileUri) }

        val dispatcher = MultiStreamDispatcher(
            chunker = chunker,
            meta = meta,
            source = source,
            factory = streamChannelFactory,
            streamCount = defaultStreams,
            doneIndexes = doneIndexes,
            workerDispatcher = workerDispatcher,
            peerDeviceId = peerDeviceId,
        )
        runningDispatchers[transferId] = dispatcher

        updateTransferState(transferId) {
            it.copy(state = FlashTransferState.Transferring)
        }
        transferDao?.setStatus(transferId, FlashTransferState.Transferring.name)

        var persistedDone = doneIndexes.toSet()
        val progressJob = repositoryScope.launch(workerDispatcher) {
            dispatcher.progress.collect { progress ->
                updateTransferState(transferId) {
                    it.copy(
                        bytesDone = progress.bytesDone,
                        speedBytesPerSec = progress.instantBytesPerSec.toLong(),
                        etaSeconds = if (progress.etaMs >= 0) progress.etaMs / 1000 else 0L,
                    )
                }
                transferDao?.setBytesDone(transferId, progress.bytesDone)

                // Persist newly confirmed chunks so a later resume skips them (C5.6).
                val confirmedNow = dispatcher.confirmedIndexesSnapshot()
                val fresh = confirmedNow.filter { it !in persistedDone }
                if (fresh.isNotEmpty()) {
                    transferChunkDao?.insertAll(fresh.map { TransferChunkEntity(transferId, it, done = true) })
                    persistedDone = confirmedNow.toSet()
                }
            }
        }

        try {
            when (val result = dispatcher.send()) {
                is MultiStreamResult.Completed -> {
                    progressJob.cancel()
                    updateTransferState(transferId) {
                        it.copy(
                            bytesDone = fileSize,
                            state = FlashTransferState.Completed,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                        )
                    }
                    transferDao?.setStatus(transferId, FlashTransferState.Completed.name)
                    transferDao?.setBytesDone(transferId, fileSize)
                }

                is MultiStreamResult.Failed -> {
                    progressJob.cancel()
                    // Persist unconfirmed chunks if needed
                    val unconfirmed = result.unconfirmedIndexes
                    updateTransferState(transferId) {
                        it.copy(
                            state = FlashTransferState.Failed,
                            errorMessage = result.reason,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                        )
                    }
                    transferDao?.setStatus(transferId, FlashTransferState.Failed.name)
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Cooperative pause/cancel: the caller (pauseTransfer/cancelTransfer) already set
            // the authoritative terminal state. Marking Failed here would overwrite it
            // (media-downloader DownloadManager.handleCancellation pattern). Re-throw so
            // structured concurrency sees normal cancellation.
            progressJob.cancel()
            throw ce
        } catch (e: Exception) {
            progressJob.cancel()
            updateTransferState(transferId) {
                it.copy(
                    state = FlashTransferState.Failed,
                    errorMessage = e.message ?: "Transfer aborted unexpectedly",
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L,
                )
            }
            transferDao?.setStatus(transferId, FlashTransferState.Failed.name)
        } finally {
            runningJobs.remove(transferId)
            runningDispatchers.remove(transferId)
        }
    }

    override suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit> {
        val transfer = _activeTransfers.value.find { it.id == transferId }
            ?: return FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Transfer not found: ${transferId.value}"))
        runCatching {
            android.util.Log.i(
                "TRANSFER",
                "pauseTransfer id=${transferId.value} direction=${transfer.direction} state=${transfer.state} jobPresent=${runningJobs.containsKey(transferId.value)}",
            )
        }

        if (transfer.direction == FlashTransferDirection.Receiving) {
            // Inbound: gate local intake AND tell the sender to stop transmitting
            // (application-level; TCP backpressure cannot reach dedicated data channels).
            updateTransferState(transferId.value) {
                it.copy(state = FlashTransferState.Paused, speedBytesPerSec = 0L)
            }
            _incomingControl.tryEmit(IncomingControl(transferId.value, ACTION_PAUSE))
            _outgoingControl.tryEmit(OutgoingControl(transferId.value, transfer.peerDeviceId, ACTION_PAUSE))
            return FlashResult.Success(Unit)
        }

        // Outbound: COOPERATIVE pause — flip the dispatcher flag (instant, reversible).
        // Cancelling the job here was unreliable (blocking socket writes swallow cancellation)
        // and made resume relaunch a second dispatcher while the first kept running.
        val dispatcher = runningDispatchers[transferId.value]
        if (dispatcher != null) {
            dispatcher.setPaused(true)
            updateTransferState(transferId.value) {
                it.copy(state = FlashTransferState.Paused, speedBytesPerSec = 0L, etaSeconds = -1L)
            }
            _outgoingControl.tryEmit(OutgoingControl(transferId.value, transfer.peerDeviceId, ACTION_PAUSE))
            return FlashResult.Success(Unit)
        }

        // No live dispatcher (e.g. queued/failed): state-only pause.
        updateTransferState(transferId.value) {
            it.copy(state = FlashTransferState.Paused, speedBytesPerSec = 0L)
        }
        transferDao?.setStatus(transferId.value, FlashTransferState.Paused.name)
        return FlashResult.Success(Unit)
    }

    override suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit> {
        val transfer = _activeTransfers.value.find { it.id == transferId }
            ?: return FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Transfer not found: ${transferId.value}"))

        if (transfer.state != FlashTransferState.Paused && transfer.state != FlashTransferState.Failed) {
            return FlashResult.Success(Unit)
        }

        if (transfer.direction == FlashTransferDirection.Receiving) {
            // Resume draining the inbound channel; buffered chunks flow, ACKs resume,
            // and the wire control frame un-pauses the sender's dispatcher.
            updateTransferState(transferId.value) { it.copy(state = FlashTransferState.Transferring) }
            _incomingControl.tryEmit(IncomingControl(transferId.value, ACTION_RESUME))
            _outgoingControl.tryEmit(OutgoingControl(transferId.value, transfer.peerDeviceId, ACTION_RESUME))
            return FlashResult.Success(Unit)
        }

        // Outbound: live dispatcher paused cooperatively → just unpause (no relaunch).
        val dispatcher = runningDispatchers[transferId.value]
        if (dispatcher != null && runningJobs.containsKey(transferId.value)) {
            dispatcher.setPaused(false)
            updateTransferState(transferId.value) { it.copy(state = FlashTransferState.Transferring) }
            _outgoingControl.tryEmit(OutgoingControl(transferId.value, transfer.peerDeviceId, ACTION_RESUME))
            return FlashResult.Success(Unit)
        }

        updateTransferState(transferId.value) {
            it.copy(state = FlashTransferState.Queued)
        }
        transferDao?.setStatus(transferId.value, FlashTransferState.Queued.name)

        // Re-launch transfer
        val job = repositoryScope.launch(workerDispatcher) {
            executeSend(
                transferId = transferId.value,
                // Stable wire identity: the receiver's session is keyed on (transferId, fileId);
                // a fresh fileId here would be rejected as SESSION_CONFLICT.
                fileId = transfer.wireFileId ?: UUID.randomUUID().toString(),
                // Resume MUST re-read the original source, not the display name.
                fileUri = transfer.sourceUri ?: transfer.fileName,
                displayName = transfer.fileName,
                fileSize = transfer.bytesTotal,
                peerName = transfer.peerName,
                peerDeviceId = transfer.peerDeviceId,
            )
        }
        runningJobs[transferId.value] = job
        return FlashResult.Success(Unit)
    }

    override suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit> {
        val transfer = _activeTransfers.value.find { it.id == transferId }
        val job = runningJobs.remove(transferId.value)
        val dispatcher = runningDispatchers[transferId.value]
        dispatcher?.setPaused(false) // unpause so cancellation lands at the next suspension point
        job?.cancel()

        updateTransferState(transferId.value) {
            it.copy(state = FlashTransferState.Cancelled, speedBytesPerSec = 0L, etaSeconds = 0L)
        }
        transferDao?.setStatus(transferId.value, FlashTransferState.Cancelled.name)

        // Tell the counterpart so both sides tear down deterministically (ADR-018).
        if (transfer != null) {
            _outgoingControl.tryEmit(OutgoingControl(transferId.value, transfer.peerDeviceId, ACTION_CANCEL))
            if (transfer.direction == FlashTransferDirection.Receiving) {
                _incomingControl.tryEmit(IncomingControl(transferId.value, ACTION_CANCEL))
            }
        }
        return FlashResult.Success(Unit)
    }

    /**
     * Applies a `FLASH_XFER` control frame received from the counterpart peer (ADR-018).
     * Keeps BOTH sides' state and transmission behavior in lockstep.
     */
    fun onRemoteTransferControl(transferId: String, action: String) {
        runCatching { android.util.Log.i("TRANSFER", "remote control action=$action transferId=$transferId") }
        val transfer = _activeTransfers.value.find { it.id.value == transferId } ?: return
        when (action) {
            ACTION_PAUSE -> when (transfer.direction) {
                FlashTransferDirection.Sending -> {
                    runningDispatchers[transferId]?.setPaused(true)
                    updateTransferState(transferId) {
                        it.copy(state = FlashTransferState.Paused, speedBytesPerSec = 0L, errorMessage = "paused by receiver")
                    }
                }
                FlashTransferDirection.Receiving -> {
                    updateTransferState(transferId) { it.copy(state = FlashTransferState.Paused, speedBytesPerSec = 0L) }
                }
            }
            ACTION_RESUME -> when (transfer.direction) {
                FlashTransferDirection.Sending -> {
                    runningDispatchers[transferId]?.setPaused(false)
                    updateTransferState(transferId) { it.copy(state = FlashTransferState.Transferring) }
                }
                FlashTransferDirection.Receiving -> {
                    updateTransferState(transferId) { it.copy(state = FlashTransferState.Transferring) }
                }
            }
            ACTION_CANCEL -> when (transfer.direction) {
                FlashTransferDirection.Sending -> {
                    runningJobs.remove(transferId)?.cancel()
                    updateTransferState(transferId) {
                        it.copy(state = FlashTransferState.Cancelled, errorMessage = "cancelled by receiver")
                    }
                }
                FlashTransferDirection.Receiving -> {
                    // Host tears down sink + pipeline session on this event.
                    _incomingControl.tryEmit(IncomingControl(transferId, ACTION_CANCEL))
                    updateTransferState(transferId) {
                        it.copy(state = FlashTransferState.Cancelled, errorMessage = "cancelled by sender")
                    }
                }
            }
        }
    }

    override fun onInboundFrame(bytes: ByteArray): Boolean {
        var handled = false
        runningDispatchers.values.forEach { dispatcher ->
            if (dispatcher.onInboundFrame(0, bytes)) {
                handled = true
            }
        }
        return handled
    }

    // ---- receive-side tracking (inbound transfers surface in activeTransfers) ------------------

    override fun onIncomingStarted(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String?,
    ) {
        _activeTransfers.update { list ->
            if (list.any { it.id.value == transferId }) {
                list
            } else {
                list + FlashTransfer(
                    id = FlashTransferId(transferId),
                    peerName = peerName,
                    fileName = fileName,
                    direction = FlashTransferDirection.Receiving,
                    bytesDone = 0L,
                    bytesTotal = totalBytes,
                    state = FlashTransferState.Transferring,
                    wireFileId = fileId,
                    peerDeviceId = peerDeviceId,
                )
            }
        }
    }

    override fun onIncomingProgress(transferId: String, bytesDone: Long) {
        updateTransferState(transferId) { transfer ->
            if (transfer.direction == FlashTransferDirection.Receiving &&
                transfer.state == FlashTransferState.Transferring
            ) {
                transfer.copy(bytesDone = maxOf(transfer.bytesDone, bytesDone))
            } else {
                transfer
            }
        }
    }

    override fun onIncomingCompleted(transferId: String, verified: Boolean) {
        updateTransferState(transferId) { transfer ->
            transfer.copy(
                bytesDone = transfer.bytesTotal,
                state = FlashTransferState.Completed,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                errorMessage = if (verified) null else "completed without whole-file verification",
            )
        }
    }

    override fun onIncomingFailed(transferId: String, reason: String) {
        updateTransferState(transferId) { transfer ->
            transfer.copy(
                state = FlashTransferState.Failed,
                errorMessage = reason,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
            )
        }
    }

    private fun updateTransferState(transferId: String, transform: (FlashTransfer) -> FlashTransfer) {
        _activeTransfers.update { list ->
            list.map { if (it.id.value == transferId) transform(it) else it }
        }
    }
}
