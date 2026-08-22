# Flash Core Upgrade Plan — PART 1: Core Components (v2)

**Status:** ACTIVE PLAN (revised 2026-08-22 after UI-requirements audit + web research)
**Created:** 2026-08-22 · **Revised:** 2026-08-22 (v2 — extensive step breakdown, UI-dependency inventory, continuous discovery, multi-stream transfer, full security stack)
**Companion doc:** [ui-page-plan.md](ui-page-plan.md) — **PART 2: Pages & Navigation**
**Related:** ADR-005 (Pulse identity) · ADR-008 (multi-module libraries) · ADR-009 (FlashText)

> PART 1 upgrades each `:core:*` component one by one — abstraction first, then implementation, then the API exposed to the frontend.
> All page/UI planning (bottom navigation, settings, per-screen specs) lives in **PART 2**.

---

## 0. Ground Rules (binding for every step below)

### R1 — RESEARCH-FIRST RULE (mandatory)
**Every step starts with online research before any code is written.**
- Search official Android documentation and reputable engineering sources for the exact APIs/patterns the step touches.
- Record findings (source URLs + what was learned) in the step's component doc or `logs/experiments.md` before implementation.
- If an approach documented here conflicts with what current docs say, STOP and raise it with the owner — do not silently implement stale knowledge.
- Never repeat an approach already marked failed in `docs/known-issues.md` / `logs/errors.md` without a documented reason to retest.

### R2 — CORE IS A REUSABLE LIBRARY (mandatory)
- `:core:*` modules contain **no Compose UI, no Activity, no app-only code**. Android framework deps only where unavoidable (NSD, Keystore, ContentResolver) and always behind an interface.
- Consumers depend only on interfaces + models; concrete wiring happens once in `:core:engine` / `:app` (C7/C0.4).
- Each module gets `maven-publish` config and a version-catalog entry so it can be consumed outside this app later (Windows/Linux clients, Rust bridge interop goal).
- Public API surfaces are documented with KDoc; breaking changes to a published interface require a note in `docs/decisions.md`.

### R3 — VERIFIED STEPS ONLY
Every step ends with: unit tests written & green (`testDebugUnitTest`), build green, result logged in `logs/progress.md`. One consolidated Gradle run per work session (agents never run Gradle).

### R4 — NO REGRESSIONS
Existing green tests (~271) stay green. Existing public interfaces (`FlashChatRepository`, `FlashNetwork`, `FlashDiscovery`, …) evolve additively where possible; breaking changes get adapter steps.

### R5 — LOG EVERYTHING
Per AGENTS.md §28: progress, errors (full ERROR-0XX entries), experiments/benchmarks, decisions (ADR), handoff updates at session end. Historical log entries are never deleted.

---

## 1. Owner Decisions (confirmed 2026-08-22)

| ID | Decision | Status |
|---|---|---|
| D1 | DI framework — **Hilt recommended**, owner sign-off pending before C0.4 | ⏳ OPEN (default: Hilt) |
| D2 | At-rest encryption — **SQLCipher full-database encryption** (Signal-style), key held in AndroidKeyStore | ✅ APPROVED |
| D3 | Hash library — **SHA-256** (`java.security`, ARMv8 crypto-accelerated, zero deps) for chunks/messages/fingerprints | ✅ APPROVED |
| D4 | Frame-level E2E — **implement in C2**: ECDH P-256 → AES-GCM per paired peer, layered over TLS | ✅ APPROVED |
| D5 | Mesh relay scope — **post-v1**; v1 = direct P2P only (LAN/Wi-Fi Direct/Aware/BLE-presence). Design seams kept | ✅ APPROVED |
| D6 | Sound feedback (unblocks UI-040) — owner product decision still required | ⛔ BLOCKED |

---

## 2. Removed Demo Pages (owner decision, 2026-08-22)

Four provisional pages were removed from the app shell:
1. **Icon QA sheet** (`FlashIconSheet`) · 2. **Motion QA sheet** (`FlashMotionSheet`) · 3. **Experimental WS transfer page** (`WsTransferScreen`, `:ui:transfer` deleted) · 4. **LAN demo home** (`FlashHomeScreen`).

Engine classes (`LanController`, `WsTransferManager`, `WsDiscovery`, `WsPairingStore`, `AppIdentity`) remain in `:app` temporarily as relocation sources for C4/C5. Their UI entry points no longer exist.

---

## 3. PART A — What the Finished UI Needs From Core (requirements audit, 2026-08-22)

Audited from all `docs/ui/*.md` component docs. This inventory is normative: the C-steps below exist to satisfy it. Everything visible today runs on **sample data**; the items below are the actual gaps.

### 3.1 Capability → Module map

