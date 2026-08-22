# Flash Core Upgrade & Frontend API Exposure Plan

**Status:** PROPOSED (planning only — nothing implemented)
**Created:** 2026-08-22
**Owner sign-off:** pending
**Related:** [AGENTS.md](../AGENTS.md) · [decisions.md](decisions.md) · ADR-008 (multi-module libraries)

---

## 1. Purpose & Scope

Flash's UI roadmap is complete (UI-001–045 implemented against **sample data**). This plan defines how to upgrade the six `:core:*` library modules from their current "contract + partial implementation" state into a real local-first engine, and how to expose that engine to the Compose frontend through a clean, publishable API surface — without breaking ADR-008 (standalone libraries, unidirectional deps).

Everything here is a **plan**. No code has been written for it yet.

---

## 2. Current Core Inventory (as surveyed 2026-08-22)

| Module | Public API today | Works | Gaps |
|---|---|---|---|
| `:core:common` | `FlashDevice(Id)`, `FlashPeerPresence`, `FlashTransportType`, `FlashResult/Error`, `FlashTextFraming` | Solid foundations | Missing: message envelope types, protocol version constant shared by WS+TCP, clock/timestamp utility |
| `:core:security` | `FlashIdentityStore` (persistent device identity + name), `FlashTrustStore` (trust/revoke/list peers) | Identity generation + Preferences-backed persistence works | No crypto at all: no keypairs, no TLS, no session encryption, no pairing handshake protocol; trust is a flag, not bound to a key fingerprint |
| `:core:discovery` | `FlashDiscovery` (NSD advertise/discover for `_flash-transfer._tcp` + `_flashws._tcp`), resolve queue | LAN NSD works on-device | NSD-only; no BLE, no Wi-Fi Aware, no Wi-Fi Direct discovery path; endpoints lack fingerprint/identity binding |
| `:core:network` | `FlashNetwork` (TCP server/connect/manual, sessions), `LanSession` heartbeat, WebSocket client/server + RFC6455 codec | TCP probe/session + WS transfer server/client work | Plain-text sockets; single-stream transfers; no TLS; no multi-peer routing/mesh; session reconnect not automatic |
| `:core:transfer` | `FlashTransferRepository` interface (sendFile/pause/resume/cancel + StateFlow) | Interface + WS framing models only — **the real manager (`WsTransferManager`) still lives in `:app`** | No chunking/resume/checksum/compression; whole-file streaming only; no receive-side SAF/MediaStore pipeline |
| `:core:messaging` | `FlashChatRepository` interface + rich UI-state models | Sample repository only; UI consumes samples everywhere | **No real implementation**: no persistence, no send/receive path, no receipts/edit/delete/sync |
| `:app` | Showcase wiring | Screens render; WS transfer demo runs | Contains engine code that belongs in `:core:transfer`; manual screen switching (UI-033 state holder exists but unwired) |

**Cross-cutting gaps:** zero persistence anywhere (Room/DataStore absent), no DI graph, no foreground service, no WorkManager usage, no notifications, no settings storage, no logging framework beyond `Log.i` tags.

---

## 3. Research Summary (sources)

| Source | Takeaways adopted in this plan |
|---|---|
| LocalSend protocol v2 (`localsend/protocol`, localsend.org) | Receiver-runs-HTTP-server model; metadata-only prepare step then parallel binary sends; PIN query verification; reverse transfer via browser URL; multi-recipient parallel send; auto-accept/quick-save settings |
| Quick Share vs LocalSend benchmarks (punprime.com, androidauthority.com) | LAN saturates far higher throughput than Wi-Fi Direct tunnels → prefer LAN when available; Wi-Fi Direct as no-router fallback; multi-device broadcast as first-class mode |
| Knit (offline mesh messenger) | Dual-radio transport seam (Wi-Fi Aware + BLE simultaneously), signed frames relayed byte-for-byte with TTL/hop dedup, jittered overhear-suppressed flooding, store-and-forward custody, offline APK self-share, SQLCipher-at-rest, no GMS |
| bitchat-android / AirChat | Noise XX (X25519 + ChaCha20-Poly1305) E2E pattern; P-256 Keystore identity keys excluded from backup; foreground `MeshService`; battery tiers (normal/conserve/critical) gating relay TTL; courier per-peer quotas; privacy-preserving background alerts |
| mftp | Per-chunk BLAKE3 + full-file hash; crash-safe resume bit-vector; RTT-adaptive chunk size & stream count; zstd per-chunk compression with skip-if-incompressible; TOFU self-signed TLS pinned to TrustStore |
| Swoosh TRANSFER_FLOW | 64KB adaptive chunks (16–256KB); SHA-256 per chunk + final file hash verify; pause/resume persisted state; ACK batching every N chunks; ZIP bundle download; speed/ETA tracking single-source-of-truth |
| gusset transport-and-security | Chunk-then-encrypt order for dedup; AAD-bound ciphertext addresses; mutual-TLS with certs derived from pairing secret; atomic apply-on-complete |
| Stream "sync chat state offline" + chat architecture blog | Durable content vs derived state vs ephemeral split; pull-delta-before-replay ordering; idempotent client IDs; INSERT OR IGNORE dedup; receipt batching (single READ_ACK upTo cursor); edits/deletes as revision records/tombstones; never replay stale typing/presence |
| Android offline-first guide + WhatsApp chat module design (ProAndroidDev, Droidly) | Room = single source of truth observed by UI; outbox table + WorkManager drain with exponential backoff + jitter; connect-on-foreground lifecycle; NetworkCallback instant reconnect; cache-first reads |

