package com.transfer.flash.core.discovery.nsd

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashRadioTransport
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

// ---------------------------------------------------------------------------
// Bridge seam: every NsdManager/WifiManager interaction lives behind this
// interface so JVM unit tests can fake radio callbacks (plan C3.4, R2).
// ---------------------------------------------------------------------------

/** TXT-record payload of one advertisement (neutral form of NsdServiceInfo attrs). */
data class AdvertiseRequest(
    val serviceName: String,
    val serviceType: String,
    val port: Int,
    val txtRecords: Map<String, String>,
)

/** Neutral callbacks mirroring [NsdManager.RegistrationListener]. */
interface AdvertiseEvents {
    fun onRegistered(actualServiceName: String, actualPort: Int)
    fun onRegistrationFailed(errorCode: Int)
    fun onUnregistered()
    fun onUnregistrationFailed(errorCode: Int)
}

/**
 * Which resolution mechanism the bridge should use for a discovered service
 * (plan C3.4 split). Recorded verbatim by test fakes to assert branch selection.
 */
enum class ResolutionStrategy {
    /** API 34+: continuous monitoring via `registerServiceInfoCallback`. */
    INFO_CALLBACK,

    /** API < 34: one-shot resolve through the hardened [NsdResolveQueue] (ERROR-006 protections preserved). */
    LEGACY_RESOLVE_QUEUE,
}

data class BrowseRequest(
    val serviceType: String,
    /**
     * True requests the API 33+ `discoverServices(String, Int, NetworkRequest, Executor, Listener)`
     * overload which tracks networks automatically (proper Found/Lost across Wi-Fi drops).
     * False requests the legacy PROTOCOL_DNS_SD call. https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager
     */
    val useNetworkRequestDiscovery: Boolean,
)

/** Neutral callbacks mirroring [NsdManager.DiscoveryListener]. */
interface BrowseEvents {
    fun onStarted()
    fun onStartFailed(errorCode: Int)
    fun onServiceFound(serviceName: String)
    fun onServiceLost(serviceName: String)
}

/** Resolution/monitoring flavor requested by the transport. */
data class MonitorRequest(val serviceName: String, val strategy: ResolutionStrategy)

/** Fully resolved service data, neutralized out of [NsdServiceInfo]. */
data class ResolvedServiceData(
    val hostAddress: String?,
    val port: Int,
    val serviceName: String,
    val attributes: Map<String, String>,
)

/** Neutral callbacks mirroring [NsdManager.ResolveListener]/[NsdManager.ServiceInfoCallback]. */
interface MonitorEvents {
    /** First invocation per peer acts as Found, subsequent ones as Updated. */
    fun onUpdated(data: ResolvedServiceData)

    /** Radio-reported loss (ServiceInfoCallback.onServiceLost or post-resolve loss). */
    fun onMonitorLost(serviceName: String?)

    fun onRegistrationFailed(errorCode: Int)

    fun onUnregistered()
}

/**
 * Thin wrapper over everything NSD-related this transport needs. Production
 * implementation: [RealNsdManagerBridge]. Test implementations record calls and
 * replay neutral callbacks.
 *
 * Deviation note (R4 additive): the ERROR-006-hardened `NsdResolveQueue` stays
 * untouched and is owned INSIDE [RealNsdManagerBridge] for the
 * [ResolutionStrategy.LEGACY_RESOLVE_QUEUE] strategy — preserving its serialized,
 * generation-checked resolution while keeping Android types out of the transport logic.
 */
interface NsdManagerBridge {
    /** Acquires (true) or releases (false) the Wi-Fi multicast lock. */
    fun setMulticastLock(active: Boolean)

    /** Initiates advertising. Returns false when the call itself threw (async failures arrive via [AdvertiseEvents]). */
    fun advertise(request: AdvertiseRequest, events: AdvertiseEvents): Boolean

    fun unadvertise(events: AdvertiseEvents)

    /** Starts browsing. Returns false when the call itself threw (treated like onStartFailed). */
    fun startBrowse(request: BrowseRequest, events: BrowseEvents): Boolean