| # | Capability required by UI | Sourcing module | Consuming UI (IDs) |
|---|---|---|---|
| A1 | Message/conversation persistence, 1k–10k-message histories, stable IDs, windowed loads | C1 + C6 | UI-003, UI-005, UI-021/022, UI-043 |
| A2 | Outbox send path: instant bubble, PENDING → Sent → Delivered → Read → Failed/Retrying transitions | C6 | UI-015, UI-013 send states |
| A3 | Delivery receipts (3 distinct P2P ACK stages: socket-written / peer-ACK / viewport-ACK) | C6 + C4 | UI-015, UI-027 |
| A4 | Incoming/outgoing typing events, aggregated multi-person ("Alex and Sam are typing…") | C6 | UI-014, UI-004 |
| A5 | Peer presence per conversation member (Online/Offline/Connecting) + device connect/disconnect events | C6 ← C3/C4 | UI-004, UI-030, UI-041 |
| A6 | Connection health inputs: transport type, peer presence, peer count → Connected/Connecting/Degraded/Offline; manual `retryConnection`; engine-owned auto-retry backoff indicator | C4 (+C7) | UI-030, UI-044, UI-027 |
| A7 | `isEncrypted` per conversation; `isVerified` per peer (currently integrator passes `false`); key-changed warning events | C2 | UI-031 |
| A8 | Pairing lifecycle events (request received w/ name+transport+6-digit code, peer accepted, peer declined, expired w/ engine-side timeouts); accept/decline actions; trust persistence | C2 | UI-032, UI-031 sheet rows |
| A9 | Attachment pipeline: image/file/voice models with real URIs, dimensions, thumbnails, amplitudes; Save/Share/Forward/Open actions; Photo Picker/SAF/camera/audio ingestion | C5 + C6 | UI-017/018, UI-016, UI-019/020, UI-012 |
| A10 | Transfer telemetry: byte progress, live speed (MB/s), ETA, state machine (offered→queued→transferring↔paused→completed\|failed\|cancelled + verifying); pause/resume/cancel/retry; explicit acceptance (never auto-download); completed-file URI resolution | C5 | UI-016 card, Transfers page |
| A11 | Multi-stream parallel transfer throughput (sender-side parallelism is the known bottleneck pattern; LocalSend uses parallel upload routes) | C5 | Transfers page perf |
| A12 | Continuous discovery: advertise own identity (device id/name/details) + continuously scan so peers present or just-joined appear without user action; lost-peer handling | C3 | Nearby tab, UI-025 empty state CTA |
| A13 | Recent-searches persistence; drafts persistence; first-run marker (zero-content vs returning-empty distinction); pinned/muted/archived flags | C1 + C6 | UI-024, composer, UI-025 |
| A14 | Settings storage: theme mode, dynamic accent toggle, haptics enable, reduce-motion honored (system), sounds (D6-gated), displayName, save location, retention days, autoAcceptTrusted | C1 (DataStore) | UI-035/036/039/040, Settings page |
| A15 | History-load completion signals (LCE gating + TalkBack announcements); unseen/new-while-scrolled counters driven by real arrivals | C6 | UI-026, UI-021/022 |
| A16 | Edit/delete as revision records + tombstones replayable in order; reaction add/remove synced; read cursors monotonic (furthest-forward merge) | C6 | context menu, UI-009, badges |
| A17 | Notification payload schema + conversation deep links + process-death backstack restore | C7 (+PART 2) | notification-ui.md (NOT STARTED), navigation revisit |
| A18 | Voice capture (RECORD_AUDIO flow + MediaRecorder/AudioRecord engine, live amplitude stream, produced file into transfer pipeline); playback decode (Media3 ADR) | C5/C6 + ADRs | UI-020, UI-019 |

### 3.2 Explicit sample-data limitations stated in UI docs (must be closed by this plan)

- `profile-ui.md`: pairing is a demo state machine — "no real crypto, no persistence, no engine callbacks"; per-side timeouts engine-owned; haptics deferred until engine wiring.
- `chat-screen.md` UI-031: "`isVerified` has no engine source yet"; verify-code/fingerprint sheet rows disabled until pairing lands; key-changed warning needs engine events.
- `chat-screen.md` UI-030: auto-retry/backoff "engine concern"; multi-hop copy waits for engine hop count (post-v1, D5).
- `error-states.md`: retry/backoff engine-owned; throttling simulation needs engine hooks (out of scope for UI).
- `file-card.md`/`voice-message.md`/`media-viewer.md`: attachment/transfer pipelines "not connected" — Toast placeholders for Share/Save/Forward; demo voice produces no file.
- `search-ui.md`: recents caller-owned, in-memory; "persisted (DataStore) once state storage lands".
- `loading-states.md`: loading announcements need repository wiring.
- `navigation.md`: deep links + process-death restore unsupported.
- `notification-ui.md`: NOT STARTED entirely.
- `performance.md`: Paging-over-Room deferred until stress measurement; bitmap fixtures needed from real media pipeline.

---

## 4. Component Order (dependency-driven)

| # | Component | Module | New? |
|---|---|---|---|
| C0 | Foundations | `:core:common` (+ DI in `:app`) | extend |
| C1 | Persistence | `:core:persistence` | **new module** |
| C2 | Security | `:core:security` | major upgrade |
| C3 | Discovery | `:core:discovery` | major upgrade |
| C4 | Network | `:core:network` | major upgrade |
| C5 | Transfer | `:core:transfer` | major upgrade |
| C6 | Messaging | `:core:messaging` | major upgrade |
| C7 | Engine facade | `:core:engine` | **new module** |

Each component section follows: **Current state → Target abstraction → Steps (fine-grained, research-first) → Frontend exposure.**

---

## C0 — Foundations (`:core:common` + DI)

### Current state
`FlashDevice(Id)`, `FlashPeerPresence`, `FlashTransportType`, `FlashResult/Error`, `FlashTextFraming`. No DI graph, ad-hoc `Log.i`, implicit protocol version, no envelope type.

### Target abstraction
```kotlin
object FlashProtocol { const val VERSION: Int = 2 }
class FlashLogger(tag: String) { fun i/w/e(msg); fun recent(): List<String> }   // ring buffer → future debug sheet
data class FlashEnvelope(id: String, type: String, payloadJson: String, senderId: String, sentAt: Long)
interface FlashTimeSource { fun nowMs(): Long }                                  // injectable for test determinism
```

### Steps
- **C0.0** *Research:* Kotlin multi-module convention-plugin patterns + version-catalog best practice (current docs). Add shared `flash-android-library.gradle.kts` conventions incl. `maven-publish` (R2).
- **C0.1** `PROTOCOL_VERSION` in `:core:common`; assert in WS + TCP handshakes (both directions); mismatch ⇒ typed error.
- **C0.2** `FlashEnvelope` shared by messaging + transfer frames (single wire container; version field inside).
- **C0.3** `FlashLogger` ring buffer (bounded, thread-safe) + structured tag constants (AGENTS.md §24 tags). Replace ad-hoc `Log.i` calls in `:core:*`.
- **C0.4** `FlashTimeSource` + `FlashIdGenerator` (UUID v4) abstractions; migrate sample repo + engine classes to them.
- **C0.5** DI graph skeleton in `:app` providing Context/dispatchers/engine singletons (**D1** — confirm Hilt before starting; research current Hilt/KSP setup). ViewModels constructor-injected.
- **C0.6** GitHub Actions CI: JDK 17, `testDebugUnitTest assembleDebug` on push/PR; cache `.gradle-user-home`.

### Frontend exposure
None directly; everything downstream consumes these.

---

## C1 — Persistence (`:core:persistence`, NEW)

### Current state
No persistence anywhere. UI consumes sample repositories; recents/drafts in-memory only.

### Target abstraction
```kotlin
@Dao interface MessageDao { paged flows; insert-or-ignore (idempotent dedup); updateStatus;
                           cursor ops (before/after messageId); revision append; tombstone }
@Dao interface ConversationDao / OutboxDao(attempts,nextAttemptAt) / TransferDao / TransferChunkDao
@Dao interface RecentSearchDao / TrustedPeerDao(deviceId PK, fingerprint, trustedAt) / ReactionDao / DraftDao / ReadCursorDao
@Database FlashDatabase(version=1)   // opened through SQLCipher (D2)
class FlashSettingsDataStore { themeMode; dynamicAccent; hapticsEnabled; reduceMotionOverride;
                               autoAcceptTrusted; saveLocationUri; retentionDays; displayName; soundsEnabled(D6) }
```

### Steps
- **C1.0** *Research:* current Room versions + SQLCipher-for-Android integration (passphrase via AndroidKeyStore-wrapped AES key), DataStore preferences vs proto, Room Paging integration status. Cite sources in component notes.
- **C1.1** Create `:core:persistence`; deps: Room, SQLCipher, DataStore, Paging (compileOnly until measured), `:core:common`. Register module + version catalog (R2).
- **C1.2** Entities: `MessageEntity(localId UUID PK, conversationId, senderId, senderName?, text, sentAt, status, editedAt?, deletedAt?)`, `ConversationEntity(id PK, title, isGroup, pinned, muted, archived, sortOrder, lastReadCursor)`, `ReceiptEntity`, `OutboxEntity(localId PK, attempts, nextAttemptAt, payloadJson)`, `TransferEntity(transferId PK, bytesDone, totalBytes, status…)`, `TransferChunkEntity(transferId+index composite PK, done)`, `RecentSearchEntity(query PK, usedAt)`, `TrustedPeerEntity(deviceId PK, name, fingerprintHex, trustedAt)`, `ReactionEntity(messageId+emoji PK, count, selfReacted, reactorIdsJson)`, `DraftEntity(conversationId PK, text, updatedAt)`, `ReadCursorEntity(conversationId+memberId PK, upToMessageId)`.
- **C1.3** DAOs returning `Flow`; all inserts `OnConflictStrategy.IGNORE` where dedup is intended (messages, receipts); explicit upsert where last-write-wins is correct (drafts). Cursor-paginated history queries (keyset on `(sentAt, localId)`), window sizes configurable.
- **C1.4** `FlashDatabase` + `SupportFactory(SQLCipher)` wiring behind an internal `FlashDatabaseOpener` (passphrase never logged; Keystore-wrapped). In-memory mode for unit tests.
- **C1.5** `FlashSettingsDataStore` with all keys from target abstraction; suspend + Flow getters; migration-safe key naming.
- **C1.6** Retention pruner spec: periodic worker deleting messages/transfers older than `retentionDays` (respects pinned conversations); unit-tested date math.
- **C1.7** Migration strategy: `FallbackToDestructiveMigration` forbidden from v2 onward; export schemas to repo; schema tests.
- **C1.8** DAO-level concurrency invariant tests (outbox drain races, duplicate receipt inserts, cursor monotonicity) — these encode the C6 guarantees before the engine exists.

### Frontend exposure
Raw DAOs stay internal; exposure happens through repositories in C6/C7 only.

---

## C2 — Security (`:core:security`) — full stack (D2/D3/D4 approved)

### Current state
Identity + Trust stores backed by SharedPreferences. Trust flag bound to nothing cryptographic. No TLS, no pairing crypto, no E2E.

### Target abstraction
```kotlin
interface FlashCrypto {
    val identityKeyPair: FlashKeyPair                       // ECDSA P-256, non-exportable (AndroidKeyStore)
    fun sign(data: ByteArray): ByteArray
    fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean
    fun ecdhSessionKey(peerPublicKey: ByteArray): SecretKeySpec   // ECDH P-256 → HKDF → AES-GCM key (D4)
}
interface FlashTlsContext { clientConfig(pinnedFingerprintHex: String?): SSLContext; serverConfig(): SSLContext } // self-signed cert per install
sealed interface FlashPairingEvent { RequestReceived(peer, transport, code6); PeerAccepted; PeerDeclined; Expired }
interface FlashPairingProtocol { beginRequest(peer); respondAccept(); respondDecline(); events: Flow<FlashPairingEvent> }
// TrustStore extended: fingerprint column, isPinned(deviceId, fingerprint), keyChanged event flow
```

### Steps
- **C2.0** *Research:* AndroidKeyStore ECDH support matrix (API levels, KeyProperties purposes `AGREE_KEY`/`SIGN`), self-signed cert generation (current BouncyCastle-light approaches vs platform `KeyPairGenerator` + X509), numeric-comparison code derivation precedents (Bluetooth SSP style), TOFU pinning pitfalls. Cite sources.
- **C2.1** Per-install ECDSA P-256 identity keypair in AndroidKeyStore (non-exportable, strongbox-backed when available, excluded from backup via manifest flags).
- **C2.2** Self-signed X.509 cert wrapping the identity key for the C4 TLS layer; regenerate-on-corruption policy.
- **C2.3** SHA-256 fingerprint of identity public key (D3) + stable hex-group formatting utility (feeds UI-031/032 displays); unit-test vector.
- **C2.4** Extend `FlashTrustStore`: store fingerprint with trust record; `isPinned(deviceId, fingerprintHex)`; `trustedPeers: Flow` (Room-backed via C1 `TrustedPeerEntity` with SharedPreferences migration step).
- **C2.5** TOFU policy object: first-connect prompt data; fingerprint mismatch ⇒ hard fail + `PeerKeyChanged` event (feeds UI-031 warning state); revocation clears pin.
- **C2.6** Pairing handshake frames (`PAIR_REQUEST / PAIR_ACCEPT / PAIR_CONFIRM / PAIRED`) with 6-digit numeric comparison derived from both fingerprints (research-confirmed construction, e.g., truncation of sorted-concatenated hash); both devices display simultaneously; engine owns request-expiry + per-side decision timeouts (closes profile-ui.md demo gap).
- **C2.7** Frame-level E2E (**D4**): ECDH P-256 at pairing → HKDF → AES-GCM session key; encrypt `FlashEnvelope.payloadJson` for paired peers on top of TLS; nonce discipline + rekey policy documented in `docs/security.md`.
- **C2.8** Constant-time comparisons for codes/fingerprints; crypto unit tests against RFC/test vectors; `docs/security.md` threat-model update (at-rest SQLCipher note cross-ref C1.4).

### Frontend exposure
`badgeState(isEncrypted, isVerified)` for UI-031 (real sources replace hardcoded `false`); pairing events consumed by Nearby tab + `FlashPairingDialog` (UI-032) including 6-digit code + countdown ownership; verify-code and fingerprint-view sheet rows become functional.

---

## C3 — Discovery (`:core:discovery`) — continuous, identity-aware discovery

### Current state
NSD advertise/discover for two service types; resolve queue hardened (ERROR-006). Manual start/stop only; no identity in TXT records; no lost-peer aging; no multi-radio.

### Target abstraction
```kotlin
interface FlashRadioTransport {
    val events: Flow<FlashTransportEvent>        // Found / Updated / Lost /StateChanged
    suspend fun startAdvertising(port: Int, identity: FlashAdvertisedIdentity): FlashResult<Unit>
    suspend fun startBrowsing(): FlashResult<Unit>          // continuous until stopBrowsing()
    suspend fun stop(): FlashResult<Unit>
}
data class FlashAdvertisedIdentity(deviceId, friendlyName, deviceModel, protocolVersion, listenPort)
// Impls: NsdTransport (exists, upgraded) · WifiDirectTransport · WifiAwareTransport · BlePresenceTransport
class CompositeDiscovery(vararg transports) : FlashDiscovery   // merges events, dedups by DeviceId, prefers LAN
```

### Behavior contract (sample-app facing)
One call — `discovery.startAll(identity)` — makes the device:
1. **Advertise** using its own device id/name/model details (TXT records), and
2. **Browse continuously**: peers already on the network appear within seconds; peers that join later appear without any user action; peers that leave age out and disappear.

### Steps
- **C3.0** *Research:* current `NsdManager` guidance — API 34+ deprecates `resolveService` in favor of `registerServiceInfoCallback`; API 34/T-extensions `DiscoveryRequest` overload combining discover+continuous monitoring; `discoverServices(networkRequest,…)` variant that survives Wi-Fi reconnects and fires proper found/lost across network changes; mDNS TTL/goodbye-packet reality on Android. Record in component notes with URLs.
- **C3.1** Extract `FlashRadioTransport` seam; refactor existing NSD impl behind it with **zero behavior change** (regression-checked against current tests).
- **C3.2** Identity-aware advertising: TXT records carry deviceId/friendlyName/model/protocolVersion/listenPort; self-advertisement filtered by own deviceId (not service-name string match).
- **C3.3** Continuous browsing loop: discovery runs until stopped; auto-restart on `onStartDiscoveryFailed`/internal failure with capped retries; `onServiceLost` handled properly; endpoint entries carry `firstSeenAt`/`lastSeenAt`.
- **C3.4** Resolution strategy split by API level: API 34+ uses `registerServiceInfoCallback` (continuous updates incl. address changes); older keeps the hardened `NsdResolveQueue` (ERROR-006). Prefer `NetworkRequest`-scoped discovery so Wi-Fi drops/rejoins produce correct Lost/Found instead of stale entries.
- **C3.5** Presence sweeper: periodic pass marks endpoints lost when `now - lastSeenAt > graceWindow` (configurable, e.g., 30 s) even if a radio misses goodbye; emits `Lost` events so presence/UI converge.
- **C3.6** Wi-Fi Direct transport: discovery + group lifecycle, group-owner IP resolution, reconnect handling (prior art from early probes + `logs/experiments.md`).
- **C3.7** Wi-Fi Aware transport (API 26+, publish/subscribe `_flash._pair`), TCP-over-Aware link; vendor-fragmentation guards behind feature checks.
- **C3.8** BLE presence transport: advertise/scan only — signals "peer nearby", wakes higher-bandwidth paths (no data over BLE in v1).
- **C3.9** `CompositeDiscovery`: merge + dedup by DeviceId (multi-radio peers collapse to one endpoint with richest transport); chosen-path reported into `FlashTransportType`; endpoint state machine Found → Ready(connectable) → Lost.
- **C3.10** Bind discovered endpoint ↔ peer fingerprint (from C2 identity exchange) so TOFU pinning applies **pre-connection**.
- **C3.11** Device-test battery (physical phones): cold join (peer joins after scan started), hot leave, Wi-Fi toggle, AP roam — record timings in `logs/experiments.md`.

### Frontend exposure
`state` + `discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>>` (extended with transport richness + seen timestamps) consumed directly by the future Nearby tab; peer count feeds UI-030/UI-044 health math.

---

## C4 — Network (`:core:network`) — robust & resilient

### Current state
TCP server/connect + heartbeat session; WS client/server + codec. Plain text; single stream; manual reconnect; half-open connections undetected.

### Target abstraction
```kotlin
interface FlashSecureChannel : FlashSession { /* TLS + framed send over any socket */ }
// FlashNetwork gains:
val connectionHealth: StateFlow<FlashConnectionHealth>   // Connected/Connecting/Degraded/Offline (feeds UI-030/044)
fun retryConnection()                                     // manual trigger wired to banner Retry button
// internal: ReconnectEngine (backoff+jitter), HeartbeatManager (dead-peer detect), PerPeerSendQueues (bounded)
```

### Resilience upgrades (owner-approved direction)
1. **Auto-reconnect:** exponential backoff with jitter (base 1 s, ×2 growth, cap 30 s, ±20 % jitter); reset on stable-connect.
2. **Instant reconnect trigger:** `ConnectivityManager.NetworkCallback.onAvailable` fires immediate reconnect attempt bypassing backoff (Wi-Fi drop/rejoin case).
3. **Dead-peer detection:** heartbeat ping/pong interval (e.g., 10 s) + missed-threshold ⇒ session torn down cleanly rather than hanging half-open; feeds presence Offline quickly.
4. **Lifecycle binding:** connect ON_START / idle-disconnect ON_STOP helper (unless background-transfer mode active via C5.12 service).
5. **Backpressure:** per-peer bounded send queues (capacity + suspension, never unbounded buffering); overflow ⇒ typed error, outbox retains the write (C6 owns retry).
6. **Concurrent-session limits** + duplicate-peer connection coalescing (same DeviceId over LAN and Direct = one logical session, best path wins).

### Steps
- **C4.0** *Research:* TLS with self-signed certs on Android sockets (SSLSocket custom X509TrustManager + pinning), `NetworkCallback` semantics, TCP keepalive options vs app-level heartbeat trade-offs, half-open detection patterns. Cite sources.
- **C4.1** TLS wrap (self-signed from C2.2, TOFU pinning from C2.5) for TCP sessions and WS server/client; plaintext dev mode loudly flagged + tracked debt item (AGENTS.md §19).
- **C4.2** `ReconnectEngine` implementing upgrades 1–2 above, with injected `FlashTimeSource` for deterministic unit tests (fake-clock backoff sequence test).
- **C4.3** `HeartbeatManager` (upgrade 3): interval/threshold configurable; integrates with presence reporting.
- **C4.4** Foreground-lifecycle binding helper (upgrade 4) as a small class consumed by `:app`, no Android-Activity types in `:core:network` itself (R2).
- **C4.5** Session-manager hardening (upgrade 6): concurrent limits, coalescing, per-peer queues (upgrade 5).
- **C4.6** Transport-agnostic endpoints: accept sockets from Aware/Direct interfaces (C3), not just LAN NICs; bind-to-interface plumbing researched per radio.
- **C4.7** `connectionHealth` aggregation + `retryConnection()`; emit granular sub-states (Connecting/Reconnecting attempt n) so UI-044 scenarios (instant disconnect → slow connect → reconnect → offline) update screens without rebuild.
- **C4.8** Delivery-ACK hooks: expose per-frame ack callbacks (socket-written / peer-ACK) consumed by C6 receipt logic.
- **C4.9** Chaos harness: fault-injection wrapper dropping/stalling/duplicating frames; resilience invariant tests (idempotent redelivery tolerated, no deadlock under queue-full).

### Frontend exposure
`networkState` / `connectionHealth` / `activeSessions` flows feed the connection banner (UI-030), Nearby tab states, and error-state severity classification (UI-027/UI-044).

---

## C5 — Transfer (`:core:transfer`) — chunked, resumable, MULTI-STREAM

### Current state
Interface + framing models; real manager sits in `:app` (relocation source). Single stream, no resume, whole-file reads.

### Target abstraction
```kotlin
data class ChunkFrame(transferId, index, hashHex /*SHA-256 (D3)*/, compressed: Boolean, payload)
data class AckBatch(transferId, indexes: List<Int>)
// New: FILE_START{manifestHash,fileHash,totalChunks,size} → CHUNK… → batched ACK → COMPLETE{verified}
// DestinationPolicy: SAF uri | auto-save dir | MediaStore copy for media
// TransferStateMachine: offered→queued→transferring↔paused→completed|failed|cancelled (+verifying)
//   — exactly the states UI-016 file card renders today
```

### Steps
- **C5.0** *Research:* LocalSend protocol v2 (parallel upload routes, sha256 verification w/ per-file tokens, resumable uploads), mFTP/Swoosh multi-stream patterns, adaptive chunk sizing literature; zstd JNI cost on Android. Cite sources; record expected gains as hypotheses to benchmark, not facts.
- **C5.1** Relocate `WsTransferManager` (+ `WsDiscovery`/`WsPairingStore`/`AppIdentity` bits as needed) from `:app` into `:core:transfer`; `:app` left with no engine classes.
- **C5.2** Implement `FlashTransferRepository` against relocated manager; delete demo-only paths.
- **C5.3** Framing v2: `FILE_START{fileHash,totalChunks}` → `CHUNK{index,hash,payload}` → batched ACK → `COMPLETE{verified}`; version-negotiated via `FlashEnvelope` (C0.2).
- **C5.4** Chunker: 64 KB default, adaptive 16–256 KB from measured throughput; `ContentResolver` streaming reads (never whole-file in RAM — AGENTS.md §18).
- **C5.5** Per-chunk SHA-256 (D3) verified **before** disk write; mismatch ⇒ request only that chunk (LocalSend-style targeted repair).
- **C5.6** Resume bit-vector persisted (`TransferChunkEntity`); reconcile-on-reconnect: sender asks receiver for done-set, resumes at first hole; source/destination identity validated before resume (§18).
- **C5.7** **MULTI-STREAM TRANSFER (explicit feature):** N parallel socket streams per transfer (configurable 1–4, default 2), dispatcher assigns chunk ranges round-robin per free stream, per-stream windows + shared ACK batching every 32 chunks, aggregate throughput telemetry exposed per transfer. Benchmark 1 vs 2 vs 4 streams on physical devices into `logs/experiments.md` (EXP-0XX) before fixing defaults — measure, don't assume (AGENTS.md §23).
- **C5.8** Optional zstd per-chunk compression, skip-if-incompressible heuristic (off by default until benchmarked).
- **C5.9** Receive pipeline: destination policy (SAF picker / auto-save dir / MediaStore for media); **explicit acceptance required — never auto-download** (file-card contract).
- **C5.10** Multi-file manifest frame + multi-recipient parallel dispatch (per-peer progress rows for the Transfers page).
- **C5.11** TEXT share frame type (clipboard-style sends).
- **C5.12** Progress foreground service (`dataSync` type) + completion/failure notifications; battery-policy note in `docs/android-platform-notes.md` (verify current FGS restrictions during research pass).
- **C5.13** Speed/ETA calculator (rolling window) feeding UI-016 metrics; completed-file URI resolution for Open intent (SAF/FileProvider).
- **C5.14** Storage-bottleneck instrumentation: log network-limited vs storage-limited classification per transfer (PERFORMANCE tag) — required before any optimization claims.

