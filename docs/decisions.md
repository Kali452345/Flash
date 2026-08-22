# Decisions

## ADR-011 - D1 = Hilt; D6 = opt-in subtle sounds (default off); Phase P0 executed

### Decision
Owner approved (2026-08-22):
- **D1:** Hilt (2.60.1, KSP 2.3.11) as the DI framework. Graph lives in `:app` (`:core:*` modules stay DI-agnostic, constructor-injected), per plan C0.5.
- **D6:** Sound feedback = subtle synthesized procedural tones, **opt-in with default OFF** (settings toggle persists via DataStore in C1.5). Unblocks UI-040.
- Phase P0 executed same session: `FlashProtocol`/`FlashEnvelope`, `FlashLogger` ring buffer, `FlashTimeSource`/`FlashIdGenerator` (:core:common), Hilt graph skeleton + `FlashApplication`, GitHub Actions CI (C0.6), UI-040 sound system (`FlashSounds`) implemented in :ui:theme.

### Context
Hilt chosen over Koin (compile-time safety, standard tooling) and manual DI (brittle at scale); verified compatible with AGP 9.3.1/Kotlin 2.2.10 via research (Dagger ≥2.59 requires AGP ≥9 — satisfied). Sounds chosen opt-in/off to match reduce-motion philosophy (motion/a11y-first app) until owner opts in.

### Alternatives considered
Koin (runtime-only error detection), manual AppContainer (fine now, brittle later); asset-based sounds (ships binaries for what synthesis covers), default-on tones (rejected by a11y philosophy).

### Revisit when
Capability-flag version negotiation if a second protocol consumer appears (Windows/Linux client); sound call-site wiring when real messaging engine lands (C6).

## ADR-010 - Core upgrade decisions D2/D3/D4/D5 approved; plan v2 adopted

### Decision
Owner approved (2026-08-22) during the core-plan iteration session:
- **D2:** SQLCipher full-database at-rest encryption, key wrapped in AndroidKeyStore.
- **D3:** SHA-256 (java.security, zero deps) for chunk/message hashes and fingerprints.
- **D4:** Frame-level E2E implemented in C2 — ECDH P-256 → HKDF → AES-GCM per paired peer, layered on TLS.
- **D5:** Mesh relay is post-v1; v1 = direct P2P only (hop-count seams reserved).
- Plan `docs/core-upgrade-plan.md` rewritten to **v2**: fine-grained research-first steps, UI-dependency inventory, continuous identity-aware discovery, resilient network upgrades, multi-stream transfer, exhaustive messaging API surface.

### Context
UI roadmap complete on sample data; the finished screens define exact required inputs (`isVerified`, presence, typing names, transfer telemetry, pairing events). Core must be a reusable library (no app/UI deps) and every implementation step must begin with cited web research.

### Alternatives considered
Keystore-wrapped field encryption only (rejected — weaker than owner-approved full-database option); BLAKE3 (deferred — zero-dep SHA-256 sufficient until benchmarks say otherwise); mesh relay in v1 (rejected — scope).

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


## ADR-008 — Modular Multi-Library Architecture & Standalone Component Hosting

### Decision
Transition the Flash codebase from a single `:app` monolithic module into a suite of decoupled, standalone Android/Kotlin library modules (`:core:common`, `:core:discovery`, `:core:network`, `:core:transfer`, `:ui:theme`, `:ui:chat`, `:ui:transfer`) with `:app` serving as the runnable showcase application. Each library module will be independently buildable, testable, and publishable to Maven repositories via the Gradle `maven-publish` plugin under the group `com.transfer.flash`.

### Context
The owner requested that Flash's components be usable individually by third-party developers:
- Core networking & transfer libraries (headless discovery, socket sessions, WebSocket mesh, SAF file streaming) can be consumed without any Jetpack Compose or UI dependencies.
- UI components (Flash Pulse design system tokens, custom icons, message bubbles, chat list, composer) can be consumed independently with pluggable backend repositories.

### Alternatives considered
- Single-module architecture with package-level separation — rejected (cannot publish individual artifacts; risks accidental coupling between UI and low-level networking).
- Monolithic single SDK library (`flash-sdk`) — rejected (forces UI consumers to pull in networking/sockets, and forces headless users to pull in Compose runtime).
- Fine-grained multi-module library suite (`:core:*`, `:ui:*`, `:app`) — selected.

### Why this was selected
- **Independent Consumption**: Developers can pull `com.transfer.flash:core-transfer` for headless file transfer or `com.transfer.flash:ui-chat` for custom messaging UI.
- **Strict Layer Isolation**: Compile-time enforcement prevents UI components from referencing socket connections directly.
- **Hosting & Publishing Ready**: Standardized `maven-publish` configuration across all library modules enables automated releases to MavenCentral, JitPack, or GitHub Packages.
- **Scalability**: Allows future multiplatform targets (e.g. Kotlin Multiplatform / Desktop / CLI) for `:core:common` and `:core:network`.

### Revisit when
When preparing the first public Maven release or when extracting pure JVM/KMP modules for non-Android targets (Desktop/CLI).

---

## ADR-007 — Experimental WebSocket transfer side track (hand-rolled RFC 6455, multi-peer)

### Decision
An experimental WebSocket-based transfer path lives in `wstransfer/` (`WebSocketCodec`, `WsConnection`, `WsTransferServer`, `WsTransferClient`, `WsTransferManager`) plus `ui/transfer/WsTransferScreen.kt`. It is a **side track at the owner's request** and does NOT replace the main LAN/TCP+TLS protocol plan. The RFC 6455 codec (upgrade handshake, masking, frame parse/serialize, fragmentation reassembly) is hand-rolled in pure Kotlin; no new Gradle dependency was added.

### Context
The owner asked for WebSocket-based transfer that lets 3+ devices pair and connect to each other with simple file transfer, explicitly "not part of our main design". OkHttp 4.12.0 exists in the Gradle cache only as a leftover of the reverted Stream SDK experiment, and OkHttp's WebSocket is client-only — every Flash device must be both server and client, so OkHttp alone could not satisfy the requirement.

### Alternatives considered
- OkHttp WebSocket client + separate WS server library — rejected (two dependencies, client/server split, and OkHttp has no server).
- `org.java_websocket` (TooTallNate) — rejected for now (new dependency; keep zero-dep until this track proves useful).
- Extend line-based `LanSession` — rejected (owner explicitly requested WebSocket framing).

### Why this was selected
- Zero new dependencies (AGENTS.md dependency rule); codec is pure JVM and unit-tested (RFC 6455 reference accept-key vector, masked/unmasked round trips, 16/64-bit lengths, fragmentation, close/ping).
- Server (port 45822 preferred) + client on every device → any device can pair with any number of peers; peers keyed by device ID with outbound-preferred primary connection and inbound fallback, so a 3-device full mesh works.
- File bytes ride ordered binary frames between `FLASH_FILE_START` / `FLASH_FILE_END` text frames; receiver writes to `filesDir/ws-received/` and answers `FLASH_FILE_ACK` with byte-count verification.