    fun stopBrowse()

    /** Starts resolution ([LEGACY_RESOLVE_QUEUE]) or continuous monitoring ([INFO_CALLBACK]). */
    fun monitor(request: MonitorRequest, events: MonitorEvents): Boolean

    /** Cancels all outstanding monitors/resolutions. */
    fun cancelMonitors()
}

/**
 * Production bridge. Owns the real [NsdManager], the Wi-Fi multicast lock and the
 * hardened [NsdResolveQueue]. All framework calls are wrapped so unexpected
 * exceptions surface as boolean initiation-failures rather than crashes.
 */
class RealNsdManagerBridge(
    context: Context,
    private val tag: String = TAG,
) : NsdManagerBridge {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var advertiseListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Service infos stashed from onServiceFound so monitor() can hand them to NSD APIs. */
    private val foundServices = mutableMapOf<String, NsdServiceInfo>()

    /** Lazily created: the hardened serialized resolver (ERROR-006). */
    private var resolveQueue: NsdResolveQueue? = null

    /** Active ServiceInfoCallbacks keyed by service name (API 34+ path). */
    private val infoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    override fun setMulticastLock(active: Boolean) {
        runCatching {
            if (active) {
                if (multicastLock?.isHeld != true) {
                    multicastLock = wifiManager.createMulticastLock("flash-nsd-transport").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } else {
                multicastLock?.let { lock ->
                    if (lock.isHeld) lock.release()
                }
                multicastLock = null
            }
        }.onFailure { Log.w(tag, "Multicast lock toggle failed", it) }
    }

    override fun advertise(request: AdvertiseRequest, events: AdvertiseEvents): Boolean {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                events.onRegistered(info.serviceName, info.port)

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                events.onRegistrationFailed(errorCode)

            override fun onServiceUnregistered(info: NsdServiceInfo) =
                events.onUnregistered()

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                events.onUnregistrationFailed(errorCode)
        }
        advertiseListener = listener
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = request.serviceName
            serviceType = request.serviceType
            port = request.port
            request.txtRecords.forEach { (key, value) ->
                if (key != null && value != null) setAttribute(key, value)
            }
        }
        return runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Log.w(tag, "Failed to initiate NSD registration", it) }
            .isSuccess
    }

    override fun unadvertise(events: AdvertiseEvents) {
        advertiseListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
                .onFailure { Log.w(tag, "Unable to unregister NSD service", it) }
        }
        advertiseListener = null
    }

    override fun startBrowse(request: BrowseRequest, events: BrowseEvents): Boolean {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = events.onStarted()
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                events.onStartFailed(errorCode)

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                foundServices[serviceInfo.serviceName] = serviceInfo
                events.onServiceFound(serviceInfo.serviceName)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) =
                events.onServiceLost(serviceInfo.serviceName)

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(tag, "NSD discovery stop failed error=$errorCode")
            }
        }
        discoveryListener = listener
        return if (request.useNetworkRequestDiscovery) {
            runCatching {
                val networkRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                nsdManager.discoverServices(
                    request.serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    networkRequest,
                    DIRECT_EXECUTOR,
                    listener,
                )
            }.onFailure { Log.w(tag, "NetworkRequest discovery failed; falling back", it) }
                .isSuccess || runCatching {
                // Synchronous failure of the NetworkRequest overload → legacy fallback.
                nsdManager.discoverServices(request.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { Log.w(tag, "Legacy NSD discovery failed", it) }
                .isSuccess
        } else {
            runCatching {
                nsdManager.discoverServices(request.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { Log.w(tag, "Legacy NSD discovery failed", it) }
                .isSuccess
        }
    }

    override fun stopBrowse() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { Log.w(tag, "Unable to stop NSD discovery", it) }
        }
        discoveryListener = null
        foundServices.clear()
    }

    override fun monitor(request: MonitorRequest, events: MonitorEvents): Boolean {
        val info = foundServices[request.serviceName] ?: return false
        return when (request.strategy) {
            ResolutionStrategy.INFO_CALLBACK -> monitorWithInfoCallback(info, events)
            ResolutionStrategy.LEGACY_RESOLVE_QUEUE -> monitorWithResolveQueue(info, events)
        }
    }

    /**
     * API 34+ path: continuous updates via `registerServiceInfoCallback`
     * (https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager.ServiceInfoCallback).
     */
    private fun monitorWithInfoCallback(info: NsdServiceInfo, events: MonitorEvents): Boolean {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                mapResolved(serviceInfo)?.let(events::onUpdated)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                events.onMonitorLost(serviceInfo.serviceName)
            }

            // Abstract no-arg variant (API 34): implement so the anonymous object is
            // concrete; the parameterized override above handles enrichment when used.
            override fun onServiceLost() {
                events.onMonitorLost(null)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                events.onRegistrationFailed(errorCode)
            }

            override fun onServiceInfoCallbackUnregistered() {
                events.onUnregistered()
            }
        }
        return runCatching {
            nsdManager.registerServiceInfoCallback(info, DIRECT_EXECUTOR, callback)
            infoCallbacks[info.serviceName] = callback
        }.onFailure { Log.w(tag, "registerServiceInfoCallback failed for ${info.serviceName}", it) }
            .isSuccess
    }

    /**
     * Legacy path (< 34): the EXISTING hardened serialized resolver — ERROR-006
     * protections (single in-flight resolve, dedup by name, generation checks) preserved.
     */
    private fun monitorWithResolveQueue(info: NsdServiceInfo, events: MonitorEvents): Boolean {
        val queue = synchronized(queueLock) {
            resolveQueue ?: NsdResolveQueue(
                nsdManager = nsdManager,
                tag = tag,
                onResolvedCallback = { resolved ->
                    mapResolved(resolved)?.let(events::onUpdated)
                },
            ).also { resolveQueue = it }
        }
        queue.enqueue(info) { true }
        return true
    }

    override fun cancelMonitors() {
        infoCallbacks.values.forEach { callback ->
            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                .onFailure { Log.w(tag, "Unable to unregister ServiceInfoCallback", it) }
        }
        infoCallbacks.clear()
        resolveQueue?.clear()
        foundServices.clear()
    }

    private fun mapResolved(info: NsdServiceInfo): ResolvedServiceData? {
        val hostAddress = runCatching { info.host?.hostAddress }.getOrNull()
        val attributes = runCatching {
            info.attributes.mapValues { (_, value) ->
                value?.toString(StandardCharsets.UTF_8).orEmpty()
            }
        }.getOrElse {
            Log.w(tag, "Failed reading TXT attributes for ${info.serviceName}", it)
            emptyMap()
        }
        return ResolvedServiceData(
            hostAddress = hostAddress,
            port = info.port,
            serviceName = info.serviceName,
            attributes = attributes,
        )
    }

    private val queueLock = Any()

    companion object {
        const val TAG = "DISCOVERY"

        /**
         * Direct executor: NSD callbacks stay on ConnectivityThread, matching
         * NsdFlashDiscovery behavior (no extra thread hops, no executor lifecycle).
         */
        val DIRECT_EXECUTOR: java.util.concurrent.Executor =
            java.util.concurrent.Executor { block -> block.run() }
    }
}