---

## 4. Target Architecture

```text
:app (showcase) ──► FlashEngine facade ──► ViewModels ──► Compose UI (done)
                        │
        ┌───────────────┼───────────────────────┐
        ▼               ▼                       ▼
 :core:messaging  :core:transfer         :core:discovery
        │               │                       │
        └───────┬───────┴───────────┬───────────┘
                ▼                   ▼
        :core:persistence      :core:network ── transports: tcp / ws / wifiaware / ble / wifidirect
                │                   │
                ▼                   ▼
        Room + DataStore      :core:security (TLS/TOFU, pairing, keys)
```

Rules preserved: unidirectional deps (ADR-008); UI never touches sockets; Room is the single source of truth the UI observes; every engine module stays independently publishable.

---

## 5. Phased Step-by-Step Plan

Each numbered item is an implementable unit of work (roughly one focused commit).

### Phase 0 — Foundations
1. Add Hilt (or Koin) DI skeleton in `:app`; provide `Context`, dispatchers, and engine singletons. *(Decision D1 below.)*
2. Introduce `FlashLogger` in `:core:common` (tagged, level-gated, ring-buffer for debug sheet) replacing scattered `Log.i`.
3. Define `PROTOCOL_VERSION` constant in `:core:common`; assert handshake equality in both WS and TCP paths.
4. Add shared message-envelope types to `:core:common`: `FlashEnvelope(id, type, payload, sentAt, senderId, sig)` used by messaging + transfer.
5. CI workflow file: run `testDebugUnitTest assembleDebug` on push (GitHub Actions, JDK 21).

### Phase 1 — Persistence (`:core:persistence`, new module)
6. Create `:core:persistence` module (Room + DataStore; depends on `:core:common` only).
7. Entities: `MessageEntity(localId PK, conversationId, senderId, text, sentAt, status, editedAt?, deletedAt?)`; `ConversationEntity`; `ReceiptEntity(messageLocalId, peerId, kind, at)`; `OutboxEntity(localId PK, conversationId, payloadJson, attempts, nextAttemptAt)`; `TransferEntity(transferId PK, direction, fileName, sizeBytes, bytesDone, status, peerId, createdAt)`; `RecentSearchEntity(query PK, at)`; `TrustedPeerEntity(deviceId PK, name, fingerprint, trustedAt)` (mirrors TrustStore for queryability).
8. DAOs with `Flow` queries; `OnConflictStrategy.IGNORE` inserts everywhere (idempotent dedup).
9. `FlashSettingsDataStore`: display name override, theme mode (system/light/dark), dynamic accent toggle, haptics on/off, auto-accept trusted peers, save-location URI, retention days.
10. Decide + apply at-rest encryption (Decision D2): SQLCipher vs Keystore-encrypted DataStore for small fields.
11. Retention job spec: periodic WorkManager pruning messages older than N days (configurable).
12. Wire persistence into `settings.gradle.kts` + version catalog; publish config like other modules.

