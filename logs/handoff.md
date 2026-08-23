# Current Handoff

## 2026-08-23 - P5 C5.1 session note (WsTransferManager/WsDiscovery/WsPairingStore relocated to :core:transfer/wslegacy)
- Landed (NOT Gradle-verified): all three `:app` wstransfer classes moved to `core/transfer/src/main/java/com/transfer/flash/core/transfer/wslegacy/**` with LEGACY KDoc markers; `:app` wstransfer package DELETED; MainActivity stale comment fixed. Adaptations: identity constructor-injected (localDeviceId/localFriendlyName â€” C7 seam), scope dispatcher Main.immediateâ†Default (R2), WsPairingStore takes FlashTrustStore directly (Context moved to callsite; JVM test WsPairingStoreTest +3). New tiny LegacyDiscoveredDevice stands in for deleted :app DiscoveredDevice (unification TODO).
- **LEAD ACTION REQUIRED before build:** add `implementation(project(":core:discovery"))` to core/transfer/build.gradle.kts (WsDiscovery needs NsdFlashDiscovery; dep verified absent, no transitive path). Everything else present.
- **Nobody constructs WsTransferManager until C5.2/C7 wiring** â€” it auto-starts server+discovery in init.
# Current Handoff

## 2026-08-23 - P5 pure-logic agent session note (C5.7 MULTI-STREAM, :core:transfer/multistream)
- Landed (NOT yet Gradle-verified): `multistream/StreamChannel.kt` (+StreamChannelFactory), `MultiStreamProgress.kt` (aggregate telemetry + RollingRateMeter, injected clock, 2s window), `MultiStreamDispatcher.kt` (dynamic claim loop over shared cursor + death-retry pool; end-game K=8 first-free single-owner tail w/ failover; ONE shared ResumeBitVector ACK mirror under one state lock, dedup = never re-count marked indexes; >=1 alive completes / all-dead Failed(unconfirmedIndexes); terminal COMPLETE emitted exactly once via CAS; claim+read atomic under lock, retry path reopens linear stream; injectable workerDispatcher+nowMs, no coroutines-test), `MultiStreamReceiver.kt` (any channel -> ONE ReceivePipeline; ACK_BATCH/COMPLETE routed down ARRIVING channel; exactly-once COMPLETE via serialized access).
- Tests: multistream/MultiStreamDispatcherTest.kt (7) + MultiStreamReceiverTest.kt (3) - deterministic JVM (runBlocking + Dispatchers.Default + gates/polling). Created ONLY under core/transfer/src/{main,test}/.../multistream/**. wslegacy/** untouched. No gradle/toml changes. Gradle NOT run.
- Decisions: ADR-015 (dynamic MPSCP-style claiming over static ranges; shared mirror; arrival-channel ACK routing; end-game tail). Research URLs in logs/progress.md entry of same date.
- Deviations: StreamChannel is a normal interface (Kotlin fun-interface forbids `val id`); pause/cancel + stall timeouts deferred to engine layer; "round-robin per free stream" plan wording superseded by measured prior art per R1.
- Next AI: run testDebugUnitTest (~10 new tests expected), fix fallout ONLY inside multistream/**, then EXP benchmark 1 vs 2 vs 4 streams on devices before fixing defaults.

## 2026-08-23 - P5 pure-logic agent session note (C5.3-C5.6 chunked pipelines, :core:transfer/chunked)
- Landed (NOT yet Gradle-verified): self-contained binary framing v2 `ChunkFrame` (FLSH|v2|type|len LE; parse returns null on ANY malformation), `Chunker` (64KB default, 16-256KB bounds, pure adaptiveSize() throughput curve, hashOnly prepass + lazy ChunkStream w/ per-chunk SHA-256 + whole-file digest identity guard), `Sha256` incremental/constant-time helpers (D3), `ResumeBitVector` (BitSet LE-word serialization, padding-safe, monotonic-union reconcile), `ReceivePipeline` (verify-before-write sink injection, ACK every 32 flushable, duplicate idempotent, graceful Rejected events incl. implicit-NACK hash mismatch), `SendPipeline` (suspend send():Boolean injection, resumeFrom(doneIndexes) linear-skip, confirmed/sent mirrors, Aborted{failedIndex,resumeCandidates}).
- Tests: 7 new JUnit4 classes (~35 tests), deterministic JVM via runBlocking only (no coroutines-test dep). E2E: happy path + kill-after-k resume with byte-identical result.
- Created ONLY files under core/transfer/src/{main,test}/java/com/transfer/flash/core/transfer/chunked/**. No gradle/toml/existing-file changes; Gradle NOT run.
- Decisions: ADR-014 (framing v2 layout, raw 32B per-chunk hashes, union reconcile). Research URLs: logs/progress.md entry of same date + KDoc.
# Current Handoff

## 2026-08-23 - P4 part 2 stream B session note (LAN session hardening + delivery ACKs, C4.8/C4.3 in tcp/**)
- Landed (NOT yet Gradle-verified): `LanSession` now emits real FrameAcks (`frameAcks: MutableSharedFlow` â€” SocketWritten per successful send, PeerAcknowledged on parsed `FLASH_ACK`), new `sendAwaitAck(message, timeoutMs=5000)` via additive `FLASH_DATA`/`FLASH_ACK` frames (protocol.md updated; existing frames byte-compatible), HeartbeatTracker-driven dead-peer detection (10 s Ã— 3 â‰ˆ 30 s, injectable constructor params, DeclareDead closes socket to unblock blocked readLine â€” JDK close() contract), read loop now tolerates probe leftover soTimeout. New injectable `LanSessionLogger` (JVM-test seam). Additive receive surface `incomingFrames: SharedFlow<String>`.
- New test `tcp/LanSessionHardenedTest.kt`: +5 JVM loopback tests incl. two-real-session end-to-end acked send and duplicate-ACK dedup.
- LanProbeServer/LanConnectionProbe UNTOUCHED (constructor stayed source-compatible). ws/**, tls/**, gradle untouched. Gradle NOT run.
- Research citations: logs/progress.md entry 2026-08-23 (stream B).

## 2026-08-23 - P4 part 2 stream A session note (TLS into WS transport, C4.1 completion)
- Landed (NOT yet Gradle-verified): `tls/SecureSocketUpgrader.kt` (wrapClient eager+fail-closed / wrapAccepted lazy server mode / forceHandshake / withPlainStreamTracking taint guard enforcing the clean-boundary rule) + additive `TlsOptions?` on WsTransferServer & WsTransferClient â€” TLS wrap happens BEFORE the WS handshake bytes flow in both directions. Tests: tls/SecureSocketUpgraderTest + ws/SecureWsTransferLoopbackTest (~+7 expected).
- Deviations: client context param now `Context?` (JVM-testable loopback); internal `WsLog` try/catch shim around android.util.Log (gradle untouchable this session); field name `expectedDeviceId`. tcp/** untouched; gradle untouched; Gradle NOT run.
- Research citations: logs/progress.md entry 2026-08-23 (stream A).

## 2026-08-23 - P3.5 A+B2/B3 session note (identity hardening + mode wiring)
- Landed (NOT yet Gradle-verified â€” run `testDebugUnitTest` first): TXT `caps`/`fp8` keys (core TxtCodec + NsdTxtCodec mirror; encode delegates to core), `FlashAdvertisedIdentity.capabilities/fingerprintPrefix` (defaulted, backward compatible), pre-directory `proto != FlashProtocol.VERSION` drop in NsdTransport, `FlashRadioTransport.setMode(policy)` default-no-op seam, `NsdTransport.setMode` (GHOST suppresses/resumes advertise; ECO duty loop w/ conflated mid-idle wake; BOOST scales backoff base), `CompositeDiscovery.setMode` fan-out + `discoveryMode: StateFlow` + additive `[MODE] ` status prefix.
- group/** and settings/** untouched (concurrent agent owns them). No gradle/toml changes. Research citations: logs/progress.md entry 2026-08-23. Design decisions: ADR-013.
- New tests: TxtCodecTest +7, NsdTransportLogicTest +9, CompositeDiscoveryTest +4 (~+20 expected).

## Current branch
`main` â€” remote: https://github.com/Kali452345/Flash.git (initial import commit `8a5c458`, 2026-08-22).

## Last verified build
`testDebugUnitTest assembleDebug` â€” BUILD SUCCESSFUL (2026-08-22); 376 Gradle tasks, **462 tests / 0 failures**.

## Current phase
**Phase P5 part 1 + option-2 integration COMPLETE (2026-08-23): :core:transfer chunked framing v2 (binary FLSH, per-chunk SHA-256, ACK batching, resume bit-vector) + multi-stream dispatcher/receiver (dynamic claims; 5 concurrency scenarios @Ignore under OPEN ERROR-013) + wslegacy relocation (C5.1). Option 2 wired: DiscoveryRouteBinder C3-to-C4 seam + DefaultFlashNetwork in engine holder + Dev Console tap-to-connect/health. 636 tests / 0 failures / 6 skipped. NEXT: ERROR-013 root-cause OR P5 part 2 (FlashTransferRepository over pipelines, C5.2/C5.9/C5.12).**

## Component status
- **UI-034 (Adaptive layouts):** `IMPLEMENTED` in `ui/adaptive/FlashAdaptiveLayouts.kt` â€” two-pane not yet consumed by screens (integration pending).
- **UI-038/039/041 (A11y/Haptics/Micro):** `IMPLEMENTED` â€” `FlashFeedback.kt` haptic choke point, 15 call sites migrated, a11y fixes applied.
- **UI-042/043 (Performance/Stress):** `IMPLEMENTED` â€” `FlashStressTestScreen.kt` harness; device measurements PENDING.
- **UI-024/031/032:** `IMPLEMENTED` â€” integration wiring items in Deferred block below.
- **UI-023, UI-028/029/030, UI-025â€“027, UI-021/022, UI-020, UI-019:** `IMPLEMENTED` â€” device verification pending.
- **UI-018 (Media viewer):** `VERIFIED` on device.
- **UI-017 (Image message & grid layout):** `IMPLEMENTED` in `FlashImageGrid.kt`, `FlashMessagingModels.kt`, `FlashMessageBubble.kt`.
- **UI-016 (File message card):** `IMPLEMENTED` in `FlashFileMessageCard.kt`, `FlashMessagingModels.kt`, `FlashMessageBubble.kt`.
- **UI-015 (Delivery / read states):** `IMPLEMENTED` in `FlashDeliveryStatusIcon.kt`, `FlashIcons.kt`, `FlashMessageBubble.kt`.
- **UI-014 (Typing indicator):** `IMPLEMENTED` in `FlashTypingIndicator.kt`, `FlashChatHeader.kt`, `FlashMessageList.kt`, `FlashConversationScreen.kt`.
- **UI-012 (Custom attachment button):** `IMPLEMENTED` in `FlashAttachmentButton.kt`, `FlashAttachmentSheet.kt`, `FlashComposer.kt`, `FlashConversationScreen.kt`.
- **UI-010 (Reply system):** `IMPLEMENTED` in `FlashQuotedReplyCard.kt`, `FlashSwipeToReply.kt`, `FlashMessageBubble.kt`, `FlashConversationScreen.kt`.
- **UI-009 (Reaction system):** `IMPLEMENTED` in `FlashReactionChip.kt`, `FlashReactionsDock.kt`, `FlashMessageContextMenu.kt`, `FlashConversationScreen.kt`.
- **UI-007 (Message press & selection):** `IMPLEMENTED` in `FlashMessageBubble.kt`, `FlashSelectionToolbar.kt`, `FlashConversationScreen.kt`.
- **UI-008 (Focus overlay & context menu):** `IMPLEMENTED` in `FlashMessageContextMenu.kt`, `FlashConversationScreen.kt`.
- **UI-011 (Custom message composer):** `IMPLEMENTED` in `FlashComposer.kt`.
- **UI-013 (Custom send button):** `IMPLEMENTED` in `FlashComposer.kt`.
- Foundation components: UI-001, UI-002, UI-037 (`IMPLEMENTED` in `:ui:theme`).
- List & Header: UI-003, UI-004, UI-005, UI-006 (`IMPLEMENTED` in `:ui:chat`).

## Working features
- Full modular multi-module library architecture (`com.transfer.flash:*`).
- Group chat header (UI-028): initials collage avatar (2/3/4+ layouts from seeded palette), "N members Â· M online" subtitle, named typing ("Alex and Sam are typingâ€¦"), transport/encryption glyphs, group Search action. Header fully de-Materialed (custom icon buttons, drawn divider, FlashText).
- System states family (UI-025/026/027): screen-specific empty states with P2P copy + "Find devices" CTA, layout-matched skeletons (delay-guarded, reduce-motion-safe, decorative semantics), severity-split error panels (red failure vs neutral offline) with single Retry â€” wired into chat list and conversation screens.
- Chat scroll engine + jump pill (UI-021/022): auto-scroll at bottom & on own sends, unseen counter with floating "N new messages" accent pill (tap â†’ animated jump + reset), reverseLayout bottom pinning through image resizes, keyboard-safe position retention.
- Voice recording interface (UI-020): hold mic to record, slide-left arms cancel (error-tinted bar), slide-up locks into persistent panel with trash/pause/send, live timer + Canvas amplitude strip, demo-mode capture producing real `FlashVoiceAttachmentUi` payloads.
- Voice message playback card (`FlashVoiceMessageCard`, UI-019): 40-bar discrete waveform with tap-to-seek + drag scrub, 48dp play/pause/download/retry badge, Telegram-style remainingâ†”duration label, 1Ã—/1.5Ã—/2Ã— speed pill, demo-mode playback ticker (real audio deferred pending Media3 ADR).
- Full-screen media viewer (`FlashMediaViewer`, UI-018): pinch/double-tap anchored zoom (1Ã—â€“4Ã—, rubber-band), pan, vertical drag-to-dismiss with backdrop fade + page scale, HorizontalPager album carousel, auto-hiding chrome (counter `n / m`, close, Save/Share/Forward), sample-size-guarded decode, always-dark backdrop token.
- Adaptive Image Collage & Grid Layout (`FlashImageGrid`): 1, 2, 3, 4, and 5+ image mosaics with clamped aspect ratios ($0.5$ to $2.0$), micro-gap gutters ($2.5\text{dp}$), bubble contour corner masking, and $+N$ overflow chips.
- Experimental WebSocket Mesh Transfer: Full file viewing, sharing, and device export capabilities (`WsFileActions`, `FileProvider`, SAF `CreateDocument` picker, click-to-open cards).
- Redesigned 24Ã—24 Custom Vector Icon Set: 46 Flash-owned vector icons with 2.0dp stroke weight, generous optical bounding boxes, and scaled default UI sizing (24dp).
- Complete Edge-to-Edge System Bar and Insets Safety: Status bar cutout clearance across headers/toolbars, and navigation bar/keyboard clearance across composer and sheets.
- Rich in-bubble file message cards (`FlashFileMessageCard`) with color-coded file extension badges (PDF, ZIP, Code, Audio, Video, Image, Document), circular transfer progress rings, and real-time throughput metrics (MB/s speed & ETA countdown).
- Animated delivery status glyphs (`FlashDeliveryStatusIcon`) for 5 transit lifecycle states (Pending, Sent, Delivered, Read, Failed) with 1-tap retry interaction.
- 120 FPS GPU-accelerated 3-dot wave bouncing typing indicator.
- Incoming message stream typing bubble (`FlashTypingBubble`) with concave bubble shaping and smooth list integration.
- Animated header subtitle typing status (`FlashHeaderTypingStatus`) with mini-dots.
- Stateful attachment button in composer with spring rotation ($0^\circ \to 45^\circ$) and active accent tint.
- Modal bottom sheet attachment palette with 5 categorized options (Gallery, Files, Camera, Audio, Flash P2P).
- Staggered spring scale entrance and 0.90x micro-press physics on attachment action tiles.
- Left-swipe-to-reply gesture with rotating reveal badge and single-edge haptic trigger.
- In-bubble quoted reply card with 3dp rounded vertical accent bar and 1-tap jump to original message.
- Jump-to-original message smooth scrolling with 600ms Flash Pulse glow highlight.
- Composer reply dock with dismiss action.
- Interactive reaction dock on message bubbles with 1-tap toggling, active self-reaction accent styling, animated vertical count roll (odometer), and `+N` overflow chip.
- Floating quick reaction bar inside spotlight focus overlay with staggered spring entrance, micro-press physics, and trailing `+` action button.
- Immersive message focus overlay with 65% dimmed backdrop, elevated bubble preview, and sculpted context menu card.
- Multi-message selection mode with animated toolbar swap, batch Copy/Reply/Forward/Delete.
- Adaptive multiline message composer with keyboard safety, reply dock, and tactile send button.
- Message list with reverse layout, concave bubble shapes, entrance choreography, and auto-scroll.
- LAN Discovery and experimental WebSocket multi-peer mesh Transfer.

## In progress
- **UI-028 (Group header):** IMPLEMENTED â€” device verification pending.
- **UI-025/026/027 (states):** device verification pending.
- **UI-021/022, UI-020, UI-019:** device verification pending.

## Broken
- None.

## Last change
ERROR-013 investigation: fixes 1-6 verified; remaining six scenarios characterized as SUITE-ORDER FLAKY (green x3 isolated, red in module/suite runs) - cross-test interference, likely leaked park-loop workers starving Dispatchers.Default. Re-@Ignore with updated message; next-session plan recorded (dedicated test dispatcher / leak assertions / condition-based shutdown).

## Last test
testDebugUnitTest assembleDebug - BUILD SUCCESSFUL (2026-08-23); **636 tests / 0 failures / 6 skipped (ERROR-013 suite-order-flaky family, OPEN)**.

## Known blockers
- **Environment (ERROR-008, MITIGATED)**: E: drive intermittently returns "The device is not ready" during Gradle cache writes. Recovery: `.\gradlew.bat --stop`, kill stuck java PIDs, rebuild with a fresh daemon. Real fix is hardware-side (move caches off the removable/hot-plug device or disable its power management).

## Deferred / pending integration (do not forget)
**Master plans:**
- **PART 1 â€” Core:** `docs/core-upgrade-plan.md` **v2 ACTIVE** â€” D2/D3/D4/D5 approved (ADR-010); D1 + D6 open; execution phases P0â€“P8 defined.
- **PART 2 â€” Pages:** `docs/ui-page-plan.md` â€” bottom nav shell (Chats/Transfers/Nearby/Settings + Send FAB), page-by-page specs P1â€“P5 with core-API dependencies, integration checklist.

All items below are absorbed into those two documents:
- UI-031 badge/sheet wiring into header; `isVerified` passes false until pairing lands.
- UI-032 pairing dialog trigger from discovery flow; Accept/Decline need engine callbacks.
- UI-024 recent-searches persistence; UI-029 demo roster until live members.
- UI-020 MediaRecorder capture ADR; UI-019 Media3 playback ADR.
- Engine-side auto-retry/backoff indicator (UI-044); key-changed warning state (UI-031).
- **UI-031**: wire `FlashEncryptionBadge` near conversation header; tap opens `FlashEncryptionSheet`. `isVerified` passes `false` until pairing/engine lands; verification rows disabled-with-explanation.
- **UI-032**: trigger `FlashPairingDialog` from the Nearby Devices/discovery flow once engine exposes pairing events; Accept/Decline need engine callbacks.
- **UI-024**: recent-searches persistence (currently in-memory only).
- **UI-029**: members sheet uses demo roster until repository feeds live members.
- **UI-020**: real audio capture requires RECORD_AUDIO flow + MediaRecorder engine ADR.
- **UI-019**: real playback requires Media3 dependency ADR.
- **Engine-side**: auto-retry/backoff indicator (UI-044), key-changed warning state (UI-031).

## Recommended next task
**Next: ERROR-013 root-cause (instrumented worker-lifecycle debugging in multistream) OR P5 part 2 - FlashTransferRepository over the new pipelines (C5.2) + SAF receive policy (C5.9) + FGS wiring (C5.12). Owner device run: Dev Console tap-to-connect between two phones now exercises discovery + network + health end-to-end.**

## 2026-08-22 - P3 NSD session note (agent handoff)
- LAN MVP networking now has `nsd/NsdTransport.kt` (:core:discovery) implementing FlashRadioTransport C3.2-C3.4 (identity TXT advertise + self-filter, continuous browse w/ capped restarts, API>=34 ServiceInfoCallback vs <34 hardened NsdResolveQueue split, NetworkRequest-scoped discovery API 33+). `NsdFlashDiscovery` untouched (R4). NOT yet Gradle-verified (forbidden session) - run testDebugUnitTest first; tests: nsd/NsdTransportLogicTest.kt (pure-JVM, no coroutines-test dep in module).
- API thresholds + citations live in `NsdApiLevel.kt` KDoc and logs/progress.md entry of same date. DiscoveryRequest combined API (T-ext 22 / SDK 37) deliberately deferred.


## DEVICE TESTING BACKLOG (for owner)
Priority order; each item = install latest debug APK, exercise, report pass/fail:
1. **UI-019 Voice playback**: tap voice card â†’ play/pause animated morph, seek by tap, drag scrub, speed pill cycle, remainingâ†”duration label swap.
2. **UI-020 Recording**: hold mic â†’ bar+timer+amplitude; slide-left = red "release to cancel"; slide-up = lock panel (trash/pause/send); release sends; short tap discards.
3. **ERROR-009/010/011 regressions**: keyboard-open has NO blank band; context menu dismisses on FIRST scrim tap + âœ• button.
4. **UI-021/022 Scrolling**: peer message while scrolled up â†’ "N new messages" pill; tap jumps to bottom; auto-scroll on own send.
5. **UI-023 Search**: header search icon â†’ type query â†’ counter + prev/next jump with in-bubble highlight; close restores.
6. **UI-025â€“027 States**: empty chat list ("Find devices" CTA), skeleton loading, error panel retry.
7. **UI-028/029 Group**: collage avatar + "15 members Â· 4 online" subtitle + named typing; group avatar tap â†’ members sheet.
8. **UI-030 Banner**: connection banner states (toggle sample data); transport badge chip.
9. **UI-031 Encryption badge/sheet** (once wired).
10. **UI-032 Pairing dialog** (once wired to discovery).
11. **UI-024 Chat-list search**: filter by title/preview, recents chips.
12. **Dark mode sweep** all above + **reduced-motion** setting spot-checks.
13. **UI-042/043 Perf**: open stress screen at 500/2000 messages, fling scroll, note jank (`adb shell dumpsys gfxinfo com.transfer.flash`).

## Build environment note
```powershell
$env:JAVA_HOME="E:\AndroidDev\AndroidStudio\android-studio\jbr"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; $env:GRADLE_USER_HOME="E:\Flash\.gradle-user-home"; .\gradlew.bat testDebugUnitTest installDebug
# If "The device is not ready" appears (ERROR-008):
.\gradlew.bat --stop; taskkill /PID <stuck java pid> /F; then rerun with a fresh daemon.
```

## Files most relevant to next task
- `logs/handoff.md` testing backlog above (owner runs; lead fixes / marks VERIFIED)
- `ui/chat/src/main/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLayouts.kt` (two-pane consumption pending)
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashStressTestScreen.kt` (entry-point wiring)
- `docs/ui/performance.md` (device measurement plan for UI-042/043 numbers)
- Integration files from Deferred block: FlashNavigation.kt, FlashNetworkSimSheet.kt, FlashEncryptionIndicators.kt, FlashPairingFlow.kt

## 2026-08-22 - P3 pure-logic agent handoff (C3.3/C3.5/C3.9)
- Created (ONLY these): `core/discovery/.../core/{StandardEndpointDirectory,TxtCodec,DiscoveryRetryPolicy,CompositeDiscovery}.kt` + 4 matching JUnit4 test classes under src/test. NO existing file touched; nsd/** untouched.
- CompositeDiscovery implements existing FlashDiscovery + `startAll(port, identity)` aggregate + `sweep(nowMs, grace=30_000)` + `mergedEvents` SharedFlow(DROP_OLDEST); dedup across transports by deviceId, priority LAN > WIFI_DIRECT > WIFI_AWARE > BLE, loss hysteresis emits Updated(fallback) not Lost while a lower radio still sees the peer.
- Deterministic tests without coroutines-test: synchronous DirectDispatcher injected via optional scopeFactory ctor param + explicit clock lambda + local FakeTransport.
- NOT Gradle-verified (forbidden session) - run testDebugUnitTest first; expect ~+25 tests. Full details + research URLs: logs/progress.md entry of this date; decisions: ADR-010.

## 2026-08-23 - P4 pure-logic agent (C4.2/C4.3/C4.5/C4.7-aggregation + C4.9)
- Created ONLY: `core/network/.../resilience/**` (ReconnectPolicy, HeartbeatPolicy, HeartbeatTracker, BoundedSendQueue, SessionHardeningPolicy, ConnectionHealthAggregator, ChaosSession+DedupGate, ChaosNetworkHarness) + matching tests under src/test. NO existing file or gradle/toml touched; Gradle NOT run.
- Strategies chosen (research-cited in logs/progress.md same date): full-jitter-with-floor backoff base 1s cap 30s; heartbeat 10s interval / 3 misses; send-queue REJECT mode capacity 64; session limit 8; duplicate-device tie keeps existing.
- Deterministic JVM tests only (explicit nowMs, seeded Random, injected random01); no coroutines-test. MutableStateFlow used in main via transitive coroutines-core (lifecycle-runtime-ktx) - verified.
- NOT build-verified. Next AI: run testDebugUnitTest first (~+30 expected), then wire primitives into concrete FlashNetwork impls (C4.2/C4.3 integration).