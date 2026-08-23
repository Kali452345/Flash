# Decisions

## ADR-015 - Multi-stream dispatch: dynamic claim loop (MPSCP-style), first-free end-game tail, one shared ACK mirror answered per arrival channel (C5.7)

### Decision
1. **Work distribution = dynamic on-demand claiming**, not static range/round-robin partitioning: each idle channel worker claims the next unsent chunk from a shared cursor (+ retry pool of chunks returned by dead channels). End-game: when remaining work <= K=8 chunks, the FIRST free alive channel becomes sole owner and drains the tail one chunk at a time; others park and take over only if the owner dies.
2. **One shared sender-side confirmed mirror** (`ResumeBitVector`, monotonic-union) guarded by a single state lock - never per-channel vectors, because receiver ACK batches may arrive on ANY of the N channels. Dedup: already-marked indexes are never re-counted; duplicate/overlapping batches idempotent.
3. **Receiver replies (ACK_BATCH/COMPLETE) travel down the ARRIVING channel** (liveness symmetry, per-path congestion honesty, no routing table). Terminal COMPLETE coordination frame is emitted EXACTLY ONCE by whichever thread first observes full coverage (CAS).
4. Stream count configurable 1..4, default 2 (plan C5.7); defaults stay provisional until EXP benchmarks on physical devices.