### Phase 2 — Real Messaging Engine
13. Move sample repo aside; create `RealFlashChatRepository(deps: dao, network, identity, trust)` implementing existing `FlashChatRepository` interface unchanged.
14. Outbox pattern: `sendText()` writes PENDING row → Room emits bubble instantly → dispatcher drains outbox over an active session.
15. Idempotent delivery: `localId = UUID` travels end-to-end; receiver INSERT OR IGNOREs duplicates.
16. Receipts: DELIVERED ack immediately after DB write; READ batched as one cursor frame (`upToMessageId`) per conversation-open + debounced updates.
17. Edit/delete as records: `EditRecord`/`Tombstone` frames appended and replayed; UI shows "edited"/"deleted" states (bubble already renders text; extend model).
18. Reconnect sync: on session-up, send `SYNC_REQUEST(sinceLastCursor)` per conversation; apply incoming batch atomically in one Room transaction; only then flush outbox (pull-before-push ordering).
19. Typing/presence: ephemeral frames with TTL, never persisted; typing start/stop debounce (send at most 1 frame / 3s while typing).
20. Multi-peer fan-out: repository sends each outbound frame to all `activeSessions`; per-peer delivery receipts tracked individually.
21. Group conversation model upgrade: conversation = set of member DeviceIds; group messages fanned out identically (mesh flood comes later in Phase 5).
22. Unread counts derived from per-conversation read cursor stored locally (furthest-forward merge rule).
23. Replace all sample repositories in `MainActivity` wiring behind a build flag or settings toggle (keep samples for previews).

### Phase 3 — Transfer Engine v2
24. Move `WsTransferManager` + related code from `:app` into `:core:transfer` (ADR-008 cleanup); `:app` keeps only UI wiring.
25. Implement `FlashTransferRepository` for real against the moved manager; delete the demo path.
26. Framing v2: `FILE_START{transferId,name,size,totalChunks,fileHash}`, `CHUNK{transferId,index,hash,payload}`, `ACK{index…batched}`, `COMPLETE{fileHashVerified}`.
27. Chunker: default 64KB, adaptive 16–256KB from measured throughput; streaming read via ContentResolver (never whole-file in RAM).
28. Per-chunk integrity: SHA-256 (or BLAKE3 if a pure-JVM lib is accepted — Decision D3) computed before send, verified before disk write; mismatch triggers re-request of that chunk only.
29. Resume bit-vector persisted per transfer (Room `TransferChunksEntity`); both peers reconcile missing chunks on reconnect; UI surfaces "Resume" from the existing paused state.
30. Parallel streams: 2–4 concurrent chunk pipelines per transfer (configurable); ACK batching every 32 chunks.
31. Optional per-chunk zstd compression with skip-if-incompressible (magic-byte sniff) — off by default.
32. Receive side: SAF `CreateDocument` flow for user-chosen destination + optional auto-save to app-private `ws-received/` then MediaStore copy for images/video.
33. Transfer notifications: progress foreground service during active large transfers (existing notification permission); completion/failure notifications via WorkManager-safe path.
34. Multi-recipient send: same payload prepared once, dispatched in parallel to N selected peers; per-peer progress rows.
35. Folder/multi-file selection: SAF tree pick → manifest frame → sequential-or-parallel per-file transfers under one session id; receiver-side ZIP-bundle option deferred (backlog F14).
36. Text/clipboard share channel: tiny TEXT frame type reusing the transfer session (feeds composer send path too).

### Phase 4 — Security
37. Self-signed cert generation per device install; TLS wrap for both TCP session and WS server/client (cert pinning hooks exposed).
38. TOFU: on first connect, present fingerprint (SHA-256 of cert, formatted hex groups) → confirm dialog (reuse UI-032 pairing visuals) → store fingerprint in `FlashTrustStore` extended with `fingerprint` column.
39. Returning-peer check: fingerprint mismatch ⇒ hard-fail connection with explicit security event (key-changed warning state feeding UI-031).
40. Pairing protocol: `PAIR_REQUEST → PAIR_ACCEPT → CONFIRM_CODE(numeric comparison, 6-digit derived from both fingerprints) → PAIRED(frame)` wired to `FlashPairingDialog` (UI-032) and persisted via TrustStore.
41. Message-layer confidentiality option: symmetric session key exchanged at pairing (ECDH P-256 → AES-GCM per frame) — Decision D4 (cost/benefit given TLS layer).
42. Exclude identity keys from Android backup; regenerate-on-wipe policy documented.

### Phase 5 — Discovery Expansion
43. Extract `TransportSeam`: `interface FlashRadioTransport { advertise(); scan(); events }` so NSD becomes one impl among several.
44. Wi-Fi Direct discovery + group management behind the seam (device has prior art from early probes); connects to the same TCP/WS stack over the p2p interface.
45. Wi-Fi Aware (NAN) transport for API 26+ devices with the feature — highest-value no-router path; publish/subscribe `_flash._pair` service; open TCP over Aware link.
46. BLE advertise/scan fallback for presence-only signaling ("peer nearby, wake LAN path") — low bandwidth, high reachability.
47. Composite transport selector: prefer LAN → Aware/Direct → BLE-signaled wake; expose chosen transport in header (already displayed via `FlashTransportType`).
48. Optional mesh relay phase (explicitly post-v1): TTL/hop-count flood with dedup + jittered rebroadcast for group chats across hops (Knit/AirChat pattern) — separate ADR required.

### Phase 6 — Background Runtime
49. `FlashMeshService` foreground service (dataSync type): keeps discovery + sessions alive while user enables "background mode" toggle; persistent notification with stop action; privacy-preserving alert text (no bodies/names).
50. Battery tiers: normal / conserve (clamp relay + longer heartbeats) / critical (pause relays, keep direct paths) driven by BatteryManager thresholds (AirChat pattern).
51. WorkManager jobs: outbox drain (NETWORK_CONNECTED constraint, backoff 2ⁿ+jitter cap 30s), transfer retry worker, retention pruner, delta-sync-on-connect worker.
52. Boot-completed receiver → reschedule workers; do NOT autostart the foreground service without the user toggle.
53. ProcessLifecycleOwner: connect sessions ON_START, graceful idle disconnect ON_STOP unless background mode enabled.

### Phase 7 — Frontend API Exposure
54. `FlashEngine` facade in a new thin `:core:engine` module aggregating repositories: `chats, transfers, discovery, security, settings` — the ONLY import the app needs.
55. ViewModel factories per screen: ChatListViewModel, ConversationViewModel (messages Flow + send + search), TransfersViewModel, NearbyViewModel (endpoints + pairing events), SettingsViewModel, PairingViewModel. All take engine interfaces (testable with fakes).
56. Map DB entities → existing UI-state models (`FlashChatListUiState`, `FlashConversationUiState`, …) in repository layer so the finished Compose screens keep compiling untouched.
57. Wire UI-033 `FlashNavigationState` into MainActivity (replace boolean flags); consume `FlashAdaptiveTwoPane` on expanded width (chat list | conversation) — UI-034 integration.
58. Wire remaining deferred UI: UI-031 badge→sheet in header, UI-032 pairing overlay on `PAIR_REQUEST`, UI-044 sim sheet behind debug gate, stress-test screen entry (debug builds only).
59. Settings screen consuming `FlashSettingsDataStore` (theme mode incl. follow-system, dynamic accent toggle, haptics toggle, display name editor, retention slider, background-mode switch).
60. Notifications channel setup + permission flow (POST_NOTIFICATIONS, Android 13+) with per-conversation reply action placeholder.

### Phase 8 — Hardening & Release Readiness
61. Error taxonomy sweep: map all new failure modes onto `FlashError` variants; ensure UI error-states (UI-027) consume them.
62. Logging review pass: no secrets/fingerprints logged at info level.
63. R8/proguard rules per published module; API lint pass on public surfaces (no internal leakage).
64. README per publishable module (consumption example), mirroring ADR-008 goals.

---

## 6. Bottom Navigation Recommendation

Four destinations + one floating compose action — matches §22 primary flows and everything that now exists:

| Tab | Icon (Flash-owned) | Screen | Notes |
|---|---|---|---|
| **Chats** | existing chat-list affordance | `FlashChatListScreen` (+ UI-024 search, UI-025 empty state) | Home tab; badge dot for unseen total |
| **Transfers** | `flash_ic_transfer`-style (reuse Upload/Download pair or dedicated glyph) | Existing `WsTransferScreen` upgraded by Phase 3 | Progress rows double as notification deep-link target |
| **Nearby** | `FlashIcons.Device` or Wifi/WifiDirect composite | New Nearby screen: discovered peers list, tap-to-pair (UI-032 dialog), connection banner states (UI-030), network sim entry point in debug builds (UI-044) | This is Flash's differentiator — deserves top-level placement |
| **Settings** | sliders glyph (exists: `flash_ic_sliders`) | Phase 7 settings screen: identity name, theme/dynamic accent, haptics, background mode, retention, security sheet entry (UI-031) | |

Plus a **center-extending FAB "Send"** on the Chats tab opening the attachment/share sheet (UI-012 palette) — mirrors §22 "Send" being the primary action.

