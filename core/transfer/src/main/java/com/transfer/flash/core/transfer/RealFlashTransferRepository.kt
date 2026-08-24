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
        )
        runningDispatchers[transferId] = dispatcher

        updateTransferState(transferId) {
            it.copy(state = FlashTransferState.Transferring)
        }
        transferDao?.setStatus(transferId, FlashTransferState.Transferring.name)

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
        val job = runningJobs.remove(transferId.value)
        job?.cancel()
        runningDispatchers.remove(transferId.value)

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

        updateTransferState(transferId.value) {
            it.copy(state = FlashTransferState.Queued)
        }
        transferDao?.setStatus(transferId.value, FlashTransferState.Queued.name)

        // Re-launch transfer
        val job = repositoryScope.launch(workerDispatcher) {
            executeSend(
                transferId = transferId.value,
                fileId = UUID.randomUUID().toString(),
                fileUri = transfer.fileName, // Fallback/descriptor
                displayName = transfer.fileName,
                fileSize = transfer.bytesTotal,
                peerName = transfer.peerName,
            )
        }
        runningJobs[transferId.value] = job
        return FlashResult.Success(Unit)
    }

    override suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit> {
        val job = runningJobs.remove(transferId.value)
        job?.cancel()
        runningDispatchers.remove(transferId.value)

        updateTransferState(transferId.value) {
            it.copy(state = FlashTransferState.Cancelled, speedBytesPerSec = 0L, etaSeconds = 0L)
        }
        transferDao?.setStatus(transferId.value, FlashTransferState.Cancelled.name)
        return FlashResult.Success(Unit)
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

    private suspend fun updateTransferState(transferId: String, transform: (FlashTransfer) -> FlashTransfer) {
        _activeTransfers.update { list ->
            list.map { if (it.id.value == transferId) transform(it) else it }
        }
    }
}