### Context
LocalSend v2 parallelizes only across FILES (`POST /upload` per fileId, called in parallel - https://github.com/localsend/protocol §4.2); within-one-file striping needs GridFTP/PFTP/MPSCP prior art (https://www.osti.gov/servlets/purl/1143126): PFTP's static round-robin lets a slow stream head-of-line block its whole share, while MPSCP's "next block to the first available stream" naturally load-balances. BitTorrent keeps end-game request depth minimal so the tail cannot strand behind slow peers (https://blog.libtorrent.org/2011/11/writing-a-fast-piece-picker/). Aggregate throughput is computed as ONE rolling 2 s window over TOTAL confirmed bytes (never summed per-stream rates).

### Alternatives considered
- Static range partitioning per stream: rejected - head-of-line blocking on slow streams, measured worse in PDT studies above.
- Round-robin pre-assignment: rejected - same slow-stream pathology without death-reclaim flexibility.
- Per-channel confirmed vectors merged later: rejected - fragmented truth; late/duplicate ACKs across channels become ambiguous.
- Broadcasting every ACK batch to all N channels: rejected - wasted writes and double-count risk; arrival-channel reply + any-channel ingestion is sufficient.
- Spreading the last K chunks across all channels (BitTorrent duplicate-request style): rejected for SENDER-side dispatch - duplicates waste upload bytes; single-owner tail gives deterministic drain with owner-failover.

### Revisit when
EXP-0XX device benchmarks (1 vs 2 vs 4 streams) land; K=8 and default streamCount may be retuned. Pause/cancel and stall timeouts are engine-layer concerns around `MultiStreamDispatcher.send`.

## ADR-014 - Chunked transfer framing v2: self-contained binary frames, per-chunk SHA-256 verify-before-write, monotonic-union resume vectors (C5.3-C5.6)

### Decision
1. Framing v2 is a **self-contained binary format** (FLSH magic + version byte 2 + type byte + u32 LE payload length; LE scalars; u16-length-prefixed UTF-8 strings), documented in full in ChunkFrame.kt KDoc and traveling as FlashEnvelope payloads. Types: FILE_START{transferId,fileId,fileName,totalBytes,totalChunks,chunkSize,fileSha256Hex} / CHUNK{transferId,fileId,index,data,chunkSha256raw32} / ACK_BATCH{transferId,fileId,indexes asc} / COMPLETE{transferId,fileId,verified}.
2. Per-chunk SHA-256 carried **raw 32 B** (not hex); receiver verifies BEFORE sink write (C5.5). Mismatch = Rejected(HASH_MISMATCH), never written/marked/ACKed - absence from ACK batches is the implicit NACK driving targeted single-chunk repair.
3. Resume state is a BitSet-packed bit vector (ResumeBitVector: wordCount LE + LE words) with **monotonic-union** 
econcile merge rule on both receiver and sender mirrors.
4. ACK batching fixed at every 32 distinct verified chunks (ReceivePipeline.DEFAULT_ACK_EVERY); COMPLETE emitted only when the vector completes, optionally gated by an injected whole-file digest re-check.

### Context
LocalSend v2 supplies file-level sha256 at prepare-upload and answers 422 on mismatch; BitTorrent pins piece-level independent hashes so corruption localizes to one re-requestable unit and resume state is a grow-only bitfield. Binary framing chosen because CHUNK carries up to 256 KB opaque bytes - JSON/base64 would inflate wire volume ~33%+ per chunk.

### Alternatives considered
- Single running whole-file digest only: rejected - cannot localize corruption or validate partial resume state without a second pass.
- Hex-encoded per-chunk hashes: rejected - doubles hash overhead (64 B vs 32 B) per chunk.
- Sequence-number-only ACKs (cumulative): rejected - cannot express holes for out-of-order/multi-stream arrival (C5.7).
- Sender-side random-access seek on resume: deferred - linear read-and-discard skip kept for v1 (flash read >> LAN throughput); SeekableSource reserved.

### Revisit when
Rust/desktop client implements the layout (compat test vectors then mandatory), or C5.7 multi-stream needs windowed/selective ACK semantics beyond the batch set.

## ADR-012 - CompositeDiscovery cross-radio dedup priority + presence grace window (P3/C3.9)

### Decision
1. Cross-transport endpoint dedup keeps the highest-priority radio's endpoint, fixed order LAN > WIFI_DIRECT > WIFI_AWARE > BLE (unknown names last). Loss of the top sighting falls back to the lower radio with an Updated event; Lost is emitted only when the LAST sighting disappears (hysteresis).
2. Presence sweeper default grace window = 30 s (`CompositeDiscovery.DEFAULT_GRACE_MS`), boundary `now - lastSeenAt >= grace` (exactly-at-window expires).

### Context
Same peer is visible on multiple radios simultaneously (e.g., LAN + BLE presence). UI needs ONE endpoint reporting the richest connectable path. Radios routinely miss mDNS goodbyes: RFC 6762 sec 10.1 goodbyes are TTL=0 records often not sent on crash/kill; record TTLs are 120 s (SRV/A/AAAA) to 75 min (PTR/TXT) per sec 10 - far too slow for chat-style presence.

### Alternatives considered
- Most-recent-sighting-wins dedup (recency over priority): rejected - would flap between paths as radios re-announce at different cadences.
- Grace = 120 s (mDNS SRV TTL): rejected - departure convergence up to 2 min unacceptable for presence UI.
- Emit Lost immediately on high-priority loss while lower radio alive: rejected - factually wrong (peer reachable) and causes Lost/Found flapping in UI.

### Why selected
Priority order mirrors transport-bandwidth reality and Android's own ranked-transport model (AOSP NetworkRanker policy flags, NetworkCapabilities transports, Nearby Connections Strategy tradeoffs). 30 s matches plan C3.5 example, rides out single missed announcements without long stale-presence windows.

### Revisit when
Real-device benchmarks (C3.11) show 30 s grace causing stale rows on networks with aggressive multicast filtering, or LAN/WFD throughput ranking flips on measured hardware.


## ADR-011 - D1 = Hilt; D6 = opt-in subtle sounds (default off); Phase P0 executed

### Decision
Owner approved (2026-08-22):
- **D1:** Hilt (2.60.1, KSP 2.3.11) as the DI framework. Graph lives in `:app` (`:core:*` modules stay DI-agnostic, constructor-injected), per plan C0.5.
- **D6:** Sound feedback = subtle synthesized procedural tones, **opt-in with default OFF** (settings toggle persists via DataStore in C1.5). Unblocks UI-040.
- Phase P0 executed same session: `FlashProtocol`/`FlashEnvelope`, `FlashLogger` ring buffer, `FlashTimeSource`/`FlashIdGenerator` (:core:common), Hilt graph skeleton + `FlashApplication`, GitHub Actions CI (C0.6), UI-040 sound system (`FlashSounds`) implemented in :ui:theme.

### Context
Hilt chosen over Koin (compile-time safety, standard tooling) and manual DI (brittle at scale); verified compatible with AGP 9.3.1/Kotlin 2.2.10 via research (Dagger â‰¥2.59 requires AGP â‰¥9 â€” satisfied). Sounds chosen opt-in/off to match reduce-motion philosophy (motion/a11y-first app) until owner opts in.

### Alternatives considered
Koin (runtime-only error detection), manual AppContainer (fine now, brittle later); asset-based sounds (ships binaries for what synthesis covers), default-on tones (rejected by a11y philosophy).

### Revisit when
Capability-flag version negotiation if a second protocol consumer appears (Windows/Linux client); sound call-site wiring when real messaging engine lands (C6).

## ADR-010 - Core upgrade decisions D2/D3/D4/D5 approved; plan v2 adopted

### Decision
Owner approved (2026-08-22) during the core-plan iteration session:
- **D2:** SQLCipher full-database at-rest encryption, key wrapped in AndroidKeyStore.
- **D3:** SHA-256 (java.security, zero deps) for chunk/message hashes and fingerprints.
- **D4:** Frame-level E2E implemented in C2 â€” ECDH P-256 â†’ HKDF â†’ AES-GCM per paired peer, layered on TLS.
- **D5:** Mesh relay is post-v1; v1 = direct P2P only (hop-count seams reserved).
- Plan `docs/core-upgrade-plan.md` rewritten to **v2**: fine-grained research-first steps, UI-dependency inventory, continuous identity-aware discovery, resilient network upgrades, multi-stream transfer, exhaustive messaging API surface.

### Context
UI roadmap complete on sample data; the finished screens define exact required inputs (`isVerified`, presence, typing names, transfer telemetry, pairing events). Core must be a reusable library (no app/UI deps) and every implementation step must begin with cited web research.

### Alternatives considered
Keystore-wrapped field encryption only (rejected â€” weaker than owner-approved full-database option); BLAKE3 (deferred â€” zero-dep SHA-256 sufficient until benchmarks say otherwise); mesh relay in v1 (rejected â€” scope).

### Revisit when
D1 (Hilt vs Koin vs manual) still needs explicit sign-off before C0.5; D6 (sound feedback) blocks UI-040. Benchmarks may revisit hash choice after EXP entries exist.

## ADR-009 - FlashText primitive: chat text renders through the design system, not material3.Text

### Decision
All Flash chat UI text renders through the new com.transfer.flash.ui.theme.FlashText composable, built on foundation-level androidx.compose.foundation.text.BasicText (the same non-Material tier as the composer's BasicTextField) and styled exclusively through FlashTypography tokens. Components implemented from UI-018 onward use FlashText; bare material3.Text must not appear in new chat UI code.

### Context
AGENTS.md 34 requires M3 as infrastructure only and every visible identity element Flash-owned. An audit of UI-018-022 + UI-025/026/027 found all icons, buttons, chrome, gestures, and shapes custom, but text was rendered via material3.Text (styled with Flash tokens). Text is user-visible identity, so it belongs behind a design-system primitive like color/shape/motion already are.

### Alternatives considered
- Keep material3.Text with tokenized styles - rejected for new code: leaves visible identity on an M3 component.
- Custom Canvas text rendering - rejected: unjustifiable cost; BasicText already provides non-Material text layout.
- Big-bang migration of existing components - deferred: accepted components (UI-003-016) migrate opportunistically when next touched.

### Consequences
- ui:theme gains FlashText.kt (no new dependencies).
- Pre-existing Material usages flagged for later cleanup: material3.IconButton in FlashReplyDock, CircularProgressIndicator in FlashFileIconBadge (both predate this ADR), HorizontalDivider, Scaffold.


## ADR-008 â€” Modular Multi-Library Architecture & Standalone Component Hosting

### Decision
Transition the Flash codebase from a single `:app` monolithic module into a suite of decoupled, standalone Android/Kotlin library modules (`:core:common`, `:core:discovery`, `:core:network`, `:core:transfer`, `:ui:theme`, `:ui:chat`, `:ui:transfer`) with `:app` serving as the runnable showcase application. Each library module will be independently buildable, testable, and publishable to Maven repositories via the Gradle `maven-publish` plugin under the group `com.transfer.flash`.

### Context
The owner requested that Flash's components be usable individually by third-party developers:
- Core networking & transfer libraries (headless discovery, socket sessions, WebSocket mesh, SAF file streaming) can be consumed without any Jetpack Compose or UI dependencies.
- UI components (Flash Pulse design system tokens, custom icons, message bubbles, chat list, composer) can be consumed independently with pluggable backend repositories.

### Alternatives considered
- Single-module architecture with package-level separation â€” rejected (cannot publish individual artifacts; risks accidental coupling between UI and low-level networking).
- Monolithic single SDK library (`flash-sdk`) â€” rejected (forces UI consumers to pull in networking/sockets, and forces headless users to pull in Compose runtime).
- Fine-grained multi-module library suite (`:core:*`, `:ui:*`, `:app`) â€” selected.

### Why this was selected
- **Independent Consumption**: Developers can pull `com.transfer.flash:core-transfer` for headless file transfer or `com.transfer.flash:ui-chat` for custom messaging UI.
- **Strict Layer Isolation**: Compile-time enforcement prevents UI components from referencing socket connections directly.
- **Hosting & Publishing Ready**: Standardized `maven-publish` configuration across all library modules enables automated releases to MavenCentral, JitPack, or GitHub Packages.
- **Scalability**: Allows future multiplatform targets (e.g. Kotlin Multiplatform / Desktop / CLI) for `:core:common` and `:core:network`.

### Revisit when
When preparing the first public Maven release or when extracting pure JVM/KMP modules for non-Android targets (Desktop/CLI).

---

## ADR-007 â€” Experimental WebSocket transfer side track (hand-rolled RFC 6455, multi-peer)

### Decision
An experimental WebSocket-based transfer path lives in `wstransfer/` (`WebSocketCodec`, `WsConnection`, `WsTransferServer`, `WsTransferClient`, `WsTransferManager`) plus `ui/transfer/WsTransferScreen.kt`. It is a **side track at the owner's request** and does NOT replace the main LAN/TCP+TLS protocol plan. The RFC 6455 codec (upgrade handshake, masking, frame parse/serialize, fragmentation reassembly) is hand-rolled in pure Kotlin; no new Gradle dependency was added.

### Context
The owner asked for WebSocket-based transfer that lets 3+ devices pair and connect to each other with simple file transfer, explicitly "not part of our main design". OkHttp 4.12.0 exists in the Gradle cache only as a leftover of the reverted Stream SDK experiment, and OkHttp's WebSocket is client-only â€” every Flash device must be both server and client, so OkHttp alone could not satisfy the requirement.

### Alternatives considered
- OkHttp WebSocket client + separate WS server library â€” rejected (two dependencies, client/server split, and OkHttp has no server).
- `org.java_websocket` (TooTallNate) â€” rejected for now (new dependency; keep zero-dep until this track proves useful).
- Extend line-based `LanSession` â€” rejected (owner explicitly requested WebSocket framing).

### Why this was selected
- Zero new dependencies (AGENTS.md dependency rule); codec is pure JVM and unit-tested (RFC 6455 reference accept-key vector, masked/unmasked round trips, 16/64-bit lengths, fragmentation, close/ping).
- Server (port 45822 preferred) + client on every device â†’ any device can pair with any number of peers; peers keyed by device ID with outbound-preferred primary connection and inbound fallback, so a 3-device full mesh works.
- File bytes ride ordered binary frames between `FLASH_FILE_START` / `FLASH_FILE_END` text frames; receiver writes to `filesDir/ws-received/` and answers `FLASH_FILE_ACK` with byte-count verification.

### Revisit when
If this track graduates to the main design: add TLS (wss://), real pairing/trust UX, resume, hash verification, and reconcile with ADR-001's TCP session path. Also revisit the hand-rolled codec if extension support (compression) is ever needed.

---

## ADR-006 â€” Custom `FlashBubbleShape` concave tail geometry (UI-005)

### Decision
Message bubbles use a Flash-owned `Shape` (`FlashBubbleShape` in `ui/theme/FlashShapes.kt`) producing `Outline.Generic(Path)`: three circular corners plus one concave cubic-BÃ©zier "pulse scoop" (8dp) on the sender-facing bottom corner, mirrored in RTL via `LayoutDirection`. Grouped messages (`TOP`/`MIDDLE`) are fully rounded 20dp. No third-party bubble library.

### Context
The master plan forbids generic `RoundedCornerShape` rectangles as final bubbles, and the provisional zero-radius-corner tail read as a broken rectangle. UI-005 required real grouped geometry with a distinct silhouette.

### Alternatives considered
- Zero-radius corner tail (provisional) â€” rejected (accidental look).
- SmartToolFactory/Compose-Bubble library â€” rejected (dependency for one path, canvas-shadow style conflicts with Flash no-shadow policy).
- `graphics-shapes` morphing â€” rejected for now; revisit only if UI-006 research justifies it.

### Why this was selected
Zero new dependencies; clips/borders follow the path; RTL-correct; one path per measure; gives Flash a silhouette detail not used by reference apps.

### Revisit when
UI-006 insertion animation research or UI-045 quality gate suggests morphing shapes.

---

## ADR-005 â€” Flash Pulse visual identity (UI-001)

### Decision
Flash premium chat UI uses the **Flash Pulse** design system: teal pulse accent (`#0D9488` light / `#1FB8A6` dark), graphite neutrals, spark amber for transfer/status only, layered dark surfaces (void + surface0â€“3). Tokens live in `ui/theme/`. Chat UI must not use `MaterialTheme.colorScheme` for visible styling.

### Context
UI-001 research compared Material You-as-primary, Stream-look scaffold (ADR-003 era), and an original palette. Owner requires distinct identity per ADR-004.

### Alternatives considered
- Material dynamic color as primary â€” rejected (brand loss).
- Retain Stream-look `#005FFF` blue â€” rejected (clone risk, wrong P2P story).
- Flash Pulse teal + graphite â€” selected.

### Why this was selected
Distinct from major messaging apps; supports local/P2P semantics; documented light + dark palettes; optional `dynamicAccent` tints accent only (UI-036 foundation).

### Revisit when
UI-045 quality gate or owner requests rebrand; UI-036 adds user-facing dynamic accent toggle.

---

## ADR-004 â€” Premium chat UI: research-first, custom Flash design system

### Decision
Flash premium messaging UI will be built component-by-component using a **research-first** workflow documented in `docs/ui/`. Material 3 is infrastructure only; the visible chat experience must use Flash-owned design, motion, icons, and interactions. No proprietary chat SDK/source (Stream etc.).

### Context
The product requires Telegram/Signal/WhatsApp-level polish with Flash's own visual identity and P2P-aware UX. Prior exploratory conversation UI exists but is provisional and must not bypass per-component research.

### Alternatives considered
- Continue Stream-look clean-room scaffold as final UI â€” rejected (does not meet originality/premium component bar).
- Copy Telegram/Stream visuals â€” rejected (legal and product identity).
- Single-pass Material 3 chat screen â€” rejected (generic, not premium).
- Research-first custom system with documented UI-001â€“UI-045 sequence â€” selected.

### Why this was selected
- Matches owner requirement for documented research per component.
- Keeps networking independent of UI.
- Enables continuity across AI sessions via `docs/ui/` and AGENTS.md Â§34.

### Revisit when
UI-045 quality gate passes and owner accepts premium chat UI for release; or if a licensed third-party UI kit is explicitly approved in writing.

## ADR-003 â€” Clean-room Stream visual parity; no Stream SDK or source incorporation

### Decision
Flash chat UI will match Stream Chat Android's premium conversation appearance through a Flash-owned design system and clean-room Compose components. Flash will **not** copy Stream source code, vendor Stream modules, or depend on Stream Maven artifacts.

### Context
The reference repo at `E:\Flash-reference-repos\stream-chat-android` is publicly visible but licensed under Stream.io's proprietary **Stream License**, not Apache/MIT. That license requires a Stream customer relationship, forbids sublicensing or distributing Stream source, and explicitly prohibits using Stream software to develop products that compete with Stream Chat (Section 6). Flash is a P2P LAN/Wiâ€‘Fi Direct chat product and therefore falls under the competitive-use restriction.

### Alternatives considered
- Copy `stream-chat-android-compose` sources into Flash and adapt models â€” rejected (license + competitive-use).
- Add `io.getstream:stream-chat-android-compose` as a Gradle dependency â€” rejected (same license on published artifacts).
- Use Stream SDK with a Flash network adapter â€” rejected unless Stream grants a written competitive carve-out.
- Generic Material 3 dynamic theming â€” rejected (does not match Stream's fixed brand/chrome design system).
- Clean-room UI with side-by-side visual verification against the compose sample â€” selected.

### Why this was selected
- Keeps Flash legally independent while still targeting Stream-level visual polish.
- Separates presentation (`FlashChatTheme`, chat composables) from transport (`FlashChatRepository`, LAN protocol).
- Aligns with the project rule that the transfer/chat engine must not depend on third-party chat backends.

### Revisit when
- Stream provides explicit written permission for competitive incorporation, or
- Flash pivots to being a Stream customer app that uses Stream's hosted backend (unlikely for P2P goals).

## ADR-002 - Target SDK 36 for LAN MVP while local-network permission flow is unfinished

### Decision
Target SDK 36 for the current LAN MVP and remove `ACCESS_LOCAL_NETWORK` from the manifest.

### Context
Pixel 7 testing showed repeated `AppOps` errors for `ACCESS_LOCAL_NETWORK` and a system local-network device prompt while the app targeted SDK 37. Android documentation says `ACCESS_LOCAL_NETWORK` is required for target SDK 37+, while target SDK 36 and lower receive local-network access through `INTERNET` and should not declare the new permission.

### Alternatives considered
- Keep target SDK 37 and implement the runtime local-network permission immediately.
- Keep target SDK 37 and rely on the system-mediated local-network picker.
- Lower target SDK for the LAN MVP and revisit SDK 37 after the LAN path is stable.

### Why this was selected
- The current milestone is still validating LAN discovery and TCP reachability, not final platform permission UX.
- Removing the SDK 37 local-network permission path eliminates the Pixel 7 prompt/confusion during MVP testing.
- It keeps the app testable on current devices while preserving a documented revisit point.

### Revisit when
Before release or when bumping target SDK back to 37, implement and test the official local-network permission flow or system-mediated picker flow on Android 17+ devices.

## ADR-001 - Start LAN with NSD plus a TCP reachability probe

### Decision
Use Android NSD for LAN service advertisement/discovery and a small TCP probe before implementing full file transfer.

### Context
The project needs a reliable LAN baseline before Wi-Fi Direct. NSD proves that devices can find each other, while the TCP probe proves the advertised endpoint is connectable.

### Alternatives considered
- Implement full file transfer immediately.
- Add Wi-Fi Direct before LAN is stable.
- Use manual IP entry for the first milestone.

### Why this was selected
- It follows the project plan's LAN-first sequence.
- It creates a testable discovery and connection foundation without mixing in file I/O, TLS, pairing, or resume state too early.
- It keeps the transfer engine free from NSD-specific types by converting discoveries to `DiscoveredDevice`.

### Revisit when
After two physical devices repeatedly discover and probe each other on the same LAN, implement TLS handshake and one-file transfer.
## ADR-013 - Discovery mode wiring: interface default setMode, policy-scaled BOOST backoff, caps as informational TXT (P3.5-A/B)

### Date
2026-08-23

### Decision
1. FlashRadioTransport.setMode(DiscoveryModePolicy) added to the seam with a **no-op default body**; NsdTransport overrides it. CompositeDiscovery fans out blindly, so future radios (C3.6-C3.8) compile unchanged until they implement modes.
2. BOOST lowers the restart-backoff base by **scaling the injected delay provider** (provider(attempt) * policy.restartBackoffBaseMs / DEFAULT_BACKOFF_BASE_MS) rather than replacing it: injected test/production provider shape is preserved and the cap/maxAttempts stay untouched.
3. ECO duty cycle lives INSIDE the transport's own browse loop (scan burst -> stopBrowse -> idle -> repeat), knobs re-read per iteration; a conflated channel wakes an in-flight idle gap immediately on setMode. A maxDutyCycles constructor bound (default unbounded) exists purely for JVM-test determinism (module has no coroutines-test).
4. TXT caps is **informational only**: mDNS/DNS-SD is unauthenticated (RFC 6762), so advertised capability flags are never an access decision; enforcement is deferred to connect time (C3.10 seam). Inbound rule stays VERSION-only pre-directory.
5. GHOST advertise suppression returns Success(Unit) from startAdvertising as a documented no-op; the composite's isAdvertising consults the policy so state never claims visibility in GHOST.

### Context
Plan P3.5 workstream A (identity hardening) + B2/B3 (mode wiring); contracts FlashDiscoveryMode/DiscoveryModePolicy already existed.

### Consequences
- NsdTxtCodec encode now delegates to core TxtCodec (partial TODO(unify) closure; decode stays tolerant/local).
- statusMessage gains a [MODE] prefix (additive; suffix consumers unaffected).

### Revisit when
Multiple transports implement modes (fan-out semantics may need per-transport acks), or when pairing lands (fp8 becomes a pinning cross-check at C3.10, not just a hint).