### Frontend exposure
`activeTransfers: StateFlow<List<FlashTransferUi>>` feeds Transfers tab rows/cards verbatim (existing UI-016 card language); per-attachment `transferState` merges into `FlashFileAttachmentUi` via C6/C7 mappers.

---

## C6 — Messaging (`:core:messaging`) — the complete screen-facing API

### Current state
Rich UI-state models + `SampleFlashChatRepository`. No persistence, no send path, no receipts/sync/presence sourcing.

### Contract
`RealFlashChatRepository : FlashChatRepository` — **same interface the finished screens consume today** — extended additively (R4). Backed by C1 persistence, C4 sessions, C2 trust. The finished chat UI must compile unchanged against it; new capabilities surface as extra model fields.

### Required API surface (derived from §3 audit — implement all)

**Queries / flows**
- `chatListState`, `conversationState` (existing) — now Room-driven
- `searchMessages(query)` + global chat-list search (DB LIKE now; FTS decision inline-researched at step time) → feeds UI-023/024
- `recentSearches: Flow<List<String>>` (persisted; add/remove/clear) → closes UI-024 gap
- `draftFor(conversationId)` / autosaved drafts (composer hoisted state survives restart)
- `historyBefore(conversationId, cursor, limit)` windowed loads (Paging decision deferred to measurement per performance.md)
- `unreadCounts` derived from furthest-forward read cursors
- `syncState: StateFlow<SyncHealth>` (banner hook)