// ---------------------------------------------------------------------------
// Pure, JVM-testable helpers (TXT codec + restart policy).
// ---------------------------------------------------------------------------

/**
 * NSD-side TXT codec. Key set mirrors the concurrent core.TxtCodec contract
 * ({device_id,name,model,proto}) but is deliberately independent so this module
 * compiles regardless of the other agent's delivery status.
 * TODO(unify): fold into core.TxtCodec once both land (noted in logs/progress.md).
 */
object NsdTxtCodec {
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_NAME = "name"
    const val KEY_MODEL = "model"
    const val KEY_PROTO = "proto"
    val KEYS = listOf(KEY_DEVICE_ID, KEY_NAME, KEY_MODEL, KEY_PROTO)

    fun encode(identity: FlashAdvertisedIdentity): LinkedHashMap<String, String> = linkedMapOf(
        KEY_DEVICE_ID to identity.deviceId.value,
        KEY_NAME to identity.friendlyName,
        KEY_MODEL to identity.deviceModel,
        KEY_PROTO to identity.protocolVersion.toString(),
    )

    data class ParsedIdentity(
        val deviceId: String?,
        val friendlyName: String?,
        val deviceModel: String?,
        val protocolVersion: Int?,
    )

    /**
     * Tolerant decode: missing/blank device_id yields null deviceId (caller drops the
     * endpoint — identity-aware filtering, plan C3.2); name/proto fall back gracefully.
     */
    fun decode(attributes: Map<String, String>, fallbackName: String, fallbackProto: Int): ParsedIdentity {
        val deviceId = attributes[KEY_DEVICE_ID]?.trim()?.takeIf { it.isNotEmpty() }
        val name = attributes[KEY_NAME]?.takeIf { it.isNotBlank() } ?: fallbackName
        val model = attributes[KEY_MODEL]?.takeIf { it.isNotBlank() }
        val proto = attributes[KEY_PROTO]?.trim()?.toIntOrNull() ?: fallbackProto
        return ParsedIdentity(deviceId, name, model, proto)
    }
}

/** Pure restart-decision math so retry scheduling is unit-testable without virtual time. */
object NsdRestartPolicy {

    /** @param delayMs null means "give up" (attempt budget exhausted). */
    data class Decision(val attempt: Int, val delayMs: Long?)

    fun computeRestart(attempt: Int, maxAttempts: Int, delayProvider: (Int) -> Long): Decision =
        if (attempt > maxAttempts) Decision(attempt, null)
        else Decision(attempt, delayProvider(attempt))

    /** Exponential backoff: 1 s, 2 s, 4 s, … capped at 30 s (mirrors C4 reconnect defaults). */
    val exponentialBackoff: (Int) -> Long = { attempt ->
        ((1L shl (attempt - 1).coerceIn(0, 5)) * 1_000L).coerceAtMost(30_000L)
    }
}

// ---------------------------------------------------------------------------
// NsdTransport
// ---------------------------------------------------------------------------

/**
 * Continuous, identity-aware NSD transport (plan C3.2–C3.4).
 *
 * - **Advertising (C3.2):** registers `_flash-transfer._tcp.` with TXT records
 *   {device_id,name,model,proto}; resolved events carrying OUR deviceId are
 *   self-filtered by identity (never by service-name string match).
 * - **Continuous browsing (C3.3):** browse runs until [stop]; start failures and
 *   runtime losses restart browsing honoring the injected [retryDelayMs] provider
 *   with a capped attempt budget; transitions emit [FlashTransportEvent.StateChanged].
 * - **Resolution split (C3.4):** API ≥ 34 → `registerServiceInfoCallback` continuous
 *   monitoring + `discoverServices(NetworkRequest, …)` overload (proper Found/Lost
 *   across Wi-Fi drops, added API 33); API < 34 → legacy discover +
 *   hardened [NsdResolveQueue]. Thresholds documented in [NsdApiThresholds].
 *
 * DEVIATION (documented per task spec): this transport owns an internal
 * `CoroutineScope(SupervisorJob() + dispatcher)` created lazily on first start and
 * cancelled in [stop]. Rationale: the radio lifecycle IS the scope lifetime — there
 * is no outer owner below the engine, and leaking callbacks after stop would break
 * the Found/Lost contract. Engine wiring later may replace this via constructor
 * injection if desired.
 */
class NsdTransport(
    context: Context?,
    private val apiLevel: NsdApiLevel,
    private val directory: EndpointDirectory,
    private val sweep: (nowMs: Long) -> List<EndpointDirectory.Diff.Lost>,
    private val serviceType: String = DEFAULT_SERVICE_TYPE,
    private val instancePrefix: String = "Flash",
    private val retryDelayMs: (Int) -> Long = NsdRestartPolicy.exponentialBackoff,
    private val maxBrowsingRestarts: Int = DEFAULT_MAX_RESTARTS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeSourceMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val logInfo: (String) -> Unit = { Log.i(TAG, it) },
    private val logWarn: (String) -> Unit = { Log.w(TAG, it) },
    bridgeOverride: NsdManagerBridge? = null,
) : FlashRadioTransport {

    init {
        require(bridgeOverride != null || context != null) {
            "NsdTransport requires either a Context or an injected NsdManagerBridge (tests)"
        }
    }

    private val bridge: NsdManagerBridge =
        bridgeOverride ?: RealNsdManagerBridge(requireNotNull(context) { "context required" })

    override val transportName: String = "nsd"

    private val _events = MutableSharedFlow<FlashTransportEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<FlashTransportEvent> = _events.asSharedFlow()

    @Volatile private var ownDeviceId: FlashDeviceId? = null
    @Volatile private var advertising = false
    @Volatile private var browsing = false
    @Volatile private var restartAttempt = 0
    @Volatile private var advertisedPort: Int = 0

    /** Reverse map serviceName → deviceId so radio loss can produce typed Lost events. */
    private val deviceIdsByServiceName = HashMap<String, FlashDeviceId>()

    private var scope: CoroutineScope? = null

    /**
     * Serial lane guarding [directory] + bookkeeping maps (directory itself is NOT thread-safe).
     * Falls back to the raw dispatcher when it does not support limiting views
     * (e.g. Dispatchers.Unconfined in JVM tests) — production Dispatchers.IO gets a real
     * parallelism-1 view.
     */
    private val lane: CoroutineDispatcher = try {
        dispatcher.limitedParallelism(1)
    } catch (_: UnsupportedOperationException) {
        dispatcher
    }

    private val advertiseEvents = object : AdvertiseEvents {
        override fun onRegistered(actualServiceName: String, actualPort: Int) {
            advertisedPort = actualPort
            advertising = true
            emitState("Advertising as $actualServiceName")
        }

        override fun onRegistrationFailed(errorCode: Int) {
            advertising = false
            releaseMulticastLockIfIdle()
            emitState("Advertising failed: $errorCode")
        }

        override fun onUnregistered() {
            advertising = false
            releaseMulticastLockIfIdle()
        }

        override fun onUnregistrationFailed(errorCode: Int) {
            logWarn("NSD unregistration failed error=$errorCode")
        }
    }

    private val browseEvents = object : BrowseEvents {
        override fun onStarted() {
            restartAttempt = 0
            emitState("Scanning network")
        }

        override fun onStartFailed(errorCode: Int) {
            scope?.launch(lane) {
                if (!browsing) return@launch
                logWarn("NSD discovery start failed error=$errorCode")
                if (!backoffOrGiveUp()) return@launch
                browseLoop()
            }
        }

        override fun onServiceFound(serviceName: String) {
            val strategy = resolutionStrategy()
            scope?.launch(lane) {
                if (!browsing) return@launch
                bridge.monitor(MonitorRequest(serviceName, strategy), monitorEvents)
            }
        }

        override fun onServiceLost(serviceName: String) {
            handleMonitorLost(serviceName)
        }
    }

    private val monitorEvents = object : MonitorEvents {
        override fun onUpdated(data: ResolvedServiceData) {
            scope?.launch(lane) { handleServiceUpdated(data) }
        }

        override fun onMonitorLost(serviceName: String?) {
            handleMonitorLost(serviceName)
        }

        override fun onRegistrationFailed(errorCode: Int) {
            logWarn("Service monitor registration failed error=$errorCode")
        }

        override fun onUnregistered() {}
    }

    // -- Advertising (C3.2) ---------------------------------------------------

    override suspend fun startAdvertising(port: Int, identity: FlashAdvertisedIdentity): FlashResult<Unit> {
        ensureScope()
        ownDeviceId = identity.deviceId
        acquireMulticastLockIfNeeded()

        val txt = NsdTxtCodec.encode(identity)
        val request = AdvertiseRequest(
            serviceName = "$instancePrefix ${identity.friendlyName.take(MAX_NAME_LENGTH)}",
            serviceType = serviceType,
            port = port,
            txtRecords = txt,
        )
        val initiated = runCatching { bridge.advertise(request, advertiseEvents) }
            .getOrElse { false }
        return if (initiated) {
            FlashResult.Success(Unit)
        } else {
            releaseMulticastLockIfIdle()
            FlashResult.Failure(
                FlashError.NetworkUnavailable("Failed to initiate NSD service registration"),
            )
        }
    }

    // -- Continuous browsing (C3.3 + C3.4) ------------------------------------

    override suspend fun startBrowsing(): FlashResult<Unit> {
        if (browsing) return FlashResult.Success(Unit)
        ensureScope()
        browsing = true
        restartAttempt = 0
        acquireMulticastLockIfNeeded()
        emitState("Starting browse")
        scope?.launch(lane) { browseLoop() }
        return FlashResult.Success(Unit)
    }

    private suspend fun browseLoop() {
        while (browsing && coroutineContext.isActive) {
            val request = BrowseRequest(
                serviceType = serviceType,
                useNetworkRequestDiscovery = useNetworkRequestDiscovery(),
            )
            val started = runCatching { bridge.startBrowse(request, browseEvents) }
                .getOrElse { false }
            if (started) {
                restartAttempt = 0
                return
            }
            logWarn("NSD browse initiation failed; retrying")
            if (!backoffOrGiveUp()) return
        }
    }

    /**
     * One backoff step: consumes an attempt, sleeps per [retryDelayMs], and returns
     * false when the attempt budget is exhausted (browsing disabled + final state emitted).
     */
    private suspend fun backoffOrGiveUp(): Boolean {
        restartAttempt += 1
        val decision = NsdRestartPolicy.computeRestart(restartAttempt, maxBrowsingRestarts, retryDelayMs)
        val backoffMs = decision.delayMs
        if (backoffMs == null) {
            browsing = false
            releaseMulticastLockIfIdle()
            emitState("Browsing gave up after $restartAttempt attempts")
            return false
        }
        emitState("Browsing restarting (attempt $restartAttempt in ${backoffMs}ms)")
        sleep(backoffMs)
        return browsing && coroutineContext.isActive
    }

    // -- Resolution handling (C3.4) + directory diff mapping (C3.5 groundwork) -

    private suspend fun handleServiceUpdated(data: ResolvedServiceData) {
        if (!browsing) return
        val parsed = NsdTxtCodec.decode(
            attributes = data.attributes,
            fallbackName = data.serviceName,
            fallbackProto = FlashProtocol.VERSION,
        )
        val deviceIdString = parsed.deviceId ?: run {
            logInfo("Dropping NSD endpoint without device_id name=${data.serviceName}")
            return
        }
        val deviceId = runCatching { FlashDeviceId(deviceIdString) }.getOrElse {
            logWarn("Invalid device_id '$deviceIdString' from ${data.serviceName}")
            return
        }
        if (deviceId == ownDeviceId) return // self-advertisement filtered by IDENTITY (C3.2)

        val hostAddress = data.hostAddress ?: return // unresolved yet; wait for next update
        val endpoint = FlashDiscoveredEndpoint(
            device = FlashDevice(
                id = deviceId,
                friendlyName = parsed.friendlyName ?: data.serviceName,
                transportType = FlashTransportType.LAN,
                presence = FlashPeerPresence.Online,
                protocolVersion = parsed.protocolVersion ?: FlashProtocol.VERSION,
            ),
            hostAddress = hostAddress,
            port = data.port,
            serviceName = data.serviceName,
        )
        val diff = directory.applySeen(endpoint, timeSourceMs())
        synchronized(deviceIdsByServiceName) {
            deviceIdsByServiceName[data.serviceName] = deviceId
        }
        emitDiff(diff)
    }

    private fun handleMonitorLost(serviceName: String?) {
        if (serviceName == null) return
        scope?.launch(lane) {
            if (!browsing) return@launch
            val deviceId = synchronized(deviceIdsByServiceName) {
                deviceIdsByServiceName.remove(serviceName)
            } ?: return@launch
            // Bookkeep the loss in the directory; the typed Lost event below carries the
            // serviceName we already know, so the generic Diff.Lost emission is skipped
            // (avoids duplicate Lost events for one radio goodbye).
            directory.applyLost(deviceId)
            emitEvent(FlashTransportEvent.Lost(deviceId, serviceName))
        }
    }

    /** Pulls engine-wired sweep results into Lost events (sweeper wiring is the engine's job, C3.5). */
    fun pollSweep() {
        scope?.launch(lane) {
            sweep(timeSourceMs()).forEach { agedOut ->
                val serviceName = findServiceNameFor(agedOut.deviceId)
                emitEvent(FlashTransportEvent.Lost(agedOut.deviceId, serviceName))
            }
        }
    }

    private fun findServiceNameFor(deviceId: FlashDeviceId): String? =
        synchronized(deviceIdsByServiceName) {
            deviceIdsByServiceName.entries.firstOrNull { it.value == deviceId }?.key
        }

    private suspend fun emitDiff(diff: EndpointDirectory.Diff) {
        when (diff) {
            is EndpointDirectory.Diff.Found -> emitEvent(FlashTransportEvent.Found(diff.entry.endpoint))
            is EndpointDirectory.Diff.Updated -> emitEvent(FlashTransportEvent.Updated(diff.entry.endpoint))
            is EndpointDirectory.Diff.Lost -> emitEvent(
                FlashTransportEvent.Lost(diff.deviceId, findServiceNameFor(diff.deviceId)),
            )
            EndpointDirectory.Diff.Unchanged -> Unit
        }
    }

    // -- Lifecycle -------------------------------------------------------------

    override suspend fun stop(): FlashResult<Unit> {
        browsing = false
        advertising = false
        emitState("Stopped")
        runCatching { bridge.stopBrowse() }
        runCatching { bridge.unadvertise(advertiseEvents) }
        runCatching { bridge.cancelMonitors() }
        runCatching { bridge.setMulticastLock(false) }
        scope?.cancel()
        scope = null
        synchronized(deviceIdsByServiceName) { deviceIdsByServiceName.clear() }
        restartAttempt = 0
        return FlashResult.Success(Unit)
    }

    // -- Helpers ---------------------------------------------------------------

    private fun resolutionStrategy(): ResolutionStrategy =
        if (apiLevel.sdkInt >= NsdApiThresholds.SDK_SERVICE_INFO_CALLBACK) {
            ResolutionStrategy.INFO_CALLBACK
        } else {
            ResolutionStrategy.LEGACY_RESOLVE_QUEUE
        }

    private fun useNetworkRequestDiscovery(): Boolean =
        apiLevel.sdkInt >= NsdApiThresholds.SDK_NETWORK_REQUEST_DISCOVERY

    private fun acquireMulticastLockIfNeeded() {
        // Conservative stand-in for T-extensions 7 (see NsdApiThresholds KDoc).
        if (apiLevel.sdkInt < NsdApiThresholds.SDK_MULTICAST_LOCK_NOT_NEEDED) {
            bridge.setMulticastLock(true)
        }
    }

    private fun releaseMulticastLockIfIdle() {
        if (advertising || browsing) return
        bridge.setMulticastLock(false)
    }

    private fun emitState(message: String) {
        emitEvent(FlashTransportEvent.StateChanged(advertising || browsing, message))
    }

    private fun emitEvent(event: FlashTransportEvent) {
        _events.tryEmit(event)
    }

    private fun ensureScope() {
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        }
    }

    companion object {
        const val TAG = "DISCOVERY"
        const val DEFAULT_SERVICE_TYPE = "_flash-transfer._tcp."
        const val DEFAULT_MAX_RESTARTS = 5
        const val MAX_NAME_LENGTH = 24
        private const val EVENT_BUFFER = 64
    }
}
