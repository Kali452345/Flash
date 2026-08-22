# Flash Core Upgrade Plan — PART 1: Core Components

**Status:** PROPOSED (planning only — nothing implemented)
**Created:** 2026-08-22 · Last restructured: 2026-08-22 (split into two parts)
**Companion doc:** [ui-page-plan.md](ui-page-plan.md) — **PART 2: Pages & Navigation**
**Related:** ADR-005 (Pulse identity) · ADR-008 (multi-module libraries) · ADR-009 (FlashText)

> This is **PART 1**: upgrading each `:core:*` component one by one — abstraction first, then implementation, then the API exposed to the frontend.
> All page/UI planning (bottom navigation, settings, per-screen specs) lives in **PART 2**.

---

## Removed Demo Pages (owner decision, 2026-08-22)

Four provisional pages were removed from the app shell:
1. **Icon QA sheet** (`FlashIconSheet`) — icon preview grid
2. **Motion QA sheet** (`FlashMotionSheet`) — animation probe page
3. **Experimental WS transfer page** (`WsTransferScreen`, `:ui:transfer` module deleted)
4. **LAN discovery demo home** (`FlashHomeScreen` in MainActivity)

Engine classes (`LanController`, `WsTransferManager`, `WsDiscovery`, `WsPairingStore`, `AppIdentity`) are **kept** in `:app` temporarily as relocation sources for C4/C5 below. Their UI entry points no longer exist.

---

## Component Order (dependency-driven)

| # | Component | Module | New? |
|---|---|---|---|
| C0 | Foundations | `:core:common` (+ DI in `:app`) | extend |
| C1 | Persistence | `:core:persistence` | **new module** |
| C2 | Security | `:core:security` | upgrade |
| C3 | Discovery | `:core:discovery` | upgrade |
| C4 | Network | `:core:network` | upgrade |
| C5 | Transfer | `:core:transfer` | upgrade |
| C6 | Messaging | `:core:messaging` | upgrade |
| C7 | Engine facade | `:core:engine` | **new module** |

Each component section follows the same loop: **Current state → Target abstraction → Implementation steps → Frontend exposure.**

---

## C0 — Foundations (`:core:common` + DI)

### Current state
`FlashDevice(Id)`, `FlashPeerPresence`, `FlashTransportType`, `FlashResult/Error`, `FlashTextFraming`. No DI graph, ad-hoc `Log.i`, single protocol version implicit.

### Target abstraction
```kotlin
object FlashProtocol { const val VERSION = 2 }
class FlashLogger(tag: String) { fun i/w/e(msg); fun recent(): List<String> }  // ring buffer for debug sheet
data class FlashEnvelope(id: String, type: String, payloadJson: String, senderId: String, sentAt: Long)
```
DI: Hilt or Koin providing Context/dispatchers/engine singletons (**Decision D1**).

### Steps
- **C0.1** Add `PROTOCOL_VERSION` to `:core:common`; assert in WS + TCP handshakes.
- **C0.2** Add `FlashEnvelope` type shared by messaging + transfer frames.
- **C0.3** Add `FlashLogger` with ring buffer (feeds a future debug screen).
- **C0.4** Set up DI graph skeleton in `:app` providing engine singletons (D1).
- **C0.5** GitHub Actions CI: `testDebugUnitTest assembleDebug` on push.

### Frontend exposure
None directly; everything downstream consumes these.

---

## C1 — Persistence (`:core:persistence`, NEW)

### Current state
No persistence anywhere. UI consumes sample repositories.

### Target abstraction
```kotlin
@Dao interface MessageDao { flows for conversation; insert-or-ignore; updateStatus; cursor ops }
@Dao interface ConversationDao / OutboxDao / TransferDao / TransferChunkDao / RecentSearchDao / TrustedPeerDao
@Database FlashDatabase(version=1)
class FlashSettingsDataStore { themeMode; dynamicAccent; hapticsEnabled; autoAcceptTrusted; saveLocationUri; retentionDays; displayName }
```

### Steps
- **C1.1** Create module; deps: Room, DataStore, `:core:common`.
- **C1.2** Entities: `MessageEntity(localId PK, conversationId, senderId, text, sentAt, status, editedAt?, deletedAt?)`, `ConversationEntity`, `ReceiptEntity`, `OutboxEntity(localId PK, attempts, nextAttemptAt)`, `TransferEntity(transferId PK, bytesDone, status…)`, `TransferChunkEntity(transferId+index PK, done)`, `RecentSearchEntity(query PK, at)` (feeds UI-024 recents), `TrustedPeerEntity(deviceId PK, name, fingerprint, trustedAt)`.
- **C1.3** DAOs returning `Flow`; all inserts `OnConflictStrategy.IGNORE` (idempotent dedup).
- **C1.4** `FlashSettingsDataStore` keys incl. dynamic accent toggle (UI-036) and haptics toggle (UI-039).
- **C1.5** At-rest encryption per **D2** (SQLCipher vs Keystore-wrapped fields).
- **C1.6** Retention pruner spec (periodic delete older than N days).
- **C1.7** Register module + version catalog + publish config.

### Frontend exposure
Raw DAOs stay internal; exposure happens through repositories in C6/C7 only.

---

## C2 — Security (`:core:security`)

### Current state
Identity + Trust stores (Preferences). No crypto; trust flag not bound to any key.

### Target abstraction
```kotlin
interface FlashCrypto { generateIdentityKeyPair(); sign(data); verify(sig,data,fingerprint); ecdhSessionKey(peerPub) }
interface FlashTlsContext { clientConfig(pinnedFingerprint?); serverConfig() }        // self-signed cert per install
interface FlashPairingProtocol { beginRequest(peer); confirmCode(code6); onPaired(callback) }
// TrustStore extended: fingerprint column + verify/revoke by fingerprint
```

### Steps
- **C2.1** Per-install self-signed keypair/cert generation; private key excluded from Android backup.
- **C2.2** SHA-256 fingerprint derivation + stable hex-group formatting (feeds UI-031/032 displays).
- **C2.3** Extend `FlashTrustStore`: store fingerprint with trust record; `isPinned(deviceId, fingerprint)` check.
- **C2.4** TOFU policy object: first-connect prompt data, mismatch = hard fail + key-changed event (feeds UI-031 warning state).
- **C2.5** Pairing handshake frames (`PAIR_REQUEST/ACCEPT/CONFIRM/PAIRED`) with 6-digit numeric comparison code derived from both fingerprints — wires the existing `FlashPairingDialog` (UI-032).
- **C2.6** Optional frame-level E2E: ECDH P-256 → AES-GCM session key at pairing (**Decision D4**).

### Frontend exposure
Badge/sheet states for UI-031 (`badgeState(isEncrypted,isVerified)`), pairing events consumed by Nearby tab + `FlashPairingDialog`.

---

## C3 — Discovery (`:core:discovery`)

### Current state
NSD advertise/discover for two service types; resolve queue hardened (ERROR-006).

### Target abstraction
```kotlin
interface FlashRadioTransport {
    val events: Flow<FlashTransportEvent>   // Found/Lost/StateChanged
    suspend fun startAdvertising(port: Int, identity: FlashIdentity)
    suspend fun stop()
}
// Impls: NsdTransport (exists), WifiAwareTransport, WifiDirectTransport, BlePresenceTransport
class CompositeDiscovery(vararg transports) : FlashDiscovery   // merges events, prefers LAN
```

### Steps
- **C3.1** Extract `FlashRadioTransport` seam; refactor NSD impl behind it (no behavior change).
- **C3.2** Wi-Fi Direct transport (prior art exists from early probes) — discovery + group lifecycle.
- **C3.3** Wi-Fi Aware transport (API 26+, publish/subscribe `_flash._pair`), TCP-over-Aware link.
- **C3.4** BLE presence transport (advertise/scan only — signals "peer nearby", wakes higher-bandwidth paths).
- **C3.5** `CompositeDiscovery` merge + dedup by DeviceId; chosen-path reporting into `FlashTransportType`.
- **C3.6** Bind discovered endpoint ↔ peer fingerprint so C2 pinning applies pre-connection.

### Frontend exposure
`discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>>` already consumed by future Nearby tab; add `transport` richness per endpoint.

---

## C4 — Network (`:core:network`)

### Current state
TCP server/connect + heartbeat session; WS client/server + codec. Plain text; single stream; manual reconnect.

### Target abstraction
```kotlin
interface FlashSecureChannel : FlashSession { /* adds TLS + framed send over any socket */ }
// FlashNetwork gains: autoReconnect policy, connect(endpoint w/ fingerprint), per-peer outbox hooks
```

### Steps
- **C4.1** TLS wrap (self-signed, TOFU pinning from C2) for TCP sessions and WS server/client.
- **C4.2** Auto-reconnect: exponential backoff + jitter (cap 30 s), instant reconnect via `NetworkCallback`.
- **C4.3** Foreground-lifecycle binding helper (connect ON_START / idle disconnect ON_STOP unless background mode).
- **C4.4** Multi-peer session manager hardening: concurrent session limits, per-peer send queues.
- **C4.5** Transport-agnostic endpoints: accept sockets from Aware/Direct interfaces (C3), not just LAN NICs.

### Frontend exposure
`networkState`/`activeSessions` flows feed the connection banner (UI-030) and Nearby tab states.

---

## C5 — Transfer (`:core:transfer`)

### Current state
Interface + framing models; real manager sits in `:app` (relocation source after demo-page removal).

### Target abstraction
```kotlin
// Existing interface stays; implementation becomes chunked/resumable/multi-stream.
data class ChunkFrame(transferId, index, hashHex, compressed: Boolean, payload)
data class AckBatch(transferId, indexes: List<Int>)
// New: receive-side destination policy (SAF uri or auto-save dir), manifest frame for multi-file
```

### Steps
- **C5.1** Relocate `WsTransferManager` (+ WsDiscovery/WsPairingStore as needed) from `:app` into `:core:transfer`.
- **C5.2** Implement `FlashTransferRepository` against it; delete demo-only paths.
- **C5.3** Framing v2: FILE_START{fileHash,totalChunks} → CHUNK{index,hash,payload} → batched ACK → COMPLETE{verified}.
- **C5.4** Chunker: 64KB default, adaptive 16–256KB from throughput; ContentResolver streaming reads.
- **C5.5** Per-chunk SHA-256 (D3) verified before disk write; mismatch ⇒ request that chunk only.
- **C5.6** Resume bit-vector persisted (TransferChunkEntity); reconcile-on-reconnect.
- **C5.7** Parallel pipelines (2–4 configurable); ACK batching every 32 chunks.
- **C5.8** Optional zstd per-chunk compression, skip-if-incompressible (off by default).
- **C5.9** Receive pipeline: SAF destination picker + auto-save dir + MediaStore copy for media.
- **C5.10** Multi-file manifest frame + multi-recipient parallel dispatch (per-peer progress rows).
- **C5.11** TEXT share frame type (clipboard-style sends).
- **C5.12** Progress foreground service + completion/failure notifications.

### Frontend exposure
`activeTransfers` flow feeds the Transfers tab rows/cards verbatim (existing UI-016 card language).

---

## C6 — Messaging (`:core:messaging`)

### Current state
Rich UI-state models + sample repository. No persistence, no send path, no receipts/sync.

### Target abstraction
```kotlin
class RealFlashChatRepository(
    db: FlashDatabase, network: FlashNetwork, identity: FlashIdentityStore,
    trust: FlashTrustStore, scope: CoroutineScope,
) : FlashChatRepository   // SAME interface the finished screens consume today
// Adds: searchMessages(query), editMessage(id,newText), deleteMessage(id),
//       markReadUpTo(conversationId,messageId), syncState: StateFlow<SyncHealth>
```

### Steps
- **C6.1** Outbox pattern: send writes PENDING row → Room emits bubble instantly → dispatcher drains over sessions.
- **C6.2** Idempotency: local UUID travels end-to-end; receiver IGNORE-duplicates.
- **C6.3** Receipts: DELIVERED ack post-insert; READ as batched cursor (`upToMessageId`) on open + debounce.
- **C6.4** Edit/delete revision records replayed in order; UI shows "edited"/"deleted".
- **C6.5** Reconnect delta sync: SYNC_REQUEST(cursor) → atomic batch apply → flush outbox (pull-before-push).
- **C6.6** Typing/presence ephemeral frames (TTL, never persisted, start/stop debounced).
- **C6.7** Multi-peer fan-out to all active sessions; per-peer delivery receipts.
- **C6.8** Unread counts from furthest-forward read cursors.
- **C6.9** Search query support feeding UI-023/024 (DB-backed LIKE/FTS decision inline).
- **C6.10** Swap `SampleFlashChatRepository` out of `MainActivity` behind debug flag (samples remain for previews).

### Frontend exposure
Unchanged public interface — finished screens keep compiling; new capabilities surface as extra model fields (edited/deleted flags, sync health banner hook).

---

## C7 — Engine Facade (`:core:engine`, NEW)

### Target abstraction
```kotlin
interface FlashEngine {
    val chats: FlashChatRepository
    val transfers: FlashTransferRepository
    val discovery: FlashDiscovery
    val security: FlashSecurityFacade      // badge states, pairing events, trusted peers
    val settings: FlashSettingsRepository  // wraps DataStore
}
```

### Steps
- **C7.1** Create module aggregating implementations; single construction point used by DI (C0.4).
- **C7.2** Per-screen ViewModels: ChatList, Conversation, Transfers, Nearby, Settings, Pairing — constructor-injected fakes for tests.
- **C7.3** Entity→UI-state mappers live here (screens never see DB types).
- **C7.4** Publish config; README consumption example.

### Frontend exposure
This IS the frontend API. ViewModels expose StateFlows of existing UI-state models; Compose screens unchanged.

---

## Research Sources
LocalSend protocol v2 + site/benchmarks; Knit; bitchat-android; AirChat; mftp; Swoosh TRANSFER_FLOW; gusset; Stream offline-sync + chat architecture blogs; Android offline-first guide; Wi-Fi Aware overview; Nearby Connections overview; WhatsApp chat-module system design (ProAndroidDev), Droidly chat sysdesign.

## Decisions required (owner)
D1 DI framework · D2 At-rest encryption · D3 Hash library (SHA-256 vs BLAKE3 dep) · D4 Frame-level E2E on top of TLS · D5 Mesh relay scope (post-v1?) · D6 Sound feedback (unblocks UI-040).

## Risks
Scope creep beyond Phases 0–3 · battery policy needed before release (foreground service) · Wi-Fi Aware vendor fragmentation · outbox/sync/receipt concurrency (encode invariants in tests first) · E:-drive flakiness during long phases (commit incrementally).