**Events (flows, ephemeral vs durable correctly separated)**
- message arrivals (stable IDs, burst-tolerant), status transitions Sending→Sent→Delivered→Read / Failed→Retrying
- typing start/stop per member (TTL'd, never persisted, debounced both directions) + multi-member aggregation strings input
- presence per member (Online/Offline/Connecting) from C3/C4 signals; device connect/disconnect events
- pairing/key-change security events relayed from C2
- history-load completion signals (LCE gating + TalkBack announcement hook)

**Mutations / actions**
- `sendText(text)` — outbox write → instant bubble → drain
- `sendAttachment(image/file/voice)` → C5 transfer + message row
- `retryMessage(localId)` (manual tap-to-retry; engine auto-retry w/ backoff + indicator per error-states.md deferral)
- `editMessage(id, newText)` / `deleteMessage(id)` — revision records + tombstones, replayed in order
- `setReaction(messageId, emoji, on)` — synced counts/self-flag
- `markReadUpTo(conversationId, messageId)` — batched cursor receipts (debounce ≈2 s, furthest-forward merge — never moves backward)
- `saveDraft(conversationId, text)` / clear
- archive/pin/mute conversation; forward message/media; share/save-to-gallery routing into C5 destination policy
- conversation-scoped subscription lifecycle: `openConversation(id)` starts session-bound observation + marks-read; `closeConversation()` releases

**Derived state the screens ask about (must be sourced, not faked)**
- `isEncrypted` per conversation (channel type from C4 TLS/E2E)
- `isVerified` per peer (C2 trust store) — replaces hardcoded `false`
- transport label + peer count for connection-health math
- first-run marker (zero-content ever vs returning-empty) for UI-025 variants

### Steps
- **C6.0** *Research:* offline-first mobile chat patterns — durable outbox, idempotent client-generated IDs, pull-before-push reconnect delta sync (SYNC_REQUEST(cursor) → atomic apply → flush outbox), order-sensitive merges for receipts/reactions vs last-write-wins for drafts, ephemeral-vs-durable state separation, tombstone deletes. Sources already surveyed 2026-08-22 (Stream offline-sync, WhatsApp/HLD chat-system designs, iOS messenger sysdesign) — recheck for anything newer at implementation time.
- **C6.1** Outbox pattern: send writes PENDING row → Room emits bubble instantly → dispatcher drains over sessions; process-death safe (durable, not memory).
- **C6.2** Idempotency: local UUID travels end-to-end; receiver IGNORE-duplicates; sender treats echoed duplicate as success (at-least-once tolerated).
- **C6.3** Receipts: DELIVERED ack post-insert; READ as batched cursor (`upToMessageId`) on open + debounce; three ACK stages mapped from C4.8 onto UI-015 glyph states.
- **C6.4** Edit/delete revision records replayed in order; UI shows "edited"/"deleted" from applied state.
- **C6.5** Reconnect delta sync: SYNC_REQUEST(cursor) → atomic batch apply → flush outbox (pull-before-push, Stream-pattern ordering); cursor high-watermarks per conversation.
- **C6.6** Typing/presence ephemeral frames: TTL, never persisted, start/stop debounced; incoming aggregation input for named typing; presence derived from heartbeat/session/discovery convergence.
- **C6.7** Multi-peer fan-out to all active sessions; per-peer delivery receipts (per-member ticks groundwork for groups).
- **C6.8** Unread counts from furthest-forward read cursors; badge corrections after sync (never trust cached badge alone).
- **C6.9** Search: DB-backed query + persisted recents; FTS vs LIKE decision made with citations at step time.
- **C6.10** Conversation lifecycle + mark-read + drafts + first-run detection.
- **C6.11** Encryption/verification state sourcing into header/badge models (C2-fed).
- **C6.12** Concurrency invariant tests FIRST (encode §Risks invariants): outbox drain race, duplicate delivery, cursor monotonicity, revision ordering, receipt batching — red-green before engine wiring.
- **C6.13** Swap `SampleFlashChatRepository` out of `MainActivity` behind debug flag (samples remain for previews/screenshots/tests).

### Frontend exposure
Unchanged public interface for existing screens; new capabilities surface as extra model fields (edited/deleted flags, sync-health banner hook, real presence/typing/encryption values). ViewModels in C7 wrap this.

---

## C7 — Engine Facade (`:core:engine`, NEW)

### Target abstraction
```kotlin
interface FlashEngine {
    val chats: FlashChatRepository          // Real implementation (samples behind debug flag)
    val transfers: FlashTransferRepository
    val discovery: FlashDiscovery           // startAll(identity) continuous mode included
    val security: FlashSecurityFacade       // badge states, pairing events, trusted peers, fingerprint view
    val settings: FlashSettingsRepository   // wraps DataStore (C1.5)
    val network: FlashNetworkFacade         // connectionHealth, retryConnection, sessions (UI-030/044)
}
```

### Steps
- **C7.1** Create module aggregating implementations; single construction point used by DI (C0.5).
- **C7.2** Per-screen ViewModels: ChatList, Conversation, Transfers, Nearby, Settings, Pairing — constructor-injected fakes for tests.
- **C7.3** Entity→UI-state mappers live here (screens never see DB types); attachment URI resolution + amplitude passthrough.
- **C7.4** Publish config + README consumption example demonstrating out-of-app reuse (R2 proof).
- **C7.5** Notification/deep-link hooks stubbed behind interface (schema lands with PART 2 pages + notification-ui.md research).

### Frontend exposure
This IS the frontend API. ViewModels expose StateFlows of existing UI-state models; Compose screens unchanged.

---

## 5. Execution Phases (commit-sized milestones)

| Phase | Contents | Exit criteria |
|---|---|---|
| P0 | C0.0–C0.6 | CI green; protocol version asserted; DI skeleton builds |
| P1 | C1.0–C1.8 | Schema exported; DAO invariant tests green; SQLCipher open/roundtrip tested |
| P2 | C2.0–C2.8 | Keygen/cert/pairing-vector tests green; TrustStore migration covered |
| P3 | C3.0–C3.5 (LAN continuous) | Two-phone device battery passed; join/leave < 5 s observed |
| P4 | C4.0–C4.9 (TLS + resilience) | Chaos-harness invariants green; TLS on both transports |
| P5 | C5.1–C5.7 (chunked multi-stream) | Resume-after-kill works; EXP benchmark recorded |
| P6 | C6.0–C6.13 | Screens run on RealFlashChatRepository behind debug flag |
| P7 | C3.6–C3.8 remaining radios + C5.8–C5.14 + C7 | Engine facade published; PART 2 pages can wire |
| P8 | Device verification backlog + UI-045 quality gate | Owner sign-off |

Scope guard: anything beyond these phases (mesh relay D5, BLE data, video attachments, notification-ui) is **post-v1** unless the owner reprioritizes.

## 6. Risks
Scope creep beyond P0–P7 · battery policy before release (FGS) · Wi-Fi Aware vendor fragmentation · outbox/sync/receipt concurrency (encoded as tests first, C6.12) · SQLCipher native-lib size (~7 MB APK growth — acceptable per D2) · E:-drive flakiness during long phases (commit incrementally, ERROR-008 recovery documented).

## 7. Research Sources (seed list)
Android NsdManager/API-34 ServiceInfoCallback/DiscoveryRequest docs (surveyed 2026-08-22); LocalSend protocol v2 (parallel upload routes, sha256 chunk verification, resumable uploads — surveyed 2026-08-22); Stream "sync chat state offline" (pull-before-push, state-class separation — surveyed 2026-08-22); WhatsApp/HLD chat-system design (outbox, cursor receipts, ephemeral TTLs — surveyed 2026-08-22); iOS messenger system design (cursor pagination, idempotency keys, WS backoff). **Per R1, every step re-verifies its area against current docs before coding.**