Rationale: five slots maximum (Material guidance); Nearby must be top-level because pairing/discovery is the app's core loop, not a sub-screen; Transfers earns its own tab because progress/history is glanceable content. Avoid bottom-nav labels longer than one word; use FlashIcons exclusively (§34).

---

## 7. Feature Backlog (research-derived, prioritized)

| ID | Feature | Source inspiration | Phase |
|---|---|---|---|
| F01 | Offline text messaging w/ outbox + delivery ticks | Stream/Droidly patterns | 2 |
| F02 | Edit + delete messages (tombstones) | Stream sync article | 2 |
| F03 | Batched read receipts | Stream batching (~90% traffic cut) | 2 |
| F04 | Reconnect delta sync (pull-before-push) | Stream sync article | 2 |
| F05 | Chunked resumable transfers w/ bit-vector | mftp / Swoosh | 3 |
| F06 | Per-chunk hash + final-file verify | mftp/Swoosh | 3 |
| F07 | Adaptive chunk size (16–256KB) | Swoosh | 3 |
| F08 | Parallel chunk streams (2–4) | mftp | 3 |
| F09 | Optional per-chunk compression | mftp | 3 |
| F10 | Multi-recipient parallel send | LocalSend | 3 |
| F11 | Folder / multi-file manifest transfer | LocalSend/Swoosh | 3 |
| F12 | Transfer progress foreground service + notifications | ProAndroidDev pattern | 3 |
| F13 | Auto-accept + quick-save for trusted peers | LocalSend | 3/Phase 4 |
| F14 | Receiver ZIP bundle download | Swoosh | backlog |
| F15 | Reverse transfer via browser link (receiverless) | LocalSend | backlog |
| F16 | Clipboard/text share channel | LocalSend/Swoosh | 3 |
| F17 | PIN verification on transfer prepare | LocalSend | Phase 4 |
| F18 | TLS + TOFU fingerprint pinning | mftp/gusset | 4 |
| F19 | Key-changed warning state | Signal pattern (feeds UI-031) | 4 |
| F20 | Numeric-comparison pairing protocol | Bluetooth SIG (feeds UI-032) | 4 |
| F21 | Session-layer E2E encryption (ECDH→AES-GCM) | Knit/bitchat | 4 (D4) |
| F22 | Wi-Fi Aware transport | Knit/Android docs | 5 |
| F23 | Wi-Fi Direct transport seam | AirChat | 5 |
| F24 | BLE presence signaling | bitchat/Knit | 5 |
| F25 | Background mesh mode + battery tiers | AirChat/bitchat | 6 |
| F26 | Store-and-forward custody for offline peers | Knit | backlog (post-mesh) |
| F27 | Offline APK self-share | Knit | backlog |
| F28 | Privacy-preserving background alerts | AirChat | 6 |
| F29 | Message retention/pruning job | WhatsApp design | 1/6 |
| F30 | Encrypted local store at rest | Knit (SQLCipher) | 1 (D2) |

Additional smaller items surfaced but unprioritized: speed-graph canvas on transfer cards (Swoosh), QR-code pairing alternative (Swoosh), share-target integration (receive from OS share menu), web-receive guest mode (LocalSend), geohash/location channels (bitchat — needs privacy review).

---

## 8. Decisions Required From Owner

| ID | Decision | Options |
|---|---|---|
| D1 | DI framework | Hilt vs Koin vs manual |
| D2 | At-rest encryption | SQLCipher vs Keystore-encrypted DataStore fields |
| D3 | Hash library | Pure-Kotlin SHA-256 (zero-dep) vs BLAKE3 artifact |
| D4 | Frame-level E2E encryption on top of TLS | Yes (defense-in-depth, cost) vs defer |
| D5 | Mesh relay (Phase 5.48/F26) | In-scope for v1.x vs explicitly post-v1 |
| D6 | Sound feedback (blocks UI-040) | Which sounds, which events |

---

## 9. Risks

- **Scope creep**: Phases 2–3 are the minimum lovable engine; Phases 5–6 can ship after.
- **Battery**: any always-on radio work needs the tier system (Phase 6) before release; foreground-service exemptions are Play-policy sensitive.
- **Wi-Fi Aware fragmentation**: vendor support varies; BLE fallback keeps the feature honest.
- **Concurrency bugs**: outbox + sync + receipts interact; the pull-before-push and idempotency rules are non-negotiable invariants — encode them in tests first.
- **E: drive instability** (ERROR-008): heavy multi-commit phases should be committed incrementally.