### Revisit when
If this track graduates to the main design: add TLS (wss://), real pairing/trust UX, resume, hash verification, and reconcile with ADR-001's TCP session path. Also revisit the hand-rolled codec if extension support (compression) is ever needed.

---

## ADR-006 — Custom `FlashBubbleShape` concave tail geometry (UI-005)

### Decision
Message bubbles use a Flash-owned `Shape` (`FlashBubbleShape` in `ui/theme/FlashShapes.kt`) producing `Outline.Generic(Path)`: three circular corners plus one concave cubic-Bézier "pulse scoop" (8dp) on the sender-facing bottom corner, mirrored in RTL via `LayoutDirection`. Grouped messages (`TOP`/`MIDDLE`) are fully rounded 20dp. No third-party bubble library.

### Context
The master plan forbids generic `RoundedCornerShape` rectangles as final bubbles, and the provisional zero-radius-corner tail read as a broken rectangle. UI-005 required real grouped geometry with a distinct silhouette.

### Alternatives considered
- Zero-radius corner tail (provisional) — rejected (accidental look).
- SmartToolFactory/Compose-Bubble library — rejected (dependency for one path, canvas-shadow style conflicts with Flash no-shadow policy).
- `graphics-shapes` morphing — rejected for now; revisit only if UI-006 research justifies it.

### Why this was selected
Zero new dependencies; clips/borders follow the path; RTL-correct; one path per measure; gives Flash a silhouette detail not used by reference apps.

### Revisit when
UI-006 insertion animation research or UI-045 quality gate suggests morphing shapes.

---

## ADR-005 — Flash Pulse visual identity (UI-001)

### Decision
Flash premium chat UI uses the **Flash Pulse** design system: teal pulse accent (`#0D9488` light / `#1FB8A6` dark), graphite neutrals, spark amber for transfer/status only, layered dark surfaces (void + surface0–3). Tokens live in `ui/theme/`. Chat UI must not use `MaterialTheme.colorScheme` for visible styling.

### Context
UI-001 research compared Material You-as-primary, Stream-look scaffold (ADR-003 era), and an original palette. Owner requires distinct identity per ADR-004.

### Alternatives considered
- Material dynamic color as primary — rejected (brand loss).
- Retain Stream-look `#005FFF` blue — rejected (clone risk, wrong P2P story).
- Flash Pulse teal + graphite — selected.

### Why this was selected
Distinct from major messaging apps; supports local/P2P semantics; documented light + dark palettes; optional `dynamicAccent` tints accent only (UI-036 foundation).

### Revisit when
UI-045 quality gate or owner requests rebrand; UI-036 adds user-facing dynamic accent toggle.

---

## ADR-004 — Premium chat UI: research-first, custom Flash design system

### Decision
Flash premium messaging UI will be built component-by-component using a **research-first** workflow documented in `docs/ui/`. Material 3 is infrastructure only; the visible chat experience must use Flash-owned design, motion, icons, and interactions. No proprietary chat SDK/source (Stream etc.).

### Context
The product requires Telegram/Signal/WhatsApp-level polish with Flash's own visual identity and P2P-aware UX. Prior exploratory conversation UI exists but is provisional and must not bypass per-component research.

### Alternatives considered
- Continue Stream-look clean-room scaffold as final UI — rejected (does not meet originality/premium component bar).
- Copy Telegram/Stream visuals — rejected (legal and product identity).
- Single-pass Material 3 chat screen — rejected (generic, not premium).
- Research-first custom system with documented UI-001–UI-045 sequence — selected.

### Why this was selected
- Matches owner requirement for documented research per component.
- Keeps networking independent of UI.
- Enables continuity across AI sessions via `docs/ui/` and AGENTS.md §34.

### Revisit when
UI-045 quality gate passes and owner accepts premium chat UI for release; or if a licensed third-party UI kit is explicitly approved in writing.

## ADR-003 — Clean-room Stream visual parity; no Stream SDK or source incorporation

### Decision
Flash chat UI will match Stream Chat Android's premium conversation appearance through a Flash-owned design system and clean-room Compose components. Flash will **not** copy Stream source code, vendor Stream modules, or depend on Stream Maven artifacts.

### Context
The reference repo at `E:\Flash-reference-repos\stream-chat-android` is publicly visible but licensed under Stream.io's proprietary **Stream License**, not Apache/MIT. That license requires a Stream customer relationship, forbids sublicensing or distributing Stream source, and explicitly prohibits using Stream software to develop products that compete with Stream Chat (Section 6). Flash is a P2P LAN/Wi‑Fi Direct chat product and therefore falls under the competitive-use restriction.

### Alternatives considered
- Copy `stream-chat-android-compose` sources into Flash and adapt models — rejected (license + competitive-use).
- Add `io.getstream:stream-chat-android-compose` as a Gradle dependency — rejected (same license on published artifacts).
- Use Stream SDK with a Flash network adapter — rejected unless Stream grants a written competitive carve-out.
- Generic Material 3 dynamic theming — rejected (does not match Stream's fixed brand/chrome design system).
- Clean-room UI with side-by-side visual verification against the compose sample — selected.

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
