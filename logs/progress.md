# Progress Log
# Progress Log

## 2026-08-23 — Stale-peer fix + Phase P4 part 1 (:core:network TLS + resilience logic)

### Worked on
Fixed the field-reported discovery bug (peers stayed visible after stop/Wi-Fi-off), then executed P4 steps C4.1 + C4.2/C4.3/C4.5/C4.7-aggregation + C4.9 via two parallel research-first subagents plus lead contracts/integration.

### Changed
- **Stale-peer fix (`2c1909d`):** CompositeDiscovery now owns an internal sweeper loop (5 s interval, started by startAll, cancelled by stopAll) so departed peers converge to Lost within ~grace+interval even when mDNS goodbyes are missed; injectable delayFn + maxSweepLoops determinism hooks; regression test drives virtual time through AtomicLong clock.
- **C4 contracts (lead):** `FlashConnectionHealth` enum; additive `FlashNetwork.connectionHealth`/`retryConnection()`; additive `FlashSession.frameAcks: Flow<FrameAck>` (SocketWritten/PeerAcknowledged stages feeding UI-015).
- **C4.1 TLS (agent):** `tls/` package — `FlashPinVerifier` seam (Room store wired later), `TofuX509TrustManager` (X509ExtendedTrustManager, SHA-256 SPKI pinning, fail-closed incl. missing device id, uniform onKeyChanged reporting), `FlashTlsContextFactory` (client/server contexts w/ KeyManager injection + onKeyChanged threading, TLSv1.3-preferred config), hostname-verification-replacement rationale documented.
- **C4.2/C4.3/C4.5/C4.7/C4.9 resilience (agent):** `resilience/` package — full-jitter-with-floor `ReconnectPolicy` (AWS blog research), 10s×3-miss `HeartbeatTracker`, reject-newest `BoundedSendQueue` (capacity 64; outbox retains rejected writes per C6 contract), `SessionHardeningPolicy` (8-session cap, transport-rank coalescing), `ConnectionHealthAggregator`, `ChaosSession`+`ChaosNetworkHarness` fault-injection with resilience invariant tests (dedup gate, queue-full storm under watchdog, dead-peer declaration, reconnect reset).
- **Lead additions:** `AndroidNetworkWatcher` (NetworkCallback instant-reconnect trigger, LAN transports only); test-only BouncyCastle bcpkix dependency for JVM cert generation (documented decision: zero production footprint — prod certs come from platform keystore).

### Verification
- Consolidated build: **561 tests / 0 failures** (+65: localhost TLS handshakes matching/wrong/missing pin, TLS 1.3 negotiation, chaos invariants, policy tables).
- Lead integration fixes (7): javax.net.ssl.KeyManager import; BC test route after hand-rolled DER encoder produced malformed certs ("Too short"); TestIdentity API compat + REAL PKCS12-backed KeyManagers (empty managers broke server-side handshakes); CN double-prefix normalization; factory now threads onKeyChanged (was silently dropped → empty key-change events); trust-manager test restructured to expect the fail-closed exception before asserting callback; chaos storm assertion corrected to reject-all-500.
- Multiple ERROR-008 E:-drive incidents again (AsyncCacheAccessDecoratedCache write failures killing daemons/config cache); recovered via --stop + fresh no-daemon runs each time.

### Remaining (P4 part 2)
- Wire TLS factories + resilience components into REAL sessions (LanSession/WsConnection/LanConnectionProbe): C4.5 session-manager integration, C4.6 Aware/Direct endpoint acceptance seams, C4.8 real ack emission, C4.4 foreground-lifecycle binding at :app layer.
- Device verification of TLS on physical phones.

### Next AI
P4 part 2 integration, or owner runs Dev Console two-phone battery first. R1 research-first every step.

## 2026-08-22 - Phase P3 partial: NSD continuous transport (C3.2-C3.4)

### Worked on
Implemented `NsdTransport : FlashRadioTransport` (identity-aware advertising, continuous browsing with capped auto-restart, API-level-split resolution) plus `NsdApiLevel` threshold isolation and JVM tests, per plan C3.0-C3.4 and R1 research-first rule.

### Research findings (R1, cited in code KDoc)
- (a) `registerServiceInfoCallback(NsdServiceInfo, Executor, ServiceInfoCallback)` = **API 34** (T-ext 7); legacy `resolveService` **deprecated API 34**; on <34 must keep ResolveListener path: https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager + https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager.ServiceInfoCallback
- (b) `discoverServices(String, Int, NetworkRequest, Executor, DiscoveryListener)` added **API 33 (not 34)**; tracks network changes automatically -> proper Found/Lost across Wi-Fi drops/rejoins; requires ACCESS_NETWORK_STATE: https://developer.android.com/sdk/api_diff/33/changes/android.net.nsd.NsdManager ; fallback = legacy PROTOCOL_DNS_SD call.
- (c) DiscoveryRequest combined discover+monitor (`registerServiceInfoCallback(DiscoveryRequest, ...)`) added **API 37 SDK level**, but docs state runtime availability from **"T extensions 22"** covering all Android 14+ (gate = `SdkExtensions.getExtensionVersion(T) >= 22`). DECISION: not adopted now (extension-version gating complexity, no need yet); noted as future step alongside API 37 ACCESS_LOCAL_NETWORK picker flows: https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager
- (d) Multicast lock required before T-extensions 7; from T-ext 7 system manages foreground multicast reception and background apps should avoid the lock. Conservative approximation used: skip lock when sdkInt >= 34 (all Android 14+ have T-ext >= 7); acquire otherwise (safe direction): https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock + NsdManager "Wi-Fi Multicast Lock" doc section.
- (e) `NsdServiceInfo.getNetwork()/setNetwork()` both **API 33** (T-ext 3); setNetwork(null)=all networks: https://developer.android.com/reference/kotlin/android/net/nsd/NsdServiceInfo

### Changed (files created ONLY; zero modifications to existing files/gradle)
- `core/discovery/src/main/java/com/transfer/flash/core/discovery/nsd/NsdTransport.kt` - `NsdManagerBridge` seam + neutral callback models (AdvertiseRequest/BrowseRequest/MonitorRequest/ResolvedServiceData), `RealNsdManagerBridge` (real NsdManager + WifiManager multicast lock + NetworkRequest(TRANSPORT_WIFI|ETHERNET) discovery w/ legacy fallback + owns EXISTING hardened NsdResolveQueue for <34 path -> ERROR-006 protections preserved untouched), pure `NsdTxtCodec` ({device_id,name,model,proto} key set mirroring core.TxtCodec for future unification), pure `NsdRestartPolicy` (capped exponential backoff 1s..30s), and `NsdTransport` itself (TXT advertise + identity self-filter C3.2; browse-until-stop loop w/ retry-on-failure C3.3; >=34 registerServiceInfoCallback vs <34 resolve-queue split + NetworkRequest-scoped discovery C3.4; directory diff -> Found/Updated/Lost event mapping incl. serviceName reverse lookup; `pollSweep()` hook for engine sweeper C3.5).
- `core/discovery/src/main/java/com/transfer/flash/core/discovery/nsd/NsdApiLevel.kt` - `interface NsdApiLevel { val sdkInt }` + `BuildNsdApiLevel` + `NsdApiThresholds` constants documenting all researched levels (34 service-info-callback / 33 network-request discovery / 33 network field / 34 multicast-lock-not-needed).
- `core/discovery/src/test/java/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt` - 13 JVM tests: TXT encode/decode fallbacks, restart-policy give-up math, TXT advertisement content, self-filter by deviceId BEFORE directory, Found-then-Updated mapping through directory diffs, drop-without-device_id/host, radio-loss -> single typed Lost w/ serviceName, legacy-vs-API34 branch selection recorded via fake bridge calls, capped re-browse attempts (3 requests @ budget 2 + runtime onStartFailed restart), multicast lock only below threshold, stop() releases everything.

### Verification
- NOT run (Gradle forbidden this session). Written against verified deps (:core:common, junit present; kotlinx-coroutines-test NOT present in core/discovery/build.gradle.kts - see deviations). Existing files/tests untouched (R4).

### Deviations
1. **kotlinx-coroutines-test unavailable**: module build.gradle.kts has only junit as testImplementation and gradle is read-only -> tests use injected no-op `sleep` + Dispatchers.Unconfined (launches execute inline; deterministic without virtual time). Retry delays asserted via recorded provider outputs instead of advanceTimeBy.
2. **Internal scope ownership** (pre-approved deviation): NsdTransport lazily creates CoroutineScope(SupervisorJob()+dispatcher), cancels in stop(); rationale documented in class KDoc (radio lifecycle == scope lifetime; post-stop callbacks would violate Lost contract).
3. **Context parameter nullable** (`context: Context?`) so JVM tests can construct with bridgeOverride=null-context combo; init requires one of context/bridge.
4. **Radio loss emits exactly one Lost** (with known serviceName) while still calling directory.applyLost - avoids duplicate events from Diff.Lost mapping.
5. **NsdTxtCodec duplicates core.TxtCodec key set deliberately** (compile-independence from concurrent agent); TODO(unify) noted.
6. **lane dispatcher falls back to raw dispatcher** when limitedParallelism unsupported (Unconfined throws USOE - verified against kotlinx.coroutines source); production IO gets real parallelism-1 view.
7. **Logging injectable** (logInfo/logWarn defaults to android.util.Log) because module lacks unitTests.returnDefaultValues; JVM tests would crash on Log stubs otherwise.

### Remaining
- Consolidated Gradle run (testDebugUnitTest) by owner/next session.
- Engine wiring: periodic sweep caller, EndpointDirectory impl (concurrent agent), CompositeDiscovery (C3.9).
- C3.11 device battery (see below).

### Next AI
1) Run testDebugUnitTest; fix reds + log ERROR-0XX. 2) Wire StandardEndpointDirectory + sweeper into NsdTransport.pollSweep(). 3) Device battery: cold join, hot leave, Wi-Fi toggle, AP roam timings -> logs/experiments.md; verify multicast-lock behavior on Android 13 non-T-ext7 device specifically.

## 2026-08-22 — Phase P2 Executed (:core:security full stack)

### Worked on
Executed core plan Phase P2 (C2.0–C2.8) via two parallel research-first subagents with strict file ownership (crypto/ vs trust+pairing/); lead wired the :core:persistence dependency into :core:security, ran consolidated builds, fixed seven integration issues.

### Changed
- **C2.0 research:** AndroidKeyStore ECDSA sign since API 23/StrongBox API 28+; PURPOSE_AGREE_KEY only since API 31 → design decision: identity = Keystore ECDSA P-256, session keys = ephemeral software ECDH P-256 (memory-only); self-signed cert via platform KeyGenParameterSpec certificate fields instead of BouncyCastle (multi-MB dep rejected); HKDF per RFC 5869; AES-GCM random-96-bit-nonce discipline per NIST SP 800-38D.
- **crypto/ (agent A):** `FlashCrypto` interface, `KeystoreFlashCrypto` (alias flash_identity, StrongBox→TEE fallback, platform self-signed cert retrieval), `SoftwareFlashCrypto` (JVM tests/fallback, loud NOT-FOR-PRODUCTION), `Hkdf` (RFC 5869 test cases 1–2 as vectors), `FlashFingerprint` (SHA-256 D3 + hex-group formatting + constant-time equals), `E2eFrameCodec` ([12B nonce|ct+tag], AAD=protocol version, AES-256-GCM).
- **trust/pairing/ (agent B):** `RoomTrustedStore` (additive FlashTrustStore impl + pin/isPinned/trustedPeers Flow + idempotent legacy import w/ LEGACY_UNBOUND_FINGERPRINT so old flags never silently become pins), pure `TofuPolicy` (FirstConnect/Match/Mismatch, fail-closed incl. missing presented fingerprint), `FlashPairingFrames`, symmetric `NumericComparisonCode` (sorted-concat SHA-256, BT-SSP numeric-comparison precedent), pure `PairingSessionStateMachine` (8 phases, engine-owned timeouts, Expired≠Failed, mapped to UI-032 demo phases), `DefaultFlashPairingProtocol` orchestrator (events flow, onFrame/onTick seams for C4/C6).
- **docs/security.md created:** threat model, identity/pairing/E2E policy, nonce discipline, rekey deferral to D5 mesh workstream, known gaps.
- **Lead integration fixes (7):** missing KeyPairGenerator import; generateKeyPair name collision inside .run block; kotlinx Flow.map vs FlashResult.map overload collision in RoomTrustedStore → try/catch rewrite; TofuPolicy nullable-arg type mismatch; PeerDeclined reducer violating its own total-reducer principle (only meaningful in AwaitingPeerConfirmation); replay=0 SharedFlow needed testScheduler.runCurrent() pumping in 3 tests; PAIR_CONFIRM fed to wrong party in handshake test.

### Verification
- Consolidated `testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL, 413 tests / 0 failures** (+73: RFC vectors, ECDH bidirectional agreement, tamper detection, numeric-code symmetry/determinism, state-machine transition matrix incl. expiry boundaries, two-party cross-wired handshake, TOFU decisions incl. blank-presented fail-closed, migration idempotency).
- Recurrent Kotlin-daemon crashes from E:-drive I/O drops (ERROR-008) — recovered each run. **Incident note:** one PowerShell Get-Content/Set-Content pass corrupted handoff.md UTF-8 (mojibake); restored from git commit and redid edits via UTF-8-safe tools. Lesson recorded: never round-trip repo text files through PS 5.1 Get-/Set-Content.
- Runtime Keystore/E2E verification pending (device backlog item added).

### Remaining
- Phase P3 next (discovery continuous mode C3.1–C3.5 + device battery C3.11).
- Wire pairing protocol to transport when C4 lands; decline frame encoding C4/C6.

### Next AI
Start P3 per plan §5. R1 research-first every step. Beware ERROR-008; commit incrementally.

## 2026-08-22 — Phase P1 Executed (:core:persistence — Room + SQLCipher + DataStore)

### Worked on
Executed core plan Phase P1 (C1.0–C1.8) via two parallel research-first subagents with strict file ownership; lead scaffolded module/build config, ran one consolidated build, fixed two integration issues.

### Changed
- **C1.0 research (lead):** Room 3.0 went stable 2026-07 (new `androidx.room3` package, SQLiteDriver-based, breaks SupportSQLite); SQLCipher added Room 3 support only in 4.18.0 (2026-08-18). **Decision: Room 2.8.4** (mature SupportOpenHelperFactory path) + **SQLCipher 4.18.0** (`net.zetetic:sqlcipher-android@aar`) + DataStore preferences 1.1.7 + Robolectric 4.16.1 (DAO tests pinned @Config sdk=[34]; SDK 36 needs JDK 21). Room 3 migration = documented revisit point.
- **Module scaffold (lead):** `settings.gradle.kts` include, version catalog entries (room/sqlcipher/sqlite/datastore/coroutines-test/robolectric), `core/persistence/build.gradle.kts` (ksp room-compiler, room.schemaLocation export to `schemas/`, maven-publish, test assets), rules.pro stubs.
- **C1.2–C1.4 (agent A):** 11 entities (Message/Conversation/Receipt/Outbox/Transfer/TransferChunk/RecentSearch/TrustedPeer/Reaction/Draft/ReadCursor), 11 DAOs (Flow reads; IGNORE dedup on messages/receipts; @Upsert last-write-wins for drafts/reactions/recents/cursors; keyset pagination `(sentAt<c)OR(=AND localId<)` with PK tiebreaker; composite seek index on (conversationId,sentAt,localId)), `FlashDatabase` v1 exportSchema=true, `FlashDatabaseOpener` (openEncrypted via System.loadLibrary("sqlcipher")+SupportOpenHelperFactory+PassphraseProvider seam; openInMemory test-only w/ loud destructive-migration comment).
- **C1.8 invariant tests (agent A):** Robolectric in-memory suite — duplicate message/receipt IGNORE, outbox claim→attempts→delete→re-claim-empty race semantics, read-cursor monotonicity (advanceFurthest transactional read-compare-write, older/equal no-op), keyset walk of 50 msgs / page 7 / tie-heavy no-dup-no-gap per-conversation scoping, chunk done-set resume bit-vector roundtrip + resetStuck.
- **C1.5–C1.6 (agent B):** `FlashSettingsDataStore` — all 9 plan keys incl. soundsEnabled default FALSE (D6), dynamicAccent, reduceMotionOverride, saveLocationUri, retentionDays, displayName; Flow readers + suspend writers, ReplaceFileCorruptionHandler(emptyPreferences), JVM-testable produceFile constructor (DataStore prefs is KMP-JVM capable per docs; plain-JVM tests over Robolectric). `RetentionPolicy` pure policy class (strictly-older cutoff, protected entries spared, retentionDays<=0 disables = keep-forever) + `PrunableSource` seam for the future DB-backed worker (C6/C7 hook).
- 21 new tests total across settings/retention/db packages.

### Verification
- Consolidated `testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL, 340 tests / 0 failures** (was 312).
- Room schema v1 exported: `core/persistence/schemas/com.transfer.flash.core.persistence.db.FlashDatabase/1.json` (in-repo, C1.7 baseline before any migration exists).
- Lead fixes: missing androidx.room imports in ReadCursorDao (KSP MissingType PROCESSING_ERROR); non-Comparable kotlin.Pair `<` in keyset walk test → explicit composite comparison.
- Three ERROR-008 E:-drive incidents this session (Gradle lock-file write failures + Kotlin daemon NoClassDefFoundError crashes); each recovered via --stop/kill-java/fresh no-daemon rerun. Pattern worsening — see Known blockers.

### Remaining
- SQLCipher encrypted-open path is compile-verified but NOT runtime-verified (native lib requires device/emulator) — add device smoke item: open DB encrypted, write/read row, reopen.
- Keystore-wrapped passphrase provider lands with C7/:app wiring.
- Retention pruner DB-backed worker (needs WorkManager decision) deferred to C6/C7.
- Next phase: P2 (:core:security full stack, C2.0–C2.8).

### Next AI
Start P2 per plan §5. R1 research-first every step. :core:* stay DI-agnostic. Beware E:-drive flakiness — commit incrementally.

## 2026-08-22 — Phase P0 Executed (C0 Foundations) + UI-040 Sound Unblocked

### Worked on
Owner approved D1 (Hilt) and D6 (subtle opt-in sounds, default off); executed core plan Phase P0 via three parallel research-first subagents with strict file ownership; lead ran one consolidated build and fixed integration issues.

### Changed
- **C0.1–C0.4 (`:core:common`, new files only):** `protocol/FlashProtocol` (VERSION=2, exact-match `isCompatible`, assert-on-handshake rationale w/ citations), `protocol/FlashEnvelope` (validated shared wire container), `logging/FlashLogger` (bounded thread-safe ring buffer, 512 default, Android Log forwarding wrapped JVM-safe) + `FlashLogEntry/Level`, `time/FlashTimeSource` + `SystemTimeSource` (+ test-source `FakeTimeSource`), `id/FlashIdGenerator` + `UuidIdGenerator`. JUnit4 tests for all.
- **C0.5 (Hilt DI skeleton in `:app`):** version catalog `hilt=2.60.1`, `ksp=2.3.11` (KSP2 standalone required by AGP 9 built-in Kotlin; Dagger ≥2.59 requires AGP ≥9 — satisfied by 9.3.1). Root plugins declared apply-false; app applies ksp+hilt; `di/FlashAppModule.kt` (@AppScope/@IoDispatcher/@DefaultDispatcher qualifiers nowinandroid-style, app CoroutineScope singleton, SampleFlashChatRepository provider), `di/FlashApplication.kt` (@HiltAndroidApp, registered in manifest), `MainActivity` annotated @AndroidEntryPoint. Composables not yet rewired (later phases).
- **C0.6:** `.github/workflows/ci.yml` — JDK17 temurin, `testDebugUnitTest assembleDebug` on push/PR, test-report artifact on failure.
- **UI-040 (D6 unblocked):** `ui/theme/FlashSounds.kt` — `FlashSound` enum (8 procedural PCM tone events), `ToneSegment`, `FlashSoundPolicy.shouldPlay` (respects enabled-flag + ringer silent/vibrate + DND interruption filter), `FlashSoundSettings` mutableStateOf bridge (default OFF; DataStore persistence lands C1.5), `FlashSoundSynth` pure-JVM renderer, `rememberFlashSounds()` composable + AudioTrack MODE_STATIC player (per Android guidance for short UI sounds, USAGE_ASSISTANCE_SONIFICATION). Full section added to `docs/ui/motion-system.md` w/ cited research; ui-research-index updated → **ALL UI-001–045 IMPLEMENTED except UI-045 gate**.
- **Docs:** ADR-011 (D1/D6 decisions + P0 execution) in `docs/decisions.md`.

### Verification
- Consolidated `testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL, 312 tests / 0 failures** (was 271; +41 new).
- Two ERROR-008 E:-drive daemon kills during the run; recovered per documented procedure (`--stop`, kill java, fresh no-daemon rerun).
- Lead fix: `FlashLogger.kt` used nonexistent `ArrayDeque.capacity()` → replaced with stored `maxCapacity` bound check (smallest-fix rule).
- NOT yet device-verified: Hilt runtime graph (needs installDebug launch), sound tones on hardware (silent/DND enforcement QA → backlog).

### Remaining
- Phase P1 (persistence module) is next per plan §5.
- Wire FlashSound call sites when real send/receive paths exist (documented in motion-system.md interaction table).
- Device backlog: add Hilt-graph smoke check + UI-040 toggle/tone QA items.

### Next AI
Start P1 (C1.0 research → C1.1 module creation). Keep R1 research-first discipline; :core:* modules must stay DI-agnostic.

## 2026-08-22 — Core Plan v2: UI-dependency audit + extensive step breakdown

### Worked on
Owner directed an iteration on `docs/core-upgrade-plan.md` grounded in what the finished UI actually needs, plus specific feature asks (continuous discovery, multi-stream transfer, full security stack, exhaustive messaging API).

### Changed
- **UI requirements audit:** two parallel research passes mined all 30+ `docs/ui/*.md` docs; produced capability→module map (§3.1) and explicit sample-data limitation list (§3.2) now embedded in the plan.
- **Web research (cited in plan §7):** NsdManager continuous discovery (API 34+ `registerServiceInfoCallback`, deprecated `resolveService`, NetworkRequest-scoped discovery), LocalSend protocol v2 (parallel upload routes, sha256 chunk verification, resumable uploads), offline-first chat sync patterns (durable outbox, pull-before-push delta sync, cursor receipts with furthest-forward merge, ephemeral-vs-durable state separation).
- **Plan rewritten to v2:** binding ground rules incl. mandatory research-first per step (R1) and reusable-library purity (R2); owner decision table (D2 SQLCipher / D3 SHA-256 / D4 E2E-in-C2 / D5 mesh-post-v1 approved; D1 DI + D6 sound still open); C0–C7 expanded from ~40 coarse steps to ~80 fine-grained steps each with research/acceptance hooks; new behavior contract for discovery (`startAll(identity)` = advertise own details + continuous browsing with lost-peer aging); network resilience upgrades enumerated (backoff+jitter, NetworkCallback instant reconnect, heartbeat dead-peer detection, bounded per-peer queues, session coalescing); multi-stream transfer as explicit feature (C5.7) with benchmark-before-defaults rule; messaging section lists complete screen-facing API surface.
- **ADR-010** added to `docs/decisions.md` recording D2/D3/D4/D5 approvals.

### Why
Everything visible runs on sample data; the UI docs define exact required inputs. The old plan was too coarse for accurate development and lacked the audit trail the owner wants.

### Verification
Documentation only — no code touched, build state unchanged (last green: 271 tests, 2026-08-22).

### Remaining
Owner sign-off on **D1 (DI framework)** before C0.5 and **D6 (sound)** before UI-040. Execution starts at Phase P0 once owner says go.

### Next AI
Start C0 after confirming D1. Follow R1 (research-first) for every step. Never run Gradle if working as a subagent; lead runs one consolidated build.

## 2026-08-22 - Demo Pages Removed + Plan Split into Core/Pages Parts

### Worked on
Per owner decision: removed the four provisional demo pages, and restructured core-upgrade-plan.md into two dedicated plan documents.

### Removed (git history preserves everything)
1. Icon QA sheet (FlashIconSheet.kt, :ui:theme/icons)
2. Motion QA sheet (FlashMotionSheet.kt, :ui:theme)
3. Experimental WS transfer page (:ui:transfer module deleted - WsTransferScreen/WsFileActions/test; module removed from settings.gradle.kts and app dependencies)
4. LAN discovery demo home (FlashHomeScreen + helpers in MainActivity)

Engine classes (LanController, WsTransferManager, WsDiscovery, WsPairingStore, AppIdentity) remain in :app as relocation sources for core Phase C4/C5. MainActivity rewritten as a minimal ChatList-Conversation shell until bottom navigation lands.

### Changed
- docs/core-upgrade-plan.md is now **PART 1: Core Components** only - reorganized per-component (C0 Foundations, C1 Persistence, C2 Security, C3 Discovery, C4 Network, C5 Transfer, C6 Messaging, C7 Engine facade), each with Current state / Target abstraction / Implementation steps / Frontend exposure.
- docs/ui-page-plan.md is NEW **PART 2: Pages & Navigation** - app shell (bottom nav Chats/Transfers/Nearby/Settings + Send FAB), page specs P1-P5 with core-API dependencies and states, overlay inventory, integration checklist.
- Handoff updated to reference both parts; Deferred block points at the split plans.

### Verification
- assembleDebug - BUILD SUCCESSFUL after one ERROR-008 daemon recovery cycle.

## 2026-08-22 — Core Upgrade & API Exposure Plan (research + planning only)

### Worked on
Surveyed all six `:core:*` modules (public APIs + gaps), performed extensive online research, and authored **`docs/core-upgrade-plan.md`** (PROPOSED — no code implemented per owner instruction).

### Research performed (online)
- LocalSend protocol v2 (receiver-runs-HTTP model, PIN verify, reverse browser transfer, multi-recipient) + Quick Share benchmarks (LAN ≫ Wi-Fi Direct throughput).
- Knit / bitchat-android / AirChat mesh messengers (dual-radio transport seams, signed relay frames w/ TTL dedup, store-and-forward, battery tiers, Noise/P-256 E2E patterns, offline APK self-share).
- mftp + Swoosh + gusset transfer engineering (chunk bit-vector resume, BLAKE3/SHA-256 integrity, adaptive chunking, zstd, TOFU pinning, AAD-bound ciphertexts).
- Stream offline-sync + chat architecture articles and Android offline-first guide (Room source-of-truth, outbox+WorkManager backoff/jitter, pull-delta-before-replay, receipt batching, tombstones).

### Created
- `docs/core-upgrade-plan.md`: current-state inventory per module; target architecture (`FlashEngine` facade over Room-backed repositories); **9 phases / ~64 numbered steps** (foundations → persistence → real messaging engine → transfer v2 → security/TLS/TOFU/pairing → discovery expansion (Aware/Direct/BLE seam) → background runtime → frontend API exposure → hardening); bottom-navigation recommendation (**Chats / Transfers / Nearby / Settings** + Send FAB); feature backlog **F01–F30** with sources; decisions D1–D6 requiring owner input (DI framework, at-rest encryption, hash lib, frame E2E, mesh scope, sound/UI-040).

### Not done
- No implementation (owner: "don't implement anything").

---

## 2026-08-22 — Git repository enabled + initial push to GitHub

### Worked on
Enabled version control for the project (previously un-managed per earlier handoffs).

### Changed
- Extended `.gitignore`: module `build/` dirs, `.gradle-user-home/`, `.kotlin/`, `.idea/`, `*.log` build-noise files, `local.properties`.
- `git init -b main` → remote `origin = https://github.com/Kali452345/Flash.git`.
- Initial commit `8a5c458` — 330 files / ~40k lines (all source, docs, logs; zero build artifacts verified pre-commit).
- Pushed to `origin/main`.

### Note
Git identity set repo-locally (Kali452345 / noreply email) — adjust if a different identity is wanted.

---

## 2026-08-22 - Final Parallel Round: UI-034/038/039/041/042/043 - IMPLEMENTED

### Worked on
Third subagent round closed out the roadmap. All UI-001-045 IDs are now IMPLEMENTED except UI-040 (BLOCKED: needs owner decision on sound feedback) and UI-045 (quality gate: intentionally last, after device verification).

### Delivered
**UI-034 Adaptive layouts (Agent A):** FlashAdaptiveLayouts.kt - zero-dependency window-size classes (Compact <600 / Medium 600-840 / Expanded >=840 via BoxWithConstraints), FlashAdaptiveTwoPane with weighted panes + hairline divider; material3-window-size-class evaluated and documented as recommendation-only. 5 tests. responsive-layout.md filled.

**UI-038/039/041 A11y + Haptics + Micro-interactions (Agent B):**
- FlashFeedback.kt (new, :ui:theme): FlashHaptic vocabulary (Tick/Confirm/Warn/Reject) + rememberFlashHaptics() single choke point; ALL 15 direct performHapticFeedback call sites across :ui:chat migrated.
- A11y audit fixes applied mechanically: bubble selection stateDescription, header avatar Role.Button, media-viewer counter liveRegion, new-messages pill live region; full findings table in accessibility.md (filled).
- Search chrome press-scales added; motion-system.md gained micro-interaction inventory (~15 interactions) + spring-token table. Tests added.

**UI-042/043 Performance research + Stress harness (Agent C):**
- FlashStressTestScreen.kt: deterministic O(n) synthetic thread generator (xorshift64) mixing text/reactions/images(gradient-fallback)/voice/file/replies at presets 100-2000, rendering through the REAL FlashMessageList; performance.md filled with component-cost inventory, measurement plan (Macrobenchmark/gfxinfo/heap), and code-review findings (BoxWithConstraints subcomposition per bubble, lambda-allocation skippability concerns flagged for device measurement).
- Research: Compose lists/stability/skippability docs, Macrobenchmark & Baseline Profile methodology.

### Lead integration fixes
- Restored missing positionChange import in FlashVoiceRecording.kt (dropped during agent import cleanup).
- Relaxed one over-strict stress test assertion (random Reply-kind picks make >= the correct invariant vs ==).

### Verification
- Full build after ERROR-008 daemon recovery: BUILD SUCCESSFUL.
- 271 tests / 0 failures across all modules (+26 this round).

---

## 2026-08-22 — UI-044 + UI-035/036 + UI-033 via Triple Parallel Subagents (with online research)

### Worked on
Second triple-parallel-subagent round. Each agent read AGENTS.md §34, its target doc, and all pattern-matching sources first, then performed live web research with citations. Lead integrated and built.

### Delivered
**UI-044 Network-state simulation (Agent A):**
- `FlashNetworkSimSheet.kt` (new): `FlashNetworkSimMath` (health cycling, labels), `rememberSimulatedHealth(real, simulated)` merge-at-read helper, `FlashNetworkSimSheet` bottom sheet with custom-drawn radio rows over the four connection states; `error-states.md` UI-044 section filled; tests added.
- Research: Chrome DevTools throttling, Android emulator networking, Beagle/Tapadoo debug menus, production-safe override patterns.

**UI-035 Dark theme + UI-036 Dynamic color (Agent B):**
- **Audit found & fixed two real contrast gaps** in `FlashColors.dark()`: `textOnAccent` white→pulse900 (2.5:1→~5.9:1 on pulse400) and `avatarPlaceholderText` graphite500→graphite300 (~2.8:1→~5.8:1). (One dropped slot `avatarPlaceholderBackground` restored by lead during integration.)
- `resolveAccent()` pure helper formalizes UI-036: dynamic wallpaper accents (SDK ≥ S, opt-in flag, accents only per ADR-005); public API backward-compatible.
- New previews: full dark-palette sweep + dynamic-accent light/dark.
- Tests: 12 new (dark-slot divergence, no pure black/white backgrounds, WCAG luminance-computed contrast guards, resolveAccent SDK/fallback matrix).
- Research: M3 dynamic color/HCT tonal palettes, WCAG dark-theme guidance, theme-mode settings patterns.

**UI-033 Navigation (Agent C):**
- `ui/navigation/FlashNavigation.kt` (new): dependency-free `FlashNavigationState` stack (depth cap 10, duplicate-push guard incl. conversationId), `FlashDestination`, `rememberFlashNavigationState`, generic `FlashAnimatedScreen` using reserved `motion.screenEnter()/screenExit()` tokens.
- `navigation.md` created/filled — documents honest evaluation of androidx.navigation (deferred, trade-offs recorded).
- 12 unit tests (no Compose runtime).
- Research: predictive-back guide, type-safe navigation, conditional-navigation pitfalls.

### Integration fixes by lead
- Restored `avatarPlaceholderBackground` accidentally dropped from dark() during agent edit.
- Fixed `FlashAnimatedScreen`: content lambda signature mismatch (`AnimatedContentScope` receiver) and motion read moved outside `transitionSpec`.

### Verification
- Consolidated build after daemon recovery (ERROR-008 recurrence): **BUILD SUCCESSFUL**.
- **245 tests / 0 failures across all modules** (up from 147 in :ui:chat alone).

---

## 2026-08-22 — UI-024 + UI-031 + UI-032 via Triple Parallel Subagents (with online research)

### Worked on
Ran **three parallel subagents simultaneously**, each required to (a) read AGENTS.md §34, the component-doc template, their target doc, and all pattern-matching source files before changing anything, and (b) perform live web searches for design inspiration with citations. Lead engineer handled integration and the single consolidated build.

### Delivered
**UI-024 Global / chat-list search (Agent A):**
- `FlashChatListSearch.kt`: `FlashChatListSearchMath` (filter by title/preview, recents dedupe/cap), `FlashChatListSearchBar` (BasicTextField pill, Back-glyph close, live count), `FlashRecentSearchChips`.
- `FlashChatListScreen.kt` wired: search-mode top-bar swap, live filtering, recents row (in-memory; persistence documented as limitation).
- `search-ui.md` UI-024 section filled; 10 unit tests.
- Research: WhatsApp recent-searches/filters, Telegram grouped search, Slack recents/suggestions, Discord empty-state study.

**UI-031 Encryption indicators (Agent B):**
- `FlashEncryptionIndicators.kt`: `FlashEncryptionBadge` (Trusted/Unverified/None states), `FlashEncryptionSheet` (plain-language E2EE explainer for P2P scope + disabled verification entry points until engine lands), `FlashEncryptionMath`; 9 unit tests.
- `chat-screen.md` UI-031 section filled.
- Research: iMessage Contact Key Verification, WhatsApp E2EE FAQ, Signal safety numbers, SOUPS 2017 auth-ceremony study, PoPETs 2025 key-transparency study.

**UI-032 Device pairing flow (Agent C):**
- `FlashPairingFlow.kt`: in-screen pairing dialog (numeric-comparison code "123 456", Canvas countdown bar, Accept/Decline pills, Awaiting/Paired/Declined/Expired phase visuals per error-states severity language), `FlashPairingMath` + models local to ui/chat; 14 unit tests; 6 previews incl. dark.
- `profile-ui.md` created/filled (UI-032 DESIGNED → IMPLEMENTED).
- Research: Bluetooth SIG numeric comparison, Silicon Labs/Nordic pairing processes, Signal safety-number updates.

### Verification
- Consolidated build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks).
- `:ui:chat`: **147 tests / 0 failures** across 20 suites (+33 from this round).
- §34 spot-audit of all new files clean.

### Remaining
- Integration wiring: encryption badge into header/composer area, pairing dialog trigger from Nearby Devices flow (needs discovery engine hookup).
- Recent-search persistence; `isVerified` has no engine source yet (passes false).
- Device verification backlog continues to grow (UI-019–032).

---

## 2026-08-21 — Device-Feedback Bug Round + UI-023 In-Chat Search — IMPLEMENTED

### Worked on
Investigated and fixed four device-reported bugs, then implemented **UI-023 (In-chat search)** per the new `docs/ui/search-ui.md`.

### Bug fixes (each logged in `logs/errors.md`)
1. **ERROR-010 — Recording gesture loss**: composer's `AnimatedContent(recordingPhase)` disposed the mic button mid-hold, killing the active pointer stream. Restructured so `FlashMicButton` lives OUTSIDE the swapped region — one persistent node across Idle/Holding/CancelArmed; only Locked swaps layout post-release.
2. **ERROR-009 — Double IME padding**: removed `.imePadding()` from the `FlashMessageList` call site; keyboard clearance now flows only through Scaffold `innerPadding` (composer bottomBar already grows with IME).
3. **ERROR-011 — Multi-tap overlay dismissal**: replaced the separate-window `Dialog` with an in-screen scrim overlay (last child of the layout) plus explicit close button + BackHandler — first-tap dismiss now lands directly.
4. **NSD crash report**: stale logcat from 2026-08-20; ERROR-006 fix already present in code (`onResolvedCallback`). No change needed.

### UI-023 implementation
- `docs/ui/search-ui.md`: new research/design doc (Telegram/WhatsApp/Signal patterns; header-swap inline search chosen; Material SearchBar rejected per §34).
- **`FlashChatSearchBar.kt` (new)**: `FlashChatSearchMath` (case-insensitive matching, non-overlapping match ranges, newest-first results with wrap-around stepping, counter label) + `FlashChatSearchBar` composable (close, query field pill, liveRegion counter "3 / 7", prev/next chevrons from rotated Flash back glyph) + `buildHighlightedMessageText`.
- **Wiring**: Search action now available in ALL conversations; header swaps between selection toolbar / search bar / normal header via single `AnimatedContent(Pair)`; result stepping reuses scroll+pulse-highlight pipeline; `searchQuery` threaded through `FlashMessageList` → `FlashMessageBubble` for in-bubble substring highlighting.
- **`FlashText`** gained an `AnnotatedString` overload (foundation BasicText — ADR-009 compliant).
- **ADR-009 follow-through**: migrated `FlashMessageBubble` off Material components entirely — custom bubble Box (clip+background+border stroke) replaces `material3.Surface`, all text now `FlashText`.
- **Unit tests**: `FlashChatSearchLogicTest.kt` — 8 tests.

### Verification
- Full build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks).
- `:ui:chat`: **114 tests / 0 failures** across 16 suites.
- §34 spot-audit of touched files clean (no material3 refs remain in FlashMessageBubble).

### Remaining
- Device re-test: recording gestures, keyboard gap, overlay single-tap dismiss, search flow.
- ERROR-008 hardware follow-up (E: drive power management) still open on owner side.

---

## 2026-08-21 — UI-029 + UI-030 Implemented via Parallel Subagents

### Worked on
Ran **two parallel subagents** to implement UI-029 (group member presentation) and UI-030 (device/network status UI) simultaneously — first multi-agent session. File ownership was strictly partitioned; agents were forbidden from running Gradle (cache contention on the flaky E: drive) and from touching shared files.

### Changed
**UI-029 (subagent A):**
- `FlashMessagingModels.kt`: added `FlashMemberRole` enum + `FlashGroupMemberUi` data class.
- `FlashGroupMembersSheet.kt` (new): custom member rows in a bottom sheet — avatar with online-dot overlay, transport subtitle + glyph, role badge pills (Owner/Admin), hand-drawn hairline dividers, no `ListItem`; `FlashGroupMembersMath` (online-first/rank/alphabetical sort, summary labels, badge labels, row cap) + sample roster + previews.
- `docs/ui/group-ui.md`: UI-029 section filled DESIGNED → IMPLEMENTED.
- `FlashGroupMembersLogicTest.kt` (new).

**UI-030 (subagent B):**
- `FlashNetworkStatusUi.kt` (new): `FlashConnectionHealth`/severity enums, `FlashNetworkStatusMath` (health resolution, labels, blocking-state, calm-vs-attention severity per error-states language), `FlashConnectionBanner` (compact non-blocking strip, retry pill only when blocking, never red for offline), `FlashTransportBadge` chip; 6 previews.
- `docs/ui/chat-screen.md`: UI-030 section filled IMPLEMENTED.
- `FlashNetworkStatusLogicTest.kt` (new).

**Integration (lead):**
- `FlashConversationScreen.kt`: connection banner under header (hidden while Connected, fade via motion tokens); group avatar tap opens members sheet (`showGroupMembers`, demo roster until repository feeds live members); group Search action toast stub.
- Fixed 3 subagent compile/test issues: nullable icon spec passed to non-null param; AnimatedContent transform misuse; `resolveHealth` precedence (peerCount==0 → Offline must trump Connected/Relay paths).

### Verification
- Full build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks).
- `:ui:chat`: **106 tests, 0 failures** across 15 suites.
- §34 spot-audit of both new files: clean (no material3.Text/Icons/ListItem/Button).

### Remaining
- Device verification backlog: UI-019–022, UI-025–028, UI-029–030.
- Members sheet uses demo roster until live member feed exists; auto-retry/backoff deferred to engine (UI-044).

---

## 2026-08-21 — UI-028: Group Chat Header — IMPLEMENTED (with online research)

### Worked on
Researched (live web sources), designed, and implemented **UI-028 (Group Chat Header)** per the new DESIGNED section in `docs/ui/group-ui.md`.

### Research performed (online)
- Stream channel-header docs (Android/iOS/RN cookbooks — pattern reference only, no SDK code/dependencies): member+online count subtitle, connection override, stacked member avatars fallback.
- Ethora chat UX guide: overlapping circles up to 3 or 2×2 grid; consistent color-hash per member.
- Telegram/WhatsApp/Signal header behavior: collage identity, "X members, Y online", named typing capped at two names.

### Changed
- **`docs/ui/group-ui.md`**: filled from NOT STARTED to UI-028 IMPLEMENTED (UI-029 remains separate).
- **Model** (`FlashChatHeaderUiState`): added `memberInitials`, `memberCount`, `onlineCount`, `typingMemberNames` — all defaulted, zero breakage.
- **`FlashGroupHeader.kt` (new)**: `FlashGroupHeaderMath` pure logic (collage layout selection Single/TwoVertical/OneLargeTwoSmall/Quad, initials cap at 4 with blank filtering, singular-safe "N members · M online" label, named typing labels capped at 2 names + "+N more", subtitle precedence) + `FlashGroupAvatar` clipped-circle collage using shared seeded avatar palette (`flashAvatarColorsFor` helper added to `:ui:theme`) and `FlashText`.
- **`FlashChatHeader.kt`**: group branches — collage avatar slot when ≥2 member initials, subtitle precedence (typing → explicit summary → computed counts), named typing dots + accent label for groups, Search action added for groups (new `onSearchClick` callback). Also migrated this file off Material components per ADR-009: custom 48dp icon buttons replace `material3.IconButton`, drawn hairline replaces `HorizontalDivider`, all text now `FlashText`.
- **Sample data**: group sample header now uses real counts + member initials.
- **Unit tests**: `FlashGroupHeaderLogicTest.kt` — 6 tests (layouts incl. degenerate inputs, initials capping, subtitle labels, typing label capping).

### Verification
- Full build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks, all unit tests green).
- Note: intermittent `IOException: The device is not ready` from the E: drive during builds this session (Gradle cache writes); resolved per-run by retrying / `--no-daemon --no-configuration-cache`. Environment issue, not code.

### Remaining
- Device verification: group conversation header renders collage + counts; search action stub.
- UI-029 (member rows/admin badges) is the natural follow-up in the same doc.

---

## 2026-08-21 — §34 Customness Audit + `FlashText` Design-System Primitive (ADR-009)

### Worked on
Owner-requested audit of all session implementations (UI-018–022, UI-025/026/027) against the "everything custom" rule, plus remediation.

### Audit results
- **Clean**: all icons Flash-owned (zero `Icons.Default/Filled/Outlined` in `:ui:chat`); all buttons/badges/chrome custom composables; composer on foundation `BasicTextField`; waveforms/skeletons raw Canvas/Box; pager/gestures = permitted foundation infrastructure; no Stream deps.
- **Gap found & fixed**: text rendered via `material3.Text`. Added **`FlashText`** (`:ui:theme`, foundation `BasicText` + `FlashTypography` tokens — ADR-009) and migrated all my components (`FlashMediaViewer`, `FlashVoiceMessageCard`, `FlashVoiceRecording`, `FlashMessageList` pill, `FlashStateViews`) to it. Verified zero `material3` references remain in those files.
- **Flagged for later (predate this session)**: `material3.IconButton` in `FlashReplyDock`, `CircularProgressIndicator` in `FlashFileIconBadge`, `HorizontalDivider`, `Scaffold` — recorded in ADR-009 for opportunistic migration.

### Verification
- Full build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL**, all unit tests green.

---

## 2026-08-21 — UI-025/026/027: Empty, Loading & Error States — IMPLEMENTED (with online research)

### Worked on
Researched (including live web research), designed, and implemented the three system-state components per new docs `docs/ui/empty-states.md`, `loading-states.md`, `error-states.md`.

### Research performed (online)
- NN/g "Designing Empty States in Complex Applications" + Carbon Design System empty-states pattern: three jobs (name screen / explain why empty / one action); replace data region entirely; never dead-end.
- 137foundry + Pixxen: generic copy is an anti-pattern; single primary CTA on first run.
- Skeleton research: NN/g video, 72technologies loading-pattern guide, accessible-data-interfaces.com (skeletons decorative + status announcements; reduce-motion guard), Codexical review of Viget 2017 / ACM ECCE 2018 (mismatched skeletons can feel slower → match real geometry within ~10%).
- web.dev offline UX guidelines + Android offline-first LCE architecture guide + Coder Legion offline handling: distinguish environmental (offline/peer-unreachable — neutral color) from failure (red); always provide one recovery action; don't block content.

### Changed
- **`FlashStateViews.kt` (new, `:ui:chat`)**:
  - `FlashStateMath` — 300ms delay guard (`shouldShowLoadingIndicator`), skeleton row cap (12).
  - `FlashStateCopy` — screen-specific empty copy (ChatListFirstRun: "No conversations yet / Find devices"; ConversationEmpty: "Say hello"); anti-generic-copy unit-test guard.
  - `FlashEmptyState` — 72dp accent medallion + headline + body + optional pill CTA (UI-025).
  - `FlashErrorState` — severity split per web.dev: Failure (red, `FlashIcons.Failed`) vs Environmental (neutral Pulse accent, `FlashIcons.Connection`); single Retry pill (UI-027).
  - `FlashSkeletonChatList` / `FlashSkeletonConversation` — layout-matched skeletons (real 72dp rows, avatar sizes, bubble shapes), opacity pulse static under reduce-motion, `clearAndSetSemantics {}` decorative semantics (UI-026).
- **Wiring**: `FlashChatListScreen` gained `isLoading` / `errorMessage` / `isErrorEnvironmental` / `onRetryLoad` / `onFindDevicesClick` with precedence error → skeleton → empty → list; `FlashConversationScreen` shows the conversation-empty state when no messages. New previews for all states.
- **Unit tests**: `FlashStatesLogicTest.kt` — delay guard, row cap, copy specificity/non-blank guards.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks, all unit tests green).

### Remaining
- Host screens don't yet emit TalkBack loading/loaded announcements (needs repository state wiring).
- Search-no-results variant deferred to UI-023/024; auto-retry/backoff indicator deferred to UI-044.

### Next AI
Device-test pending components (UI-019–022, UI-025–027), then research **UI-030 (network status UI)** or **UI-028 (group header)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-21 — UI-021 + UI-022: Chat Scrolling & Jump-to-Latest — IMPLEMENTED

### Worked on
Researched, designed, and implemented **UI-021 (Chat Scrolling)** and **UI-022 (Jump to Latest)** per the new sections in `docs/ui/chat-screen.md`.

### Changed
- **Design doc** (`docs/ui/chat-screen.md`): UI-021/UI-022 sections filled from _Deferred_ to IMPLEMENTED — behavior matrix (auto-scroll at bottom / own sends; unseen pill while scrolled up; image-resize pinning via reverseLayout; keyboard retention), rejected approaches (always-autoscroll; silent-superseded v1).
- **`FlashMessageList.kt`**:
  - New `FlashChatScrollMath` pure logic: `nextUnseenCount` (resets at bottom / on own send which auto-scrolls; increments on peer arrivals while scrolled up), `isNewTailMessage` (tail-id change detection — reaction edits don't count), `shouldShowNewMessagesPill`, `pillLabel`.
  - Unseen tracking wired: tail-id LaunchedEffect + `derivedStateOf` at-bottom reset.
  - List wrapped in Box with floating **`FlashNewMessagesPill`** (UI-022): accent pill, down-chevron = Flash back glyph rotated −90° (no new icon), tap animates to latest and clears counter; fade+slide entrance via motion tokens; Role.Button a11y ("Jump to N new messages").
- **Unit tests**: `FlashChatScrollLogicTest.kt` — 7 tests covering counter transitions, arrival detection, pill visibility/label.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks, all unit tests green).

### Remaining
- Device verification: send messages from a peer while scrolled up → pill counts; tap pill jumps; return-to-bottom resets. History pagination still out of scope (no repository paging).

### Next AI
Device-test UI-019/020/021/022, then research **UI-025/026/027 (empty/loading/error states)** or **UI-030 (network status UI)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-21 — UI-020: Voice Recording Interface — IMPLEMENTED

### Worked on
Researched, designed, and implemented **UI-020 (Voice Recording Interface)** per the new UI-020 section in `docs/ui/voice-message.md` — the composer transforms into a recording surface with hold-to-record, slide-to-cancel, lock-to-record, timer, live amplitude strip, and trash/pause/send controls.

### Changed
- **Design doc** (`docs/ui/voice-message.md`): added full UI-020 section — compared WhatsApp/Signal (hold + slide-left-cancel), Telegram (slide-up lock + persistent panel), iMessage (full-screen, rejected); hybrid state machine with unit-tested thresholds (`CANCEL_SLIDE_DP=96`, `LOCK_SLIDE_DP=72`, `MIN_RECORD_MS=500`).
- **`FlashVoiceRecording.kt` (new, `:ui:chat`)**:
  - `FlashRecordingPhase` (Idle/Holding/CancelArmed/Locked) + `FlashHoldSlideTarget`.
  - `FlashVoiceRecordingMath` — dominant-axis slide resolution, EMA amplitude smoothing, bounded demo random-walk amplitude generator, short-press discard rule, strip windowing.
  - `FlashMicButton` — occupies the send slot when draft is blank; low-level `awaitEachGesture` hold gesture streams cumulative drag to parent; press-scale + accent color transitions.
  - `FlashVoiceRecordingBar` — hold mode: pulsing red dot (reduce-motion-safe) + timer · Canvas amplitude strip · "‹ Slide to cancel" / "Release to cancel" (error-tinted when armed). Locked mode: trash · strip · pause/resume · timer · accent send. AnimatedContent mode swaps.
- **`FlashComposer.kt`**: mic/send swap when draft blank; phase-driven `AnimatedContent` — **mic button stays mounted during Holding/CancelArmed so the live gesture keeps flowing** (critical design point; only Lock swaps to the full-width panel); 100ms ticker advances timer + appends smoothed demo amplitudes; new `onSendVoice: (FlashVoiceAttachmentUi) -> Unit` callback producing a real `FlashVoiceAttachmentUi` (durationMs + amplitudes).
- **Unit tests**: `FlashVoiceRecordingLogicTest.kt` — 8 tests (slide resolution incl. dominant-axis conflicts, EMA clamping, random-walk bounds over 500 iterations, short-press discard, strip windowing).

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks, all unit tests green).
- One test expectation corrected (EMA truncates: 59.8 → 59).

### Known limitations
- Demo-mode capture: no audio file is produced; real capture needs RECORD_AUDIO permission flow + MediaRecorder engine + pipeline ADR (documented in component doc).

### Remaining
- Device verification: hold→speak→release sends; slide-left arms cancel; release cancels; slide-up locks; trash/pause/send; short tap discards silently.

### Next AI
Device-test UI-019/UI-020, then research **UI-021 (Chat Scrolling)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-21 — UI-019 Device Feedback Fixes (layout, long-press context, preview, play/pause animation)

### Worked on
Applied owner's device-test feedback on the UI-019 voice message card.

### Changed
- **Layout fix**: speed pill moved from the right-hand stack to **under the badge on the left side**; remaining/duration label now sits alone on the **right side**, vertically centered — waveform is unobstructed full-width between them.
- **Long-press context support**: card uses `combinedClickable` with a new `onLongPress` callback (haptic + `onOpenActions`), so long-pressing the voice card opens the UI-007/UI-008 focus overlay like text bubbles.
- **Focus overlay content fix**: new pure helper `flashMessageContentSummary()` in `:core:messaging` (`FlashMessagingUtils.kt`) returns `Voice message • m:ss` / `Photo` / `N photos` / file name for blank-text messages; `FlashFocusedBubblePreview` renders it instead of empty text (previously only sender name showed).
- **Play/pause animation**: badge icon swaps through an `AnimatedContent` spring scale (0.6×→1×) + fade morph using `FlashMotion.springSnappySpec()`/`tweenFastSpec()`.
- **Tests**: added `FlashMessageContentSummaryTest.kt` in `:core:messaging` (5 tests).

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks, all unit tests green).

### Remaining
- Re-test on device: layout sides, long-press context menu, overlay summary line, play/pause morph.

---

## 2026-08-21 — UI-018 VERIFIED + UI-019: Voice Message Playback — IMPLEMENTED

### Worked on
1. Marked **UI-018 (Media Viewer) VERIFIED** — owner confirmed on device (Samsung SM_G986U1) that tapping a grid tile opens the viewer and does **not** also trigger the bubble context menu.
2. Researched, designed, and implemented **UI-019 (Voice Message Playback)** per the new DESIGNED spec in `docs/ui/voice-message.md`.

### Changed
- **Research & Design Document** (`docs/ui/voice-message.md`):
  - Filled from NOT STARTED to DESIGNED: compared Telegram (discrete bar waveform, remaining-countdown label), WhatsApp (smooth waveform, circular badge), Signal (plain progress bar — rejected as prohibited generic), iMessage (scrubbing), Discord (speed control).
  - Selected: Telegram-style 40-bar discrete waveform + WhatsApp-style 48dp badge + Discord-style speed pill; real audio decode deferred pending Media3 dependency ADR.
- **Model** (`:core:messaging`, `FlashMessagingModels.kt`):
  - Added `FlashVoiceAttachmentUi(id, uri, durationMs, amplitudes, mimeType, transferStatus)` reusing `FlashFileTransferStatus`.
  - Added `voiceAttachments: List<FlashVoiceAttachmentUi>` to `FlashMessageUi`.
- **`FlashVoiceMessageCard.kt` (new, `:ui:chat`)**:
  - `FlashVoiceMath` — pure logic: `m:ss` duration formatting, peak-preserving amplitude bucketing to exactly N bars, tap→fraction/bar-index mapping, elapsed-from-fraction, speed cycle (1×→1.5×→2×), Telegram-style trailing label (remaining countdown mid-playback ↔ total when untouched/finished), played-bar count.
  - `FlashVoiceMessageCard` — attachment-surface card matching UI-016 language; demo-mode 100ms playback ticker scaled by speed; auto-stop at end.
  - `FlashVoiceBadge` — 48dp circle: Play/Pause (accent), Download (neutral), Retry (error); 0.90× spring press physics.
  - `FlashVoiceWaveform` — Canvas bars (3dp/2dp gap, rounded caps), accent played vs 45%-alpha unplayed, tap-to-seek + horizontal drag scrub via dedicated pointer inputs.
  - `FlashVoiceSpeedPill` — chip with active accent border while speed ≠ 1×.
  - TalkBack: merged description with duration + play state, stateDescription Playing/Paused, per-control button semantics.
- **Integration**: `FlashMessageBubble` renders voice cards; sample voice message added to `sampleFlashConversationState()` for device testing.
- **Unit tests**: `FlashVoiceLogicTest.kt` — 13 tests covering all `FlashVoiceMath` behavior.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks).
- `:ui:chat` test suites all green (49 tests across 12 suites, including new `FlashVoiceLogicTest`: 13/13).

### Remaining
- UI-019 device verification: play/pause, tap-seek, drag scrub, speed cycle, label swap, dark mode both directions.
- Real audio output deferred (Media3 ADR required once attachment pipeline lands) — documented in component doc Known limitations.

### Next AI
Device-test UI-019, then research **UI-021 (Chat Scrolling)** or **UI-020 (Voice Recording)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-21 — UI-018: Media Viewer — IMPLEMENTED

### Worked on
Implemented **UI-018 (Media Viewer)** per the DESIGNED spec in `docs/ui/media-viewer.md` — full-screen immersive photo viewer with pinch/double-tap zoom, pan, vertical drag-to-dismiss, HorizontalPager album carousel, auto-hiding chrome, and sample-size-guarded bitmap decode.

### Changed
- **`FlashMediaViewer.kt` (new, `:ui:chat`)**:
  - `FlashMediaViewerMath` — pure, unit-testable gesture/decode logic: zoom clamp (1×–4×), pinch overshoot ceiling (×1.35), anchored-offset invariant (centroid-fixed zoom math), pan limits, dismiss claim policy (vertical dominance ≥ 2× touch slop), dismiss distance (180dp) / velocity (900px/s) thresholds, counter + TalkBack page descriptions, initial-page clamp, power-of-two `inSampleSize` guard (long edge ≤ 4096px), backdrop alpha & page-scale dismiss mapping.
  - `FlashZoomState` / `rememberFlashZoomState` — per-page scale+offset transform state; spring reset/settle via `FlashMotion.springDefaultSpec()`.
  - `FlashMediaPage` — claim-policy gesture scope (`awaitEachGesture`): pinch owns → zoomed pan owns → un-zoomed dominant-vertical drag dismisses → horizontal left unconsumed for pager. Separate lightweight `detectTapGestures` scope: single tap toggles chrome, double-tap springs to 2.3× anchored at tap point (or back to 1×). Two-pass bounds+sample decode on `Dispatchers.IO` via `produceState`; seed-gradient loading placeholder; failure state with Flash icon + text.
  - `FlashMediaViewer` — always-dark `mediaViewerBackdrop` (drawBehind-only alpha during drag = zero recomposition), page scale 0.94 + half-translate during dismiss, `HorizontalPager(beyondViewportPageCount = 1)`, top chrome (close, `n / m` counter, more) + bottom chrome (sender • time, Save/Share/Forward) with white-92 `mediaViewerChromeText`, 48dp targets, BackHandler.
  - Suspending gesture calls routed through the external composition scope because `awaitEachGesture` is a restricted-suspension scope (documented in component doc).
- **Tap path threading**: `FlashImageGrid.onImageClick` → `FlashMessageBubble` (new param) → `FlashMessageList` (`onImageClick(message, index)`) → `FlashConversationScreen`.
- **`FlashConversationScreen.kt`**: viewer state (`mediaViewerVisible`/`Items`/`StartIndex` — items persist through exit animation), overlay rendered in `AnimatedVisibility(motion.mediaOpenEnter/Exit)`, viewer-first BackHandler ordering, Toast placeholder actions for Save/Share/Forward (pipeline not connected yet).
- **Unit tests**: `FlashMediaViewerLogicTest.kt` — 14 tests covering all math/decision functions above.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks, all unit tests green).
- One test iteration: initial "center anchor preserves offset" expectation was mathematically wrong (correct invariant: center-anchor scales existing pan by ratio); test corrected to encode the true invariant.

### Remaining
- UI-018 device verification (Samsung SM_G986U1): open-from-tile smoke test (confirm bubble context menu does not also fire on tile tap), pinch/double-tap/dismiss/fling gestures, chrome toggle, dark/light backdrop — then mark VERIFIED.
- UI-019 (voice playback) or UI-021 (chat scrolling) research next.

### Next AI
Device-test UI-018 per its testing checklist, then proceed to UI-019/UI-021 research per `docs/ui/ui-research-index.md`.

---

## 2026-08-21 — UI-017: Image Message & Adaptive Grid Layout

### Worked on
Researched, designed, and implemented **UI-017 (Image Message & Adaptive Grid Layout)** in `:ui:chat` and `:core:messaging`.

### Changed
- **Research & Design Document**:
  - Authored `docs/ui/image-grid.md` with multi-app layout comparisons (Telegram, WhatsApp, Signal), aspect ratio bounding ($0.5$ to $2.0$), outer/inner radius masking, and overflow counter specifications.
  - Updated `docs/ui/ui-research-index.md` marking UI-017 as `IMPLEMENTED`.
- **Model Extensions (`:core:messaging`)**:
  - Added `FlashImageAttachmentUi` data class in `FlashMessagingModels.kt` containing URI, dimensions, MIME type, caption, and procedural seed tint.
  - Added `images: List<FlashImageAttachmentUi>` to `FlashMessageUi`.
  - Populated sample multi-image albums in `FlashMessagingUtils.kt`.
- **Adaptive Collage Layouts (`FlashImageGrid.kt` in `:ui:chat`)**:
  - `FlashSingleImageTile`: Clamped aspect ratio ($0.5$ to $2.0$) with min ($140\text{dp}$) and max ($300\text{dp}$) bounds.
  - `FlashTwoImageGrid`: 50/50 balanced side-by-side row ($180\text{dp}$ height) with $2.5\text{dp}$ micro-gutter.
  - `FlashThreeImageGrid`: Dynamic mosaic with leading primary tile ($60\%$ weight) and two stacked companion tiles.
  - `FlashFourImageGrid`: Symmetrical $2 \times 2$ matrix ($250\text{dp}$ height).
  - `FlashMultiImageGrid`: $2 \times 2$ grid with the 4th tile presenting a semi-transparent scrim and `+N` overflow chip (e.g. `+2`).
  - `FlashImageTile`: Async bitmap loading from `content://` and file paths with stylized gradient fallback and $0.97\times$ spring touch response.
  - `FlashFloatingTimestampPill`: Translucent frosted pill (`#73000000`) for borderless image messages.
- **Bubble Integration (`FlashMessageBubble.kt`)**:
  - Seamlessly rendered `FlashImageGrid` within incoming and outgoing message bubbles with text caption flow.
- **Unit Test Suite**:
  - Added `FlashImageGridLogicTest.kt` covering dimensions, model properties, and overflow arithmetic.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks, all unit tests green).

---

## 2026-08-20 — WebSocket Transfer: Received File Click-to-Open, Export (SAF), and Share

### Worked on
Implemented full file viewing, sharing, and device export capabilities for files received via the experimental WebSocket mesh transfer track.

### Changed
- **`FileProvider` Integration**:
  - Added `app/src/main/res/xml/file_paths.xml` configuring `ws-received/` and internal app storage directories.
  - Declared `androidx.core.content.FileProvider` in `app/src/main/AndroidManifest.xml` with `${applicationId}.fileprovider`.
- **`WsFileActions.kt` Added in `:ui:transfer`**:
  - `resolveFile(context, transfer)`: Automatically resolves physical files from `filePath`, `ws-received/${transfer.fileName}`, and detail paths across internal storage.
  - `openTransfer(context, transfer)` & `shareTransfer(context, transfer)`: Robust entrypoints ensuring files can always be opened and shared even if `filePath` was null in memory.
  - `openFile(context, filePath, fileName)`: Resolves MIME types with built-in fallback table, adds `ClipData` for intent chooser URI permissions, and falls back to wildcard `*/*` if specific viewer is absent.
  - `shareFile(context, filePath, fileName)`: Launches `ACTION_SEND` intent with URI stream and `ClipData` to share received files with other apps.
  - `exportFileToUri(context, sourceFilePath, destinationUri)`: Streams file bytes to user-selected destinations via Storage Access Framework (SAF).
  - `resolveMimeType(fileName)`: Maps file extensions to standard MIME types with runtime fallback to `MimeTypeMap`.
- **`WsTransferItem` Model Updated in `:core:transfer`**:
  - Added `filePath: String? = null` to track local destination on disk.
- **`WsTransferManager.kt` Updated**:
  - Stored `file.absolutePath` on incoming file transfers.
  - Added `loadExistingReceivedFiles()` on startup to scan `ws-received/` so previously received files appear in the transfers list.
- **`WsTransferScreen.kt` Enhanced**:
  - Completed transfer cards are clickable to open the file directly in default viewers with a prominent "READY" badge.
  - Added primary **Open** button, **Export** (via `ActivityResultContracts.CreateDocument` SAF picker), and **Share** buttons to completed transfer cards.
- **Unit Test Suite**:
  - Added `WsFileActionsTest.kt` covering MIME type mapping and transfer model path integration.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (357 tasks, all unit tests green).

---

## 2026-08-20 — Flash Custom Vector Icon Set Complete Redesign (24x24 & 2.0dp Stroke)

### Worked on
Redesigned the entire Flash-owned custom vector icon set (46 icons) from the ground up on a generous 24×24 grid with 2.0dp stroke weight, modern geometric balance, and increased default UI sizing.

### Changed
- **24×24 Viewport & Optical Footprint Optimization**:
  - Re-architected all vector paths across 46 XML drawables in `ui/theme/src/main/res/drawable/` (`flash_ic_*`), eliminating excessive internal padding.
  - Increased stroke weight from 1.5dp to a crisp, bold 2.0dp with round caps and joins.
- **Icon Sizing Scale in `FlashDimensions.kt`**:
  - `iconSm`: 16dp $\to$ 18dp
  - `iconMd`: 20dp $\to$ 24dp (Default action, header, and composer size)
  - `iconLg`: 24dp $\to$ 28dp
- **Icon Groups Redesigned**:
  - **Navigation & Actions**: `flash_ic_back`, `flash_ic_arrow_left`, `flash_ic_close`, `flash_ic_search`, `flash_ic_more`, `flash_ic_sliders`.
  - **Composer & Media**: `flash_ic_send` (modern paper airplane), `flash_ic_attach` (geometric paperclip), `flash_ic_camera`, `flash_ic_gallery` (photo card), `flash_ic_microphone`.
  - **Message Actions**: `flash_ic_reply`, `flash_ic_forward`, `flash_ic_edit`, `flash_ic_delete`, `flash_ic_pin`, `flash_ic_mute`, `flash_ic_archive`, `flash_ic_flag`, `flash_ic_thread`, `flash_ic_react`.
  - **Delivery & Transit**: `flash_ic_clock`, `flash_ic_check`, `flash_ic_delivered`, `flash_ic_read`, `flash_ic_failed`, `flash_ic_retry`, `flash_ic_verified`.
  - **Calls & Networking**: `flash_ic_call`, `flash_ic_video_call`, `flash_ic_wifi`, `flash_ic_wifi_direct`, `flash_ic_connection`, `flash_ic_device`, `flash_ic_group`, `flash_ic_relay`, `flash_ic_encryption`.
  - **Playback & Utility**: `flash_ic_download`, `flash_ic_upload`, `flash_ic_play`, `flash_ic_pause`, `flash_ic_stop`, `flash_ic_bolt`, `flash_ic_heart`, `flash_ic_thumb_up`, `flash_ic_thumb_down`.
- **Documentation**: Updated `docs/ui/icon-system.md` with 24×24 grid and 24dp render sizing specifications.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, all unit tests passing).

---

## 2026-08-20 — Edge-to-Edge System Bar Overlap & Window Insets Fix (ERROR-007)

### Worked on
Investigated and resolved system bar overlaps across top headers, status bar notch/camera cutout, bottom composer, and 3-button navigation bar.

### Changed
- **`FlashChatListTopBar.kt` & `FlashChatHeader.kt` & `FlashSelectionToolbar.kt`**:
  - Wrapped header roots with `.fillMaxWidth().background(colors.backgroundSurface).statusBarsPadding()`.
  - Safely offsets all titles, avatars, back buttons, search buttons, and LAN connection icons below the status bar clock, battery, and camera punch-hole cutout while maintaining seamless top surface background.
- **`FlashComposer.kt`**:
  - Applied `.navigationBarsPadding().imePadding()` to the root container.
  - Guarantees the message text field, attachment button, and send button sit above the 3-button navigation bar / gesture bar when closed, and lift cleanly above the soft keyboard when typing.
- **`FlashConversationScreen.kt`**:
  - Set Scaffold `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to allow exact measurement of top and bottom bar heights without double padding.
- **`FlashIconSheet.kt` & `FlashMotionSheet.kt`**:
  - Added `.statusBarsPadding().navigationBarsPadding()` to QA test screens.
- **Error Log**: Added ERROR-007 to `logs/errors.md`.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, all unit tests passing).

---

## 2026-08-20 — UI-016: File Message Card — IMPLEMENTED

### Worked on
Researched, designed, and implemented **UI-016 (File Message Card)** per `docs/ui/file-card.md` — rich in-bubble document card featuring color-coded file extension badges (`FlashFileIconBadge`), circular transfer progress rings with real-time throughput metrics (speed & ETA), formatted file sizes, and seamless integration into message bubbles (`FlashFileMessageCard`).

### Changed
- **Research & Design Document created (`docs/ui/file-card.md`):**
  - Analyzed file attachment cards across Telegram, Signal, WhatsApp, and Discord.
  - Selected leading 48dp action badge with color-coded extension tinting (PDF: Red, ZIP/Archive: Amber, Code: Blue, Audio: Violet, Video: Pink, Image: Cyan, Document: Indigo).
  - Specified live P2P transfer progress metrics (MB/s speed & ETA countdown), 12dp rounded attachment container with border hairline, 0.97x press physics, and TalkBack accessibility descriptions.
- **`FlashFileMessageCard.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - `FlashFileMessageCard` composable with responsive text truncation, surface styling, and tap actions.
  - `FlashFileIconBadge` with circular progress indicator, center pause/cancel icon, and file type color resolver.
  - `formatFileSize` helper formatting bytes into B, KB, MB, and GB.
- **`FlashFileAttachmentUi` model added in `:core:messaging` (`FlashMessagingModels.kt`):**
  - Added `FlashFileTransferStatus` (NotDownloaded, Transferring, Downloaded, Failed) and `FlashFileAttachmentUi` data class.
  - Added `fileAttachments: List<FlashFileAttachmentUi>` to `FlashMessageUi`.
- **`FlashMessageBubble.kt` updated:**
  - Integrated `FlashFileMessageCard` iteration in message bubble body.
- **Unit test suite added (`FlashFileCardLogicTest.kt`):**
  - Tested byte size formatting across magnitude ranges, extension color resolution, and transfer state model integrity.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, 26 executed, all unit tests passing).

### Remaining
- UI-017 (Image message & grid) — NOT STARTED.
- UI-018 (Media viewer) — NOT STARTED.

### Next AI
Proceed with research and design for **UI-017 (Image Message & Grid Layout)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-20 — UI-015: Delivery / Read States — IMPLEMENTED

### Worked on
Researched, designed, and implemented **UI-015 (Delivery / Read States)** per `docs/ui/delivery-status.md` — animated delivery status glyphs (`FlashDeliveryStatusIcon`), custom checkmark/clock vector iconography (`flash_ic_clock.xml`, `flash_ic_check.xml`, `flash_ic_delivered.xml`, `flash_ic_read.xml`, `flash_ic_failed.xml`), and 5-stage transit lifecycle mapping (Pending, Sent, Delivered, Read, Failed) with 1-tap retry interaction.

### Changed
- **Research & Design Document created (`docs/ui/delivery-status.md`):**
  - Analyzed delivery status models across WhatsApp, Signal, Telegram, iMessage, and Discord.
  - Selected 5-stage checkmark iconography mapped to P2P local transport ACKs: Pending (Clock) $\to$ Sent (Single check) $\to$ Delivered (Double check) $\to$ Read (Teal Pulse Double check) $\to$ Failed (Red warning / retry).
  - Specified animated scale pop ($0.75f \to 1.0f$), 180ms smooth color morph to `accentPrimary` on read ACK, TalkBack announcements, and 1-tap retry for failed messages.
- **`FlashDeliveryStatusIcon.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - Animated state transition via `AnimatedContent` and `animateColorAsState`.
  - Clickable retry button on `FlashMessageStatus.Failed` with haptic feedback and TalkBack button semantics.
- **Vector drawables & icon registration added in `:ui:theme`:**
  - Added `flash_ic_clock.xml` and `flash_ic_check.xml`.
  - Registered `FlashIcons.Clock` and `FlashIcons.Check` in `FlashIcons.kt`.
- **`FlashMessageUi` model updated in `:core:messaging` (`FlashMessagingModels.kt`):**
  - Added `deliveryStatus: FlashMessageStatus? = null` field.
- **`FlashMessageBubble.kt` updated:**
  - Integrated `FlashDeliveryStatusIcon` inside `FlashMessageTimestampRow` for outgoing messages.
- **Unit test suite added (`FlashDeliveryStatusLogicTest.kt`):**
  - Tested 5 lifecycle states, message model status serialization/copying, and accessibility description mapping.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, 44 executed, all unit tests passing).

### Remaining
- UI-016 (File message card) — NOT STARTED.
- UI-017 (Image message) — NOT STARTED.

### Next AI
Proceed with research and design for **UI-016 (File Message Card)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-20 — UI-014: Typing Indicator — IMPLEMENTED

### Worked on
Researched, designed, and implemented **UI-014 (Typing Indicator)** per `docs/ui/typing-indicator.md` — 120 FPS GPU-accelerated 3-dot wave bouncing animation, incoming message stream typing bubble (`FlashTypingBubble`), and header subtitle status integration (`FlashHeaderTypingStatus`).

### Changed
- **Research & Design Document created (`docs/ui/typing-indicator.md`):**
  - Analyzed typing indicator mechanics across iMessage, Telegram, Signal, WhatsApp, Discord, and Slack.
  - Specified dual presentation model: concave incoming message bubble in list + animated subtitle status in chat header.
  - Specified 3-dot wave physics: phase-offset vertical translation ($-4\text{dp} \to 0\text{dp}$), scale pulse ($0.85 \to 1.15$), alpha pulse ($0.45 \to 1.0$) over 900ms loop period with 120ms phase offset per dot.
  - Specified `graphicsLayer` GPU execution with zero recompositions, TalkBack live region polite announcements, and static dot fallback for `reduceMotion`.
- **`FlashTypingIndicator.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - `FlashTypingIndicator` core 3-dot wave animation via `rememberInfiniteTransition`.
  - `FlashTypingBubble` container matching incoming bubble styling (`colors.chatBgIncoming`, `FlashShapes.bubbleGrouped`).
  - `FlashHeaderTypingStatus` header subtitle row with "typing" label and animated mini-dots.
- **`FlashChatHeader.kt` updated:**
  - Integrated `FlashHeaderTypingStatus` when `state.presence == FlashPeerPresence.Typing`.
- **`FlashMessageList.kt` updated:**
  - Added `peerTypingName` parameter and prepended `FlashTypingBubble` item to the reversed message stream.
- **`FlashConversationScreen.kt` updated:**
  - Wired header typing presence to `FlashMessageList.peerTypingName`.
- **Unit test suite added (`FlashTypingLogicTest.kt`):**
  - Tested typing presence mapping, typing bubble resolution, and accessibility descriptions.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, 17 executed, all unit tests passing).

### Remaining
- UI-015 (Delivery / read states) — NOT STARTED.
- UI-016 (File message card) — NOT STARTED.

### Next AI
Proceed with research and design for **UI-015 (Delivery / Read States)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-20 — UI-012: Custom Attachment Button & Palette — IMPLEMENTED

### Worked on
Researched, designed, and implemented **UI-012 (Custom Attachment Button & Palette)** per `docs/ui/attachment-button.md` — stateful composer attachment trigger with $45^\circ$ rotation micro-interaction, active accent tint, and sculpted modal bottom sheet action grid with categorized options (Gallery, Files, Camera, Audio, Flash P2P).

### Changed
- **Research & Design Document created (`docs/ui/attachment-button.md`):**
  - Compared attachment models across Telegram, Signal, WhatsApp, iMessage, and Discord.
  - Selected WhatsApp/Telegram-style Modal Bottom Sheet action palette paired with iMessage-style $45^\circ$ rotating attachment trigger.
  - Specified 5 core categories: Gallery (Cyan), Files (Indigo), Camera (Amber), Audio (Violet), and Flash Transfer (Teal Pulse P2P).
  - Specified staggered spring scale entrance, 0.90x press physics, TalkBack a11y, and IME soft keyboard safety.
- **`FlashAttachmentButton.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - Stateful attachment trigger with spring rotation ($0^\circ \to 45^\circ$), active accent tint animation, 0.88x touch press physics, and haptic feedback.
- **`FlashAttachmentSheet.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - Modal bottom sheet with `FlashShapes.radius24` top corners, subtle drag handle, and `FlowRow` action grid.
  - `FlashAttachmentTile` composable with 56dp vibrant circular icon container, subtle border, staggered spring scale-in, and 0.90x touch press scale.
- **`FlashComposer.kt` updated:**
  - Integrated `FlashAttachmentButton` with `isAttachmentExpanded` state.
- **`FlashConversationScreen.kt` updated:**
  - Added `showAttachmentSheet` state, passed `isAttachmentExpanded` to `FlashComposer`, and rendered `FlashAttachmentSheet` overlay.
- **Unit test suite added (`FlashAttachmentLogicTest.kt`):**
  - Tested 5 core attachment action categories, non-empty labels, valid icon specs, and container colors.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, 19 executed, all unit tests passing).

### Remaining
- UI-014 (Typing indicator) — NOT STARTED.
- UI-015 (Delivery / read states) — NOT STARTED.

### Next AI
Proceed with research and design for **UI-014 (Typing Indicator)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-20 — UI-010: Message Reply System — IMPLEMENTED

### Worked on
Researched, designed, and implemented **UI-010 (Reply System)** per `docs/ui/reply-system.md` — swipe-to-reply gesture with tactile reveal, in-bubble quoted reference cards with 1-tap jump to original message, 600ms pulse glow highlight, and composer reply dock integration.

### Changed
- **Research & Design Document created (`docs/ui/reply-system.md`):**
  - Analyzed swipe-to-reply mechanics across Telegram, Signal, WhatsApp, iMessage, and Slack.
  - Selected Telegram-style **Swipe Left** (inward drag) to eliminate collisions with Android 10–16 system edge-back navigation.
  - Specified 52dp threshold with logarithmic damping, rotating reply badge reveal, single-edge haptic trigger, 3dp vertical accent bar on in-bubble quote cards, and 600ms pulse highlight.
- **`FlashQuotedReplyUi` model added in `:core:messaging` (`FlashMessagingModels.kt`):**
  - Data class `FlashQuotedReplyUi(messageId, senderName, textSnippet, isMine)` added to `FlashMessageUi.replyTo`.
- **`FlashQuotedReplyCard.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - In-bubble quoted snippet with 3dp rounded vertical accent bar, bold sender name, 2-line snippet, high-contrast surface background, and 1-tap jump callback.
- **`FlashSwipeToReply.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - `FlashSwipeToReplyContainer` gesture wrapper with zero-recomposition GPU-accelerated drag, 52dp threshold, logarithmic rubber-banding resistance past 52dp, single-edge `LongPress` haptic trigger, rotating reply badge ($-35^\circ \to 0^\circ$), and spring snap-back.
- **`FlashMessageBubble.kt` updated:**
  - Embedded `FlashQuotedReplyCard`, wrapped bubble surface in `FlashSwipeToReplyContainer`, added `isHighlighted` animated pulse glow background and border.
- **`FlashMessageList.kt` updated:**
  - Added `onReplySwipe`, `onJumpToMessage`, and `highlightedMessageId` propagation.
- **`FlashConversationScreen.kt` updated:**
  - Integrated `listState.animateScrollToItem()` for jump-to-original navigation, `highlightedMessageId` auto-clearing after 700ms, and reply swipe routing to `FlashComposer`.
- **Unit test suite added (`FlashReplyLogicTest.kt`):**
  - Tested quoted metadata storage, reverseLayout jump index calculation, and quote snippet creation.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, 17 executed, all unit tests passing).

### Remaining
- UI-012 (Custom attachment button) — NOT STARTED.
- UI-014 (Typing indicator) — NOT STARTED.

### Next AI
Proceed with research and design for **UI-012 (Custom Attachment Button)** or **UI-014 (Typing Indicator)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-20 — UI-009: Message Reaction System — IMPLEMENTED

### Worked on
Researched, designed, and implemented **UI-009 (Reaction System)** per `docs/ui/reaction-system.md` — a hybrid architecture combining Telegram/Signal's spotlight quick reaction bar with Discord/Slack's frictionless 1-tap reaction chip toggling on message bubbles.

### Changed
- **Research & Design Document created (`docs/ui/reaction-system.md`):**
  - Compared Telegram, Signal, WhatsApp, iMessage, Discord, and Slack reaction mechanics.
  - Specified the Flash hybrid reaction pattern: floating quick bar in spotlight overlay + interactive bubble-docked chip row with 1-tap toggling.
  - Analyzed emoji rendering and licensing (Google Noto Color Emoji / EmojiCompat via Compose `Text` with zero added dependencies; rejected proprietary Apple/JoyPixels).
  - Specified layout geometry, `FlashMotion` animation curves (staggered spring entry, vertical odometer counter roll via `AnimatedContent`, scale press physics), and TalkBack a11y.
- **`FlashReaction` data model added in `:core:messaging` (`FlashMessagingModels.kt`):**
  - Immutable data class `FlashReaction(emoji, count, isSelfReacted, reactorIds)` updating `FlashMessageUi.reactions`.
- **`FlashReactionChip.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - Interactive pill chip with 1-tap toggle, long-press attribution trigger, active `accentPrimary` background tint & border for `isSelfReacted`, and vertical count roll odometer.
- **`FlashReactionsDock.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - Flow row docked to message bubbles with automatic alignment (end for outgoing, start for incoming) and `+N` overflow chip capping at 8 unique reactions.
- **`FlashMessageContextMenu.kt` updated:**
  - Upgraded `FlashQuickReactionsBar` with staggered spring entrance animation (`LaunchedEffect`), micro-press physics, and trailing `+` reaction trigger button.
- **`FlashMessageBubble.kt` & `FlashMessageList.kt` updated:**
  - Replaced legacy stub with `FlashReactionsDock` and wired `onToggleReaction` propagation.
- **`FlashConversationScreen.kt` updated:**
  - Added pure `toggleMessageReaction` state management updating reactions in realtime upon chip tap and quick bar selection.
- **Unit test suite added (`FlashReactionLogicTest.kt`):**
  - Tested new reaction addition, incrementing peer reactions, decrementing self-reactions, completely removing solo reactions, preserving sibling reactions, and non-target message isolation.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, 26 executed, all unit tests passing).

### Remaining
- UI-010 (Reply system) — NOT STARTED.
- UI-012 (Custom attachment button) — NOT STARTED.

### Next AI
Proceed with research and design for **UI-010 (Reply System)** or **UI-012 (Custom Attachment Button)** per `docs/ui/ui-research-index.md`.

---

## 2026-08-20 — UI-007/UI-008: Focus Overlay, Context Menu & Selection Mode — IMPLEMENTED

### Worked on
Implemented **UI-007 (Message press & selection mode)** and **UI-008 (Focus overlay & context menu)** — the immersive long-press interaction from modern chat interfaces.

### Changed
- **`FlashConversationScreen.kt`** — Full rewrite to wire focus overlay and selection toolbar:
  - `focusedMessage` state drives `FlashMessageFocusOverlay` display.
  - `selectedMessageIds` state drives `FlashSelectionToolbar` swap via `AnimatedContent`.
  - `BackHandler` exits selection mode before navigating back.
  - `replyingToMessage` state wired to `FlashComposer`'s `FlashReplyDock`.
  - Clipboard copy via Android `ClipboardManager` for single & multi-select.
- **`FlashMessageContextMenu.kt`** — Fixed shape tokens (`bubbleOutgoingTail`/`bubbleIncomingTail`) and spacing (`space8`).
- **`FlashMessageBubble.kt`** — Fixed `avatarInline` → `avatarXs`, fixed bubble shape mapping to use existing `FlashShapes` tokens (`bubbleOutgoingTail`, `bubbleIncomingTail`, `bubbleGrouped`).
- **`FlashSelectionLogicTest.kt`** — New unit tests for toggle selection, selection mode detection, and clipboard text formatting.
- **`ui/chat/build.gradle.kts`** — Added `activity-compose` dependency for `BackHandler`.

### Verification
- Full multi-module build: `testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL** (354 tasks, 17 executed).
- All unit tests pass (including new `FlashSelectionLogicTest`).

### Remaining
- UI-009 (Reaction system) — NOT STARTED.
- UI-010 (Reply system) — NOT STARTED.

### Next AI
Proceed to UI-009 Reaction System research and implementation.

---

## 2026-08-20 — UI-007: Message Press & Selection Mode Research & Design Complete

### Worked on
Executed the research, architecture, visual specification, interaction design, and animation mechanics for **UI-007 (Message press and selection mode)** per `docs/ui/selection-mode.md`.

### Changed
- **Research & Design Document created (`docs/ui/selection-mode.md`):**
  - Marked status as **DESIGNED**.
  - Analyzed Telegram, Signal, WhatsApp, and iMessage message selection mechanics.
  - Specified the Flash Contextual Selection Toolbar architecture replacing `FlashChatHeader` via `AnimatedContent(motion.statusCrossfade())`.
  - Defined bubble selection surface treatment (`BorderStroke(1.5.dp, colors.accentPrimary)`, translucent 12% Pulse wash, and single-tap toggle behavior during selection mode).
  - Specified action bar controls (Selection Count, Reply, Copy to clipboard, Forward, Delete, Close) with TalkBack a11y labels and `BackHandler` dismissal.
- **Updated `docs/ui/ui-research-index.md`:**
  - Upgraded UI-007 status to **DESIGNED**.

### Remaining
- Implement `FlashSelectionToolbar.kt` and update `FlashBubbleSurface` + `FlashConversationScreen` with multi-select state management in `:ui:chat`.
- Add unit and preview tests for selection mode.
- Verify multi-module build.

### Next AI
Implement UI-007 in `:ui:chat` per `docs/ui/selection-mode.md`.

## 2026-08-20 — UI-011 / UI-013: Custom Message Composer & Send Button Implementation

### Worked on
Implemented **UI-011 (Custom message composer)** and **UI-013 (Custom send button)** in `:ui:chat` according to the design specification in `docs/ui/composer.md`.

### Changed
- **`FlashComposer.kt` implemented in `:ui:chat` (`com.transfer.flash.ui.chat`):**
  - Replaced provisional draft with production-grade adaptive pill composer.
  - Multi-line `BasicTextField` expansion (1 to 6 lines, 20dp to 120dp height bounding) inside a clipped `FlashShapes.composerInput` pill with subtle border and `SolidColor(colors.accentPrimary)` cursor.
  - IME keyboard synchronization via `Modifier.imePadding()` preventing keyboard overlap.
  - Integrated `FlashReplyDock` supporting reply-to previews with accent vertical indicator and single-tap dismiss action.
  - Integrated `FlashSendButton` with tactile micro-press physics (`animateFloatAsState` scaling to 0.90x on press), stateful color transitions (`animateColorAsState` into `colors.accentPrimary` on valid draft), and TalkBack semantics.
  - Attachment action trigger with 40dp bounding touch target and semantic accessibility descriptions.
- **Unit test suite added:**
  - `ui/chat/src/test/java/com/transfer/flash/ui/chat/FlashComposerLogicTest.kt` verifying draft validation, enabled state gating, and whitespace trimming.
- **Updated documentation:**
  - Upgraded `docs/ui/composer.md` and `docs/ui/ui-research-index.md` status to **`IMPLEMENTED`**.

### Verification
- `testDebugUnitTest` & `assembleDebug` — BUILD SUCCESSFUL (2m 24s); all 64 library unit tests passing (`:core:common`: 15, `:core:security`: 7, `:core:discovery`: 2, `:core:network`: 15, `:core:transfer`: 8, `:core:messaging`: 5, `:ui:theme`: 4, `:ui:chat`: 8 tests).
- All previews compile and render cleanly (`Empty`, `Typing`, `Replying`).

### Remaining
- Next sequential UI component per roadmap: **UI-007 Message press & selection** / **UI-010 Reply system** / **UI-012 Custom attachment button**.

### Next AI
Proceed with research and design for the next sequential component (e.g., UI-007 / UI-010 / UI-012) per `docs/ui/ui-research-index.md`.

## 2026-08-20 — UI-011 / UI-013: Message Composer & Send Button Research & Design Complete

### Worked on
Resumed the Flash Premium Chat UI component roadmap. Completed the research, visual specification, interaction model, and animation architecture for **UI-011 (Custom message composer)** and **UI-013 (Custom send button)**.

### Changed
- **Research & Design Document created (`docs/ui/composer.md`):**
  - Marked status as **DESIGNED**.
  - Documented clean-room study of Telegram, Signal, WhatsApp, and iMessage composer mechanisms.
  - Formulated the Flash Adaptive Pill Composer architecture with integrated contextual dock (docked reply/edit preview bar, attachment trigger, expanding `BasicTextField` capped at 6 lines, and tactile `FlashSendButton`).
  - Specified layout tokens, touch targets, IME keyboard integration (`imePadding`), TalkBack a11y labels, dark mode palette, and `FlashMotion` animation curves/springs.
- **Updated `docs/ui/ui-research-index.md`:**
  - Upgraded UI-011 and UI-013 status to **DESIGNED**.

### Remaining
- Implement `FlashComposer.kt` and `FlashSendButton.kt` in `:ui:chat` according to the design specification.
- Add Compose unit/preview tests for the new composer states.
- Verify on physical device with software keyboard interaction.

### Next AI
Implement `FlashComposer.kt` and `FlashSendButton.kt` in `:ui:chat` per `docs/ui/composer.md`.

## 2026-08-20 — Phase K & L: Rewire `:app` Showcase & Quality Gate + Migration Complete

### Worked on
Executed Phase K (Rewire `:app` Showcase & Quality Gate) and Phase L (Migration Wrap-up & Quality Gate sign-off) of the Library-First Migration Plan.

### Changed
- **`:app` showcase dependency rewiring verified:**
  - `app/build.gradle.kts` depends strictly on library modules (`:core:common`, `:core:security`, `:core:discovery`, `:core:network`, `:core:transfer`, `:core:messaging`, `:ui:theme`, `:ui:chat`, `:ui:transfer`).
  - `MainActivity.kt` cleanly imports and composes library composables (`FlashChatListScreen`, `FlashConversationScreen`, `WsTransferScreen`, `FlashIconSheet`, `FlashMotionSheet`) and repositories (`SampleFlashChatRepository`).
- **Complete multi-module quality gate verified:**
  - Full build & test suite across all 10 modules:
    1. `:core:common` (`com.transfer.flash:core-common:1.0.0`) — 15 tests
    2. `:core:security` (`com.transfer.flash:core-security:1.0.0`) — 7 tests
    3. `:core:discovery` (`com.transfer.flash:core-discovery:1.0.0`) — 2 tests
    4. `:core:network` (`com.transfer.flash:core-network:1.0.0`) — 15 tests
    5. `:core:transfer` (`com.transfer.flash:core-transfer:1.0.0`) — 8 tests
    6. `:core:messaging` (`com.transfer.flash:core-messaging:1.0.0`) — 5 tests
    7. `:ui:theme` (`com.transfer.flash:ui-theme:1.0.0`) — 4 tests
    8. `:ui:chat` (`com.transfer.flash:ui-chat:1.0.0`) — 6 tests
    9. `:ui:transfer` (`com.transfer.flash:ui-transfer:1.0.0`)
    10. `:app` — Runnable showcase application
  - Total unit tests: 62 library unit tests passing.
  - Zero circular dependencies; strictly unidirectional architecture graph.
- **Architectural Migration Status:** COMPLETE. Flash is now fully structured as a suite of publishable, modular libraries with a clean runnable showcase.

### Verification
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL (1m 30s); 354 Gradle tasks executed/up-to-date, all 62 library unit tests green.
- All 6 quality gates passed across all modules.

### Remaining / Next Phase
- Resume Flash Premium Chat UI roadmap starting with **UI-011 Custom message composer** research in `docs/ui/composer.md`.

## 2026-08-20 — Phase J: Extract `:ui:chat` and `:ui:transfer`

### Worked on
Executed Phase J (Extract `:ui:chat` and `:ui:transfer`) of the Library-First Migration Plan.

### Changed
- **`:ui:chat` module created (`com.transfer.flash:ui-chat:1.0.0`):**
  - `ui/chat/build.gradle.kts` — Android library with `maven-publish`, Compose compiler, namespace `com.transfer.flash.ui.chat`, depends on `:core:common`, `:core:messaging`, `:ui:theme`.
  - Migrated all chat composables: `FlashChatListScreen.kt`, `FlashChatListRow.kt`, `FlashChatListTopBar.kt`, `FlashConversationScreen.kt`, `FlashChatHeader.kt`, `FlashMessageList.kt`, `FlashMessageBubble.kt`, `FlashMessageActionsSheet.kt`, `FlashComposer.kt`, `FlashAttachmentGrid.kt`, `FlashReactionsRow.kt`.
  - Migrated `FlashMessageInsertionTest.kt` (6 tests) to `ui/chat/src/test/`.
- **`:ui:transfer` module created (`com.transfer.flash:ui-transfer:1.0.0`):**
  - `ui/transfer/build.gradle.kts` — Android library with `maven-publish`, Compose compiler, namespace `com.transfer.flash.ui.transfer`, depends on `:core:common`, `:core:security`, `:core:network`, `:core:transfer`, `:ui:theme`.
  - Migrated `WsTransferScreen.kt`.
  - Updated imports from `com.transfer.flash.wstransfer.*` to `com.transfer.flash.core.transfer.model.*`.
- **WS transfer UI models extracted to `:core:transfer`:**
  - Created `core/transfer/src/main/java/.../model/WsTransferModels.kt` containing `WsTransferDirection`, `WsTransferStatus`, `WsPeer`, `WsTransferItem`, `WsDiscoveredDevice`, `WsTransferUiState`.
  - Removed duplicate model definitions from `WsTransferManager.kt` in `:app`; added imports from `:core:transfer`.
- **`:app` module cleanup:**
  - Deleted `app/src/main/java/com/transfer/flash/ui/chat/` directory (12 files).
  - Deleted `app/src/main/java/com/transfer/flash/ui/transfer/` directory (1 file).
  - Deleted `app/src/test/java/com/transfer/flash/ui/chat/` directory (1 file).
  - Added `implementation(project(":ui:chat"))` and `implementation(project(":ui:transfer"))` to `app/build.gradle.kts`.
  - Added `:ui:chat` and `:ui:transfer` to `settings.gradle.kts`.

### Verification
- `testDebugUnitTest` & `assembleDebug` — BUILD SUCCESSFUL (1m 37s); all unit tests green:
  - `:core:common` — 15 tests
  - `:core:security` — 7 tests
  - `:core:discovery` — 2 tests
  - `:core:network` — 15 tests
  - `:core:transfer` — 8 tests
  - `:core:messaging` — 5 tests
  - `:ui:theme` — 4 tests
  - `:ui:chat` — 6 tests (`FlashMessageInsertionTest`)
  - `:app` — all tests green

### Problems
- WsTransferScreen imported `WsPeer`, `WsTransferItem`, etc. from `com.transfer.flash.wstransfer` (`:app` internal). Required extracting WS transfer UI models to `:core:transfer:model` and updating imports.
- `:ui:transfer` was missing `activity-compose` and `material-icons-extended` dependencies. Added both.

### Remaining
- Phase K: Rewire `:app` Showcase & Quality Gate.
- Phase L: Resume UI Roadmap (UI-011 Composer).

### Next AI
Implement Phase K per migration plan checklist.

## 2026-08-20 — Phase I: Extract `:ui:theme`

### Worked on
Executed Phase I (Extract `:ui:theme`) of the Library-First Migration Plan.

### Changed
- **`:ui:theme` module created (`com.transfer.flash:ui-theme:1.0.0`):**
  - `ui/theme/build.gradle.kts` — Android library with `maven-publish`, Compose compiler plugin enabled, namespace `com.transfer.flash.ui.theme`, depends on `:core:common` and Jetpack Compose BOM.
  - `ui/theme/consumer-rules.pro` & `ui/theme/proguard-rules.pro`.
  - Added `:ui:theme` to `settings.gradle.kts`.
  - **Design tokens & theme (`ui:theme:theme`):**
    - `FlashTheme.kt` — Public Compose theme wrapper with dynamic accent support.
    - `FlashColors.kt` — Semantic color palettes (light, dark, dynamic accent tinting).
    - `FlashTypography.kt` — Typography tokens.
    - `FlashShapes.kt` — Shape tokens including concave `FlashBubbleShape`.
    - `FlashSpacing.kt` — 4dp/8dp grid spacing system.
    - `FlashDimensions.kt` — Standard layout measurements.
    - `FlashElevation.kt` — Surface elevation tokens.
    - `FlashMotion.kt` & `FlashMotionSheet.kt` — Motion curves, springs, and reduce-motion probe.
    - `FlashThemeSwatches.kt` — Design token visualization swatches.
    - `Theme.kt`, `Color.kt`, `Type.kt` — Material3 bridge theme (`FlashMaterialTheme`).
  - **Icon system & avatar (`ui:theme:icons`, `ui:theme:avatar`):**
    - `FlashIcons.kt` — 35+ typed icon accessors (`flash_ic_*`), `FlashIconSpec`, `FlashIcon` composable.
    - `FlashIconSheet.kt` — Icon sheet preview grid.
    - `FlashAvatar.kt` — Avatar composable with seed-based background generation.
    - `ui/theme/src/main/res/drawable/` — 44 Flash vector drawables (`flash_ic_*.xml`).
  - **Unit tests:**
    - `FlashThemeTokensTest.kt` — 4 tests: light color tokens, dark color tokens, spacing tokens positive, dimensions tokens positive.
- **`:app` module cleanup & refactoring:**
  - Added `implementation(project(":ui:theme"))` to `app/build.gradle.kts`.
  - Deleted deprecated `ui/design/` compatibility layer from `:app`.
  - Deleted migrated `ui/theme/`, `ui/icons/`, `ui/chat/FlashAvatar.kt`, and `flash_ic_*.xml` drawables from `:app`.
  - Updated all composables in `:app` (`FlashChatHeader.kt`, `FlashChatListRow.kt`, `FlashChatListScreen.kt`, `FlashConversationScreen.kt`, `FlashMessageBubble.kt`, `FlashMessageList.kt`, `MainActivity.kt`) to consume tokens and icons directly from `:ui:theme`.

### Verification
- `testDebugUnitTest` & `assembleDebug` — BUILD SUCCESSFUL (2m 7s); all unit tests green:
  - `:core:common` — 15 tests
  - `:core:security` — 7 tests
  - `:core:discovery` — 2 tests
  - `:core:network` — 15 tests
  - `:core:transfer` — 8 tests
  - `:core:messaging` — 5 tests
  - `:ui:theme` — 4 tests
  - `:app` — all tests green (total: 56 library unit tests)
- All 6 quality gates passed: Build ✓, API ✓, Dependency ✓ (unidirectional `:app` → `:ui:theme` → `:core:common`), Test ✓, Behavior ✓, Documentation ✓.

### Remaining
- Phase J: Extract `:ui:chat` and `:ui:transfer`.
- Phase K: Rewire `:app` Showcase & Quality Gate.
- Phase L: Resume UI Roadmap (UI-011 Composer).

### Next AI
Implement Phase J (`:ui:chat` and `:ui:transfer`) per migration plan checklist.

## 2026-08-20 — Phase H: Extract `:core:messaging`

### Worked on
Executed Phase H (Extract `:core:messaging`) of the Library-First Migration Plan.

### Changed
- **`:core:messaging` module created (`com.transfer.flash:core-messaging:1.0.0`):**
  - `core/messaging/build.gradle.kts` — Android library with `maven-publish`, namespace `com.transfer.flash.core.messaging`, depends on `:core:common`, `:core:security`, and `:core:network`, zero Compose dependencies.
  - `core/messaging/consumer-rules.pro` & `core/messaging/proguard-rules.pro`.
  - Added `:core:messaging` to `settings.gradle.kts`.
  - **Public domain contracts & models (`core:messaging:model`):**
    - `FlashChatRepository.kt` — High-level messaging repository contract and `SampleFlashChatRepository` implementation.
    - `FlashMessagingModels.kt` — Domain models: `FlashMessageId`, `FlashConversationId`, `FlashMessageStatus`, `FlashMessageGroupPosition`, `FlashListPreviewDelivery`, `FlashNetworkTransport`, `FlashAttachment`, `FlashMessage`, `FlashMessageUi`, `FlashChatListItemUi`, `FlashChatListUiState`, `FlashChatHeaderUiState`, `FlashConversationUiState`, `FlashConversation`, `FlashConversationDetail`.
  - **Messaging utilities (`core:messaging:util`):**
    - `FlashMessagingUtils.kt` — `computeMessageGroupPositions`, `sortedChatListItems`, `sampleFlashChatListState`, `sampleFlashConversationState`, `sampleDirectChatHeader`, `chatListRowContentDescription`.
  - **Unit tests:**
    - `FlashMessageGroupingTest.kt` — 5 tests: single message, consecutive same sender (TOP/MIDDLE/BOTTOM), sender change grouping break, same name but different direction separation, sender header on incoming group start.
- **`:app` module refactoring:**
  - Added `implementation(project(":core:messaging"))` to `app/build.gradle.kts`.
  - Updated UI composables (`FlashChatHeader.kt`, `FlashChatListRow.kt`, `FlashChatListScreen.kt`, `FlashConversationScreen.kt`, `FlashMessageBubble.kt`, `FlashMessageList.kt`, `MainActivity.kt`) to import from `com.transfer.flash.core.messaging.*`.
  - Fixed cross-module public property smart-cast in `FlashChatHeader.kt`.
  - Deleted duplicate source and test files (`FlashChatRepository.kt`, `FlashConversationModels.kt`, `FlashChatListModels.kt`, `FlashMessageGroupingTest.kt`) from `:app`.

### Verification
- `testDebugUnitTest` & `assembleDebug` — BUILD SUCCESSFUL (1m 25s); all unit tests green:
  - `:core:common` — 15 tests
  - `:core:security` — 7 tests
  - `:core:discovery` — 2 tests
  - `:core:network` — 15 tests
  - `:core:transfer` — 8 tests
  - `:core:messaging` — 5 tests (FlashMessageGroupingTest)
  - `:app` — all tests green (total: 52 core unit tests)
- All 6 quality gates passed: Build ✓, API ✓, Dependency ✓ (unidirectional `:app` → `:core:messaging` → `:core:network` → `:core:common`), Test ✓, Behavior ✓, Documentation ✓.

### Remaining
- Phase I: Extract `:ui:theme`.
- Phase J: Extract `:ui:chat` and `:ui:transfer`.
- Phase K: Rewire `:app` Showcase & Quality Gate.
- Phase L: Resume UI Roadmap (UI-011 Composer).

### Next AI
Implement Phase I (`:ui:theme`) per migration plan checklist.

## 2026-08-20 — Phase G: Extract `:core:transfer`

### Worked on
Executed Phase G (Extract `:core:transfer`) of the Library-First Migration Plan.

### Changed
- **`:core:transfer` module created (`com.transfer.flash:core-transfer:1.0.0`):**
  - `core/transfer/build.gradle.kts` — Android library with `maven-publish`, namespace `com.transfer.flash.core.transfer`, depends on `:core:common`, `:core:security`, and `:core:network`, zero Compose dependencies.
  - `core/transfer/consumer-rules.pro` & `core/transfer/proguard-rules.pro`.
  - Added `:core:transfer` to `settings.gradle.kts`.
  - **Public domain contracts:**
    - `FlashTransferRepository.kt` — Transfer repository interface (`activeTransfers`, `sendFile()`, `pauseTransfer()`, `resumeTransfer()`, `cancelTransfer()`).
    - `FlashTransfer.kt` — Domain models: `FlashTransferId` (value class), `FlashTransferDirection` (`Sending`, `Receiving`), `FlashTransferState` (`Offered`, `Queued`, `Transferring`, `Paused`, `Verifying`, `Completed`, `Failed`, `Cancelled`), `FlashTransfer`.
  - **Protocol framing (`core:transfer:protocol`):**
    - `WsTransferMessages.kt` — Message framing using `FlashTextFraming` (`HELLO`, `FILE_START`, `FILE_END`, `FILE_ACK`).
  - **Unit tests:**
    - `WsTransferMessagesTest.kt` — 6 tests: hello round trip, file start with special characters/spaces, file end, file ack, malformed message rejection, prefix separation.
    - `FlashTransferModelTest.kt` — 2 tests: transfer model defaults and state enum verification.
- **`:app` module refactoring:**
  - Added `implementation(project(":core:transfer"))` to `app/build.gradle.kts`.
  - Updated `WsTransferManager.kt` imports to use `com.transfer.flash.core.transfer.protocol.WsTransferMessages`.
  - Added `@file:OptIn(FlashInternalApi::class)` to `WsTransferManager.kt`.
  - Deleted migrated `WsTransferMessages.kt` and `WsTransferMessagesTest.kt` from `:app`.

### Verification
- `testDebugUnitTest` & `assembleDebug` — BUILD SUCCESSFUL (2m 16s); all unit tests green:
  - `:core:common` — 15 tests
  - `:core:security` — 7 tests
  - `:core:discovery` — 2 tests
  - `:core:network` — 15 tests
  - `:core:transfer` — 8 tests (6 WsTransferMessages + 2 model)
  - `:app` — all tests green (total: 47 core unit tests)
- All 6 quality gates passed: Build ✓, API ✓ (zero impl leaks in public contracts), Dependency ✓ (unidirectional `:app` → `:core:transfer` → `:core:network` → `:core:common`), Test ✓, Behavior ✓, Documentation ✓.

### Remaining
- Phase H: Extract `:core:messaging`.
- Phase I: Extract `:ui:theme`.
- Phase J: Extract `:ui:chat` and `:ui:transfer`.
- Phase K: Rewire `:app` Showcase & Quality Gate.
- Phase L: Resume UI Roadmap (UI-011 Composer).

### Next AI
Implement Phase H (`:core:messaging`) without changing existing transfer/network contracts.

## 2026-08-20 — Phase F: Extract `:core:network`

### Worked on
Executed Phase F (Extract `:core:network`) of the Library-First Migration Plan.

### Changed
- **`:core:network` module created (`com.transfer.flash:core-network:1.0.0`):**
  - `core/network/build.gradle.kts` — Android library with `maven-publish`, namespace `com.transfer.flash.core.network`, depends on `:core:common` and `:core:security`, zero Compose dependencies.
  - `core/network/consumer-rules.pro` & `core/network/proguard-rules.pro`.
  - Added `:core:network` to `settings.gradle.kts`.
  - **Public domain contracts:**
    - `FlashNetwork.kt` — High-level network engine interface (`networkState`, `activeSessions`, `start()`, `stop()`, `connect()`, `connectManual()`, `disconnect()`).
    - `FlashSession.kt` — Active bidirectional peer session interface (`peer`, `connectionState`, `transportType`, `send()`, `sendText()`, `disconnect()`).
    - `FlashNetworkState.kt` — Network state model (`isRunning`, `localPort`, `localAddresses`, `activePeerCount`).
    - `FlashConnectionState.kt` — Connection lifecycle enum (`Connecting`, `Connected`, `Disconnecting`, `Disconnected`, `Failed`).
  - **TCP engine (`core:network:tcp`):**
    - `LanProbeMessages.kt` — Protocol message encoding/decoding using `FlashTextFraming` from `:core:common`.
    - `LanProbeServer.kt` — TCP ServerSocket listener with accept loop.
    - `LanConnectionProbe.kt` — TCP client probe with ConnectivityManager socket binding.
    - `LanSession.kt` — Persistent TCP session with heartbeat, implementing `FlashSession`.
  - **WebSocket engine (`core:network:ws`):**
    - `WebSocketCodec.kt` — Pure-JVM RFC 6455 frame codec (no Android imports).
    - `WsConnection.kt` — WebSocket connection with read/write loops.
    - `WsTransferServer.kt` — WebSocket upgrade server.
    - `WsTransferClient.kt` — WebSocket upgrade client with LAN network binding.
  - **Utility (`core:network:util`):**
    - `LocalNetworkAddresses.kt` — IPv4 address enumeration via ConnectivityManager + NetworkInterface fallback.
  - **Unit tests:**
    - `FlashNetworkModelTest.kt` — State defaults and connection state enum tests.
    - `LanProbeMessagesTest.kt` — Hello/OK round-trip and malformed rejection tests.
    - `WebSocketCodecTest.kt` — 10 tests: RFC 6455 accept key, base64, masked/unmasked frames, fragmentation, ping/close, Unicode, HTTP headers, error rejection.
- **`:app` module refactoring:**
  - Added `implementation(project(":core:network"))` to `app/build.gradle.kts`.
  - Updated `LanController.kt` imports from `com.transfer.flash.network.*` to `com.transfer.flash.core.network.tcp.*` and `com.transfer.flash.core.network.util.*`.
  - Updated `LanController.kt` to use `session.peerInfo.deviceId` instead of `session.peer.deviceId` (peer is now `FlashDevice` from `FlashSession`).
  - Updated `WsTransferManager.kt` imports from `com.transfer.flash.network.*` and `com.transfer.flash.wstransfer.*` to `com.transfer.flash.core.network.*`.
  - **Deleted migrated source files** from `:app`: `LanConnectionProbe.kt`, `LanProbeMessages.kt`, `LanProbeServer.kt`, `LanSession.kt`, `LocalNetworkAddresses.kt`, `WebSocketCodec.kt`, `WsConnection.kt`, `WsTransferServer.kt`, `WsTransferClient.kt`, `WebSocketCodecTest.kt`, `LanProbeMessagesTest.kt`.
- **`:core:common` enhancement:**
  - Added `vararg` overload for `FlashTextFraming.encodeFields()` to support both list and vararg call sites.

### Verification
- `testDebugUnitTest` & `assembleDebug` — BUILD SUCCESSFUL (2m 46s); all unit tests green:
  - `:core:common` — 15 tests
  - `:core:security` — 7 tests
  - `:core:discovery` — 2 tests
  - `:core:network` — 15 tests (2 model + 3 LanProbeMessages + 10 WebSocketCodec)
  - `:app` — all tests green
- All 6 quality gates passed: Build ✓, API ✓ (zero impl leaks in public contracts), Dependency ✓ (unidirectional `:app` → `:core:network` → `:core:common` + `:core:security`), Test ✓, Behavior ✓, Documentation ✓.

### Problems
- `FlashTextFraming.encodeFields()` only accepted `List<Pair>` — callers in `:core:network` used vararg syntax. Fixed by adding vararg overload.
- `@FlashInternalApi` annotation on `FlashTextFraming` and `LanProbeMessages` required `@file:OptIn(FlashInternalApi::class)` on all internal consumers within `:core:network`.
- `LanSession` had conflicting `peer` property (both `LanProbeHello` getter and `FlashDevice` override). Fixed by removing the `LanProbeHello` getter and using `peerInfo` property instead.

### Remaining
- Phase G: Extract `:core:transfer`.
- Phase H: Extract `:core:messaging`.
- Phase I–L per migration plan checklist.

### Next AI
Implement Phase G (`:core:transfer`) without changing the existing network contracts.

## 2026-08-20 — Phase E: Extract `:core:discovery`

### Worked on
Executed Phase E (Extract `:core:discovery`) of the Library-First Migration Plan.

### Changed
- **`:core:discovery` module created (`com.transfer.flash:core-discovery:1.0.0`):**
  - `core/discovery/build.gradle.kts` — Android library with `maven-publish`, namespace `com.transfer.flash.core.discovery`, depends on `:core:common`, zero Compose dependencies.
  - `core/discovery/consumer-rules.pro` & `core/discovery/proguard-rules.pro`.
  - Added `:core:discovery` to `settings.gradle.kts`.
  - `FlashDiscovery.kt` — Public discovery interface contract (`state: StateFlow<FlashDiscoveryState>`, `discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>>`, `startDiscovery()`, `stopDiscovery()`, `startAdvertising(port)`, `stopAdvertising()`, `stopAll()`).
  - `FlashDiscoveryState.kt` — Public discovery state model (`isDiscovering`, `isAdvertising`, `advertisedPort`, `statusMessage`).
  - `FlashDiscoveredEndpoint.kt` — Discovered endpoint domain model wrapping `FlashDevice`, `hostAddress`, `port`, `serviceName`.
  - `NsdResolveQueue.kt` — Serialized resolver queue for Android `NsdManager` to eliminate concurrency crashes and lockups.
  - `NsdFlashDiscovery.kt` — Production implementation of `FlashDiscovery` for Android DNS-SD/mDNS with multicast lock handling, generation checks for stale callbacks, and dual support for LAN (`_flash-transfer._tcp.`) and WebSocket (`_flashws._tcp.`) service types.
  - `FlashDiscoveryModelTest.kt` — Unit tests covering state defaults and endpoint delegation.
- **`:app` module refactoring:**
  - Added `implementation(project(":core:discovery"))` to `app/build.gradle.kts`.
  - Refactored `LanDiscovery` in `:app` to delegate to `NsdFlashDiscovery` with LAN service type.
  - Refactored `WsDiscovery` in `:app` to delegate to `NsdFlashDiscovery` with WS service type.

### Verification
- `testDebugUnitTest` & `assembleDebug` — BUILD SUCCESSFUL (1m 49s); all unit tests in `:core:common` (15), `:core:security` (7), `:core:discovery` (2), and `:app` green.
- All 6 quality gates passed: Build ✓, API ✓ (zero impl leaks), Dependency ✓ (unidirectional `:app` -> `:core:discovery` -> `:core:common`), Test ✓, Behavior ✓ (existing LAN discovery and WS discovery intact), Documentation ✓.

### Remaining
- Phase F: Extract `:core:network` — move `LanSession`, `LanProbeServer`, `LanConnectionProbe`, `LocalNetworkAddresses`, `WebSocketCodec`, `WsConnection`, `WsTransferServer`, `WsTransferClient` behind `FlashNetwork` and `FlashSession`.
- Phase G–L per migration plan checklist.

### Next AI
Execute Phase F (`:core:network`) per `docs/architecture/library-first-migration-plan.md`.

---

## 2026-08-20 — Phase D: Extract `:core:security`

### Worked on
Executed Phase D (Extract `:core:security`) of the Library-First Migration Plan.

### Changed
- **`:core:security` module created (`com.transfer.flash:core-security:1.0.0`):**
  - `core/security/build.gradle.kts` — Android library with `maven-publish`, namespace `com.transfer.flash.core.security`, depends on `:core:common`, zero Compose dependencies.
  - `core/security/consumer-rules.pro` & `core/security/proguard-rules.pro`.
  - Added `:core:security` to `settings.gradle.kts`.
  - `FlashIdentity.kt` — Domain model representing local device identity (`deviceId: FlashDeviceId`, `friendlyName: String`).
  - `FlashIdentityStore.kt` — Interface contract for local persistent identity generation and display name updates.
  - `AndroidPreferencesIdentityStore.kt` — `SharedPreferences`-backed implementation maintaining 100% key compatibility with Flash 1.0 (`flash_identity`, `device_id`, `friendly_name`).
  - `FlashTrustStore.kt` — Interface contract for paired/trusted peer management (`isTrusted`, `trustPeer`, `revokeTrust`, `getTrustedPeers`).
  - `AndroidPreferencesTrustStore.kt` — `SharedPreferences`-backed implementation maintaining 100% key compatibility with Flash 1.0 (`flash_ws_pairing`, `paired_<deviceId>`).
  - `FakeSharedPreferences.kt` — Test utility for pure-JVM fast in-memory testing.
  - `FlashIdentityStoreTest.kt` — 4 unit tests covering generation, persistence, caching, and blank name validation.
  - `FlashTrustStoreTest.kt` — 3 unit tests covering trust registration, raw string overloads, revocation, and map inspection.
- **`:app` module refactoring:**
  - Added `implementation(project(":core:security"))` to `app/build.gradle.kts`.
  - Refactored `AppIdentity` to delegate to `AndroidPreferencesIdentityStore`.
  - Refactored `WsPairingStore` to delegate to `AndroidPreferencesTrustStore`.

### Verification
- `testDebugUnitTest` & `assembleDebug` — BUILD SUCCESSFUL (2m 8s); all unit tests in `:core:common` (15), `:core:security` (7), and `:app` green.
- All 6 quality gates passed: Build ✓, API ✓ (zero impl leaks), Dependency ✓ (unidirectional `:app` -> `:core:security` -> `:core:common`), Test ✓, Behavior ✓ (existing identity and pairing intact), Documentation ✓.

### Remaining
- Phase E: Extract `:core:discovery` — move `LanDiscovery` and `WsDiscovery` behind `FlashDiscovery`.
- Phase F–L per migration plan checklist.

### Next AI
Execute Phase E (`:core:discovery`) per `docs/architecture/library-first-migration-plan.md`.

---

## 2026-08-20 — Phase B+C: Gradle Infrastructure & `:core:common` Extraction

### Worked on
Executed Phase B (Gradle & Build Infrastructure Setup) and Phase C (Extract `:core:common`) of the Library-First Migration Plan.

### Changed
- **Phase B — Gradle Infrastructure:**
  - Added `android-library` plugin alias to `gradle/libs.versions.toml`.
  - Registered `android-library` in root `build.gradle.kts`.
  - Added `:core:common` to `settings.gradle.kts`.
- **Phase C — `:core:common` module created:**
  - `core/common/build.gradle.kts` — Android library with `maven-publish`, namespace `com.transfer.flash.core.common`, zero Compose dependencies.
  - `FlashAnnotations.kt` — `@FlashInternalApi` and `@FlashExperimentalApi` opt-in annotations.
  - `FlashDevice.kt` — Public domain model for discovered/connected peers.
  - `FlashDeviceId.kt` — Type-safe `@JvmInline value class` with blank-validation.
  - `FlashTransportType.kt` — Enum: LAN, WIFI_DIRECT, WEBSOCKET, RELAY, MESH, UNKNOWN + `fromString()`.
  - `FlashPeerPresence.kt` — Enum: Online, Offline, Typing, Connecting.
  - `FlashResult.kt` — Sealed `FlashResult<T>` (Success/Failure) + extension functions `map`, `flatMap`, `fold`, `onSuccess`, `onFailure`, `getOrNull`, `getOrElse`, `runCatching`.
  - `FlashError.kt` — Sealed error hierarchy: NetworkUnavailable, PeerUnavailable, ConnectionTimeout, ProtocolMismatch, TransferFailed, VerificationFailed, StorageError, Cancelled, Unknown.
  - `FlashTextFraming.kt` — Deduplicated protocol escape/unescape/encodeFields/parseFields (replaces duplicated logic in `LanProbeMessages` and `WsTransferMessages`).
  - `FlashResultTest.kt` — 7 unit tests covering Success/Failure accessors, map, flatMap, callbacks, fold, runCatching.
  - `FlashTextFramingTest.kt` — 4 unit tests: escape/unescape round-trip, encodeFields/parseFields round-trip, prefix mismatch, malformed pairs.
  - `FlashDeviceTest.kt` — 4 unit tests: DeviceId validation, blank-throws, equality/defaults, TransportType.fromString.
- Added `implementation(project(":core:common"))` to `:app/build.gradle.kts`.

### Verification
- `testDebugUnitTest` — BUILD SUCCESSFUL (1m 8s); all `:core:common` tests (15) and `:app` tests green.
- `assembleDebug` — BUILD SUCCESSFUL (3m 34s in Android Studio).
- All 6 quality gates passed: Build ✓, API ✓ (no impl leaks), Dependency ✓ (unidirectional), Test ✓, Behavior ✓ (existing features intact), Documentation ✓.

### Problems
- Initial `FlashResult` had operators as interface default methods; `Failure : FlashResult<Nothing>` caused `ClassCastException` at runtime when calling `getOrElse` on a Failure (JVM bridge method tried to cast Nothing to String). Fixed by moving all operators to top-level extension functions.
- Gradle configuration-cache lock contention when Android Studio daemon was running simultaneously. Fixed by using `--no-daemon --no-configuration-cache` for CLI builds.

### Remaining
- Phase D: Extract `:core:security` — move `AppIdentity` and `WsPairingStore` behind `FlashIdentity`/`FlashTrustStore`.
- Phase E–L per migration plan checklist.

### Next AI
Execute Phase D (`:core:security`) per `docs/architecture/library-first-migration-plan.md`. Use `--no-daemon --no-configuration-cache` for CLI builds when Android Studio is open.

---

## 2026-08-20 — Library-First Architectural Audit & Migration Plan

### Worked on
Conducted a deep, evidence-based architectural audit of the entire codebase and produced the comprehensive Library-First Migration Plan for transforming Flash into a suite of decoupled, standalone Android/Kotlin libraries under `com.transfer.flash:*` with `:app` as the showcase application.

### Changed
- Created `docs/architecture/audit.md` detailing current monolithic package structure, coupling analysis, code duplication patterns (NSD resolve queues, protocol escaping, socket routing), and technical debt.
- Created `docs/architecture/target-architecture.md` outlining the 9-module layered topology, architectural invariants, multi-transport abstraction, threading/lifecycle models, and error hierarchy.
- Created `docs/architecture/public-api.md` formalizing stable public domain contracts (`FlashDevice`, `FlashSession`, `FlashNetwork`, `FlashTransfer`, `FlashChatRepository`, `FlashResult`).
- Created `docs/architecture/library-first-migration-plan.md` delivering the 23-point migration strategy, class-by-class migration matrix, risk register, rollback plan, and phase-by-phase checklist.
- Verified baseline build status: `testDebugUnitTest` (24/24 tasks up-to-date / passing).

### Verification
- Full codebase static inspection across all 60 Kotlin source files, 7 unit tests, and build scripts.
- Verified that all unit tests execute and pass via Gradle.
- Confirmed zero Kotlin code modifications in Phase A per lead architect instructions.

### Remaining
- Execute Phase B: Add `android-library` plugin to `gradle/libs.versions.toml`, root `build.gradle.kts`, and configure `settings.gradle.kts`.
- Execute Phase C: Extract `:core:common`.

### Next AI
Begin Phase B and Phase C of `docs/architecture/library-first-migration-plan.md`.

---

## 2026-08-20 — Modular Multi-Library Architecture & Publishing Plan (ADR-008)

### Worked on
Planned and formalized the architectural transition of Flash from a single `:app` module into a suite of decoupled, standalone Android/Kotlin libraries under `com.transfer.flash:*` with independent hosting and publishing capability. Temporarily paused the Chat UI component sequence to complete this infrastructure upgrade.

### Changed
- Added ADR-008 to `docs/decisions.md` documenting the modular library suite decision, rationale, layer boundaries, and Maven publishing strategy.
- Created `docs/architecture-modular-libraries-plan.md` detailing the module topology, package mappings, Gradle publishing configuration, and step-by-step roadmap.
- Updated `docs/architecture.md` with the new modular library architecture overview and invariants.
- Updated `logs/handoff.md` with the active phase and roadmap steps.

### Verification
- Reviewed all module dependency boundaries to ensure zero Compose/UI dependencies in core engines and abstract repository interfaces in UI components.
- Verified Android Gradle Plugin and Maven Publish conventions for multi-module projects.

### Remaining
- Execute Step 1: Configure Gradle plugins, `libs.versions.toml`, and `settings.gradle.kts`.
- Execute Step 2: Extract core engine modules (`:core:common`, `:core:discovery`, `:core:network`, `:core:transfer`).
- Execute Step 3: Extract UI component modules (`:ui:theme`, `:ui:chat`, `:ui:transfer`).
- Execute Step 4: Refactor `:app` showcase and verify builds & tests.
- Execute Step 5: Verify `publishToMavenLocal` generation.
- Resume Premium Chat UI sequence (UI-011 Composer).

### Next AI
Proceed with Step 1 & 2 of `docs/architecture-modular-libraries-plan.md`.

---

### Worked on
Owner-requested side track (explicitly NOT part of the main design): checked whether WebSocket transfer existed (it did not — only the raw-TCP `LanSession` probe) and implemented an experimental WebSocket transfer path with multi-device pairing (3-device mesh capable) and simple file transfer.

### Changed
- Added `wstransfer/` package:
  - `WebSocketCodec.kt` — minimal hand-rolled RFC 6455 codec (upgrade handshake helpers, client masking, frame parse/serialize, continuation reassembly, ping/pong/close, own Base64 encoder so minSdk 24 + pure-JVM tests work). Zero new dependencies; OkHttp rejected (client-only, and only present in the Gradle cache from the reverted Stream experiment).
  - `WsTransferMessages.kt` — control text frames `FLASH_WS_HELLO` / `FLASH_FILE_START` / `FLASH_FILE_END` / `FLASH_FILE_ACK` with the same escaping as `LanProbeMessages`.
  - `WsConnection.kt` — post-handshake connection: IO read loop, lock-serialized frame writes, close/ping/pong handling.
  - `WsTransferServer.kt` — accepts WS upgrades on preferred port 45822 (dynamic fallback), 8 s handshake timeout.
  - `WsTransferClient.kt` — outbound connect + upgrade with the Wi-Fi/Ethernet `Network.socketFactory` routing fix from `LanConnectionProbe`.
  - `WsTransferManager.kt` — multi-peer registry keyed by deviceId (outbound connection preferred per peer, inbound kept as fallback and promoted on drop), self-connect guard, SAF file send (64 KiB binary frames, one active transfer per connection), receive to `filesDir/ws-received/` with deduped names + byte-count verification + `FLASH_FILE_ACK`, progress StateFlow.
- Added `ui/transfer/WsTransferScreen.kt` — start/stop server (shows own address), connect-by-IP (repeatable for multiple peers), paired-peer list with per-peer Send/Drop, "send to all", transfer progress list.
- `MainActivity.kt` — LAN home gained a "WebSocket transfer (experimental)" button and the new screen route; manager lifecycle tied to composition.
- Tests: `WebSocketCodecTest` (10 tests incl. the RFC 6455 reference accept-key vector, masked/unmasked/16-bit/64-bit round trips, fragmentation reassembly, header-then-frame stream continuity) and `WsTransferMessagesTest` (6 tests).
- Docs: ADR-007 in `docs/decisions.md`; experimental track section in `docs/protocol.md`.

### Verification
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL in 1m 27s; all unit tests green (both new test classes executed).
- Not yet device-tested: 3-device mesh pairing and a real file send between phones still need on-device verification.

### Problems
- None blocking. One new deprecation warning (`allNetworks` in `WsTransferClient.kt`) — same pattern already used by `LanConnectionProbe`/`LocalNetworkAddresses`, kept for consistency.

### Remaining
- Device test: 3 phones, each starting its server and connecting to the other two; send a file to one peer and broadcast to all; confirm ACK-verified completion and `ws-received/` output.
- If the track graduates: TLS (wss://), pairing/trust UX, resume, hash verification, foreground service for background transfers.

### Next AI
Device-test the WS transfer screen; do not merge this track with the main LAN protocol path without an ADR. Main-line work remains UI-011 composer research or UI-007 selection research.

## 2026-08-20 — UI-006 Message insertion animation

### Worked on
Research, design, and implementation of message insertion choreography (UI-006): reverse-layout list, sibling glide, Flash entrance for new tail messages, arrival-time scroll policy.

### Changed
- Completed UI-006 section of `docs/ui/message-bubble.md` (DESIGNED → IMPLEMENTED). Approaches studied: animateItem-only (A), per-item AnimatedVisibility with `messageEnter()` (B), chosen hybrid full-size slot + progress-driven content entrance (C). Sources: Telegram/Signal/WhatsApp/iMessage behavior, official `LazyItemScope.animateItem` API reference (verified 2026-08-20, stable since foundation 1.7; BOM 2025.12.00 → 1.9.x), M3 motion, Jetchat (Apache 2.0).
- `FlashMessageList.kt`: `LazyColumn(reverseLayout = true)` over `messages.asReversed()` (O(1) view; opens at bottom; key-anchored scroll stability), per-item `animateItem(fadeInSpec = null, placementSpec, fadeOutSpec)`, sticky birth-time entrance gating via first-composition id snapshot, at-bottom tracking (`derivedStateOf`), auto-scroll policy (own send || at bottom → scroll to layout 0; reduce-motion → instant). Pure internal helpers `shouldAnimateMessageEnter` / `shouldAutoScrollToNewMessage` / `isAtBottom`.
- `FlashMotion.kt`: + `rememberMessageEnterProgress(animate)` (one-shot 0→1, tween 200 Decelerate — the `messageEnter()` channels at its duration), `messagePlacementSpec()` (spring 0.90/400, snap under reduce-motion), `messageFadeOutSpec()`.
- `FlashDimensions.kt`: + `chatBottomStickThreshold = 48.dp`.
- Added `FlashMessageInsertionTest.kt` (6 unit tests).
- Index: UI-006 → IMPLEMENTED.

### Verification
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL (all tests green; two compile errors fixed: `VisibilityThreshold` extension import, `Animatable.asState()` return).
- Device (Samsung R5CN21CNJAF, dark theme): installed, opened group conversation — history renders bottom-anchored with no entrance animation (historical). Live send "UI-006 live send": message appended at tail, list auto-scrolled to bottom, previous outgoing bubble reclassified SINGLE→TOP (tail scoop moved to the new BOTTOM bubble) — `logs/screenshots/ui-006-conversation.png` (before) + `ui-006-after-send.png` (after). No layout jumps observed.

### Problems
- Device-test choreography: screen is 1080×2400 (not 1440×3200 as assumed); taps below the viewport silently missed. Resolved via `uiautomator dump` for exact composer/send bounds (`logs/ui-dump.xml`).
- Observed: provisional composer has no `imePadding`, so the keyboard covers it while typing — recorded as UI-011 input in `message-bubble.md` known limitations.

### Remaining
- Scrolled-up no-steal device check needs a long conversation (deferred to UI-021/UI-043; predicate unit-tested).
- Reduce-motion device gate (UI-038).

### Next AI
**UI-011** composer research (also owns the imePadding gap) or **UI-007** selection research — both docs NOT STARTED, so research → DESIGNED first. UI-008/009 remain blocked on UI-007.

## 2026-08-20 — UI-005 Message bubble system

### Worked on
Research, design, and implementation of the Flash message bubble system (UI-005): custom concave-tail geometry, sender-group rhythm, adaptive width, press feedback, metadata tokens.

### Changed
- Completed `docs/ui/message-bubble.md` (DESIGNED → IMPLEMENTED).
- Added `FlashBubbleShape` (custom `Shape`, concave cubic-Bézier "pulse scoop" tail, RTL-aware) + `bubbleTailSize` token in `ui/theme/FlashShapes.kt`; `bubbleIncomingTail`/`bubbleOutgoingTail` now use it.
- Added `chatTextTimestampOutgoing` token to `FlashColors` (light pulse700 / dark pulse300, contrast-checked).
- Rebuilt `FlashMessageBubble.kt`: `BoxWithConstraints` width (fraction + 320dp cap, no `LocalConfiguration`), group-position shape mapping, press scale 0.97 via `FlashMotion.springSnappySpec` (reduce-motion aware), outgoing in-bubble metadata row with reserved delivery slot (UI-015), incoming time in-bubble for direct chats, `semantics(mergeDescendants = true)`.
- `FlashMessageList.kt`: group-aware gaps (space4 inside a run, space12 between runs), `itemsIndexed` stable keys, `showSenderHeaders` parameter.
- `FlashConversationScreen.kt`: passes `showSenderHeaders = state.header.isGroup`.
- Deleted provisional `ui/design/FlashMessageStyling.kt` (absorbed into bubble).
- Added `FlashMessageGroupingTest.kt` (5 unit tests).
- Added ADR-006 (custom bubble geometry).
- Index: UI-005 → IMPLEMENTED.

### Verification
- Compose previews: light/dark group, direct, 1.5× font scale.
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL (unit tests green).
- Device (Samsung R5CN21CNJAF): `logs/screenshots/ui-005-bubbles-light.png` + `ui-005-bubbles-dark.png`; tails, borders, group rhythm, and timestamp token render correctly in both themes.

### Problems
- None blocking. Pre-existing `SwipeToDismissBoxState` deprecation warning in `FlashChatListRow.kt` (UI-003 code, untouched).

### Remaining
- UI-006 insertion animation (`animateItem` + `messageEnter` token ready).
- UI-007 press/selection choreography; UI-015 delivery slot content.
- RTL spot-check (UI-034).

### Next AI
**UI-006** message insertion animation, or **UI-011** composer research. Do not start UI-007/008 until their docs are DESIGNED.

## 2026-08-19 — UI-003 Chat list

### Worked on
Research, design, and implementation of Flash chat inbox (UI-003): custom rows, list screen, repository navigation.

### Changed
- Completed `docs/ui/chat-list.md` (IMPLEMENTED).
- Added `FlashChatListModels.kt`, `FlashChatListRow.kt`, `FlashChatListScreen.kt`, `FlashChatListTopBar.kt`.
- Extended `FlashChatRepository` with `chatListState`, selection, archive, `openConversation`/`closeConversation`.
- `MainActivity`: app opens to chat list; LAN home via connection icon in top bar.
- `FlashDimensions.chatListRowHeight`, unread badge size.
- Index: UI-003 → IMPLEMENTED.

### Verification
- Compose previews: list light/dark/selection, row variants.
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL.
- Device screenshot: `logs/screenshots/ui-003-chat-list.png`.

### Remaining
- UI-007 full selection action bar.
- UI-008 row context menu.
- UI-024 search wiring.
- `animateItem()` when Compose lazy API available in project.

### Next AI
**UI-005** message bubble system research + implementation.

## 2026-08-19 — UI-037 Motion design system

### Worked on
Research, design, and implementation of centralized Flash motion tokens (UI-037). First consumer: chat header status crossfade.

### Changed
- Completed `docs/ui/motion-system.md` (IMPLEMENTED).
- Added `FlashMotion.kt` — duration tiers, easing, springs, named transitions, reduce-motion probe.
- Added `FlashMotionSheet.kt` QA demo; `FlashTheme.motion` CompositionLocal.
- Migrated `FlashChatHeader` status line to `motion.statusCrossfade()`.
- LAN home: "Motion sheet (QA)" button in `MainActivity`.
- Index: UI-037 → IMPLEMENTED.

### Verification
- Compose previews: motion sheet light/dark/reduce-motion; header previews unchanged.
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL.
- Device: conversation header visible with Flash motion integration (`ui-004-chat-header.png` recaptured ~235 KB).

### Remaining
- UI-038 reduced-motion TalkBack pairing.
- UI-039–UI-041 haptics/sound/micro-interactions.
- Wire `screenTransition`, `messageEnter` when UI-003/005/033 land.

### Next AI
**UI-003** chat list or **UI-005** message bubble research + implementation (both unblocked by UI-037).

## 2026-08-19 — UI-004 Chat header

### Worked on
Research, design, and implementation of `FlashChatHeader` (UI-004). Replaced provisional center-title `FlashChannelHeader`.

### Changed
- Completed UI-004 section of `docs/ui/chat-screen.md` (VERIFIED).
- Added `FlashChatHeader.kt`, `FlashChatHeaderUiState`, `FlashPeerPresence`, `FlashNetworkTransport`.
- Refactored `FlashConversationUiState` to nested `header` model.
- Removed `FlashChannelHeader.kt`.
- Updated `FlashConversationScreen` to use `FlashChatHeader`.
- Index: UI-004 → VERIFIED.

### Verification
- Compose previews: group, direct, typing, dark.
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL.
- Device screenshot: `logs/screenshots/ui-004-chat-header.png`.

### Remaining
- UI-021 scroll-linked header collapse.
- UI-030 live transport from LAN session.
- UI-031 full encryption trust UX.

### Next AI
UI-005 message bubble research or UI-003 chat list.

## 2026-08-19 — UI-002 Custom icon system

### Worked on
Flash-owned MVP icon set: 35+ vector drawables, typed `FlashIcons` registry, `FlashIcon` composable with state tints, QA icon sheet.

### Changed
- Completed `docs/ui/icon-system.md` (IMPLEMENTED).
- Added `ui/icons/FlashIcons.kt`, `FlashIconSheet.kt`.
- Added/updated `res/drawable/flash_ic_*.xml` for MVP chat + P2P icons.
- Migrated provisional chat composables from `ui/chat/FlashIcons.kt` to `ui/icons/`.
- Added LAN home "Icon sheet (QA)" entry + device back navigation.
- Updated `ui-research-index.md` UI-002 → IMPLEMENTED.

### Verification
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL.
- Compose previews: light/dark icon sheet.
- Device screenshot: `logs/screenshots/ui-002-icon-sheet.png`.

### Next AI
UI-037 motion system, or UI-003 chat list research.

## 2026-08-19 — UI-001 Visual identity & design system

### Worked on
Research and implementation of Flash Pulse design system (UI-001). Documented light/dark palettes, typography, spacing, shapes, elevation, surfaces, dynamic color policy, dark theme principles.

### Changed
- Completed `docs/ui/design-system.md` (status IMPLEMENTED).
- Added `ui/theme/`: `FlashTheme`, `FlashColors`, `FlashTypography`, `FlashSpacing`, `FlashShapes`, `FlashDimensions`, `FlashElevation`, `FlashThemeSwatches`.
- Renamed LAN Material wrapper to `FlashMaterialTheme` in `Theme.kt`.
- Deprecated provisional `ui/design/*` with wrappers pointing to `ui/theme/`.
- Migrated provisional chat composables to `FlashTheme` tokens.
- Added ADR-005 (Flash Pulse identity).
- Updated `ui-research-index.md` UI-001 → IMPLEMENTED.

### Verification
- Compose `@Preview`: light, dark, and 1.5× font scale swatches in `FlashThemeSwatches.kt`.
- `testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL.
- APK installed on Samsung R5CN21CNJAF; device screenshots: `logs/screenshots/ui-001-conversation-light.png`, `ui-001-conversation-dark.png`.

### Remaining
- UI-037 `FlashMotion` tokens.
- UI-002 custom icon system.
- Owner visual acceptance of new teal palette vs old Stream-look scaffold.
- UI-035 dark theme device QA matrix; UI-036 dynamic accent user setting.

### Next AI
Start UI-037 motion system per `docs/ui/motion-system.md`. Do not reimplement bubbles/composer until UI-005/UI-011 research docs are DESIGNED.

## 2026-08-18 - Persistent LAN session

### Worked on
Replaced one-shot connected probes with persistent LAN sessions.

### Changed
- Added `LanSession`, which owns a live TCP socket.
- `Connect` now keeps the socket open after `FLASH_HELLO` / `FLASH_OK`.
- Added `FLASH_PING` and `FLASH_PONG` heartbeat messages.
- `Disconnect` now closes the live session and sends `FLASH_DISCONNECT`.
- Manual IP/port connection now creates a persistent session too.
- Heartbeat/read failure clears connected state.

### Verification
- Not built or retested. The project owner requested not to run builds after changes.

### Problems
- The old probe connected, exchanged one message, closed immediately, and left the UI pretending the peer was still connected.
- Disconnect state could drift between phones because there was no live socket.

### Fix
Use a persistent TCP session as the source of connected state.

### Remaining
- Build/install when allowed.
- Confirm both phones stay connected while both apps are open.
- Confirm Disconnect updates both phones.
- Confirm closing/stopping one phone causes heartbeat/read failure and clears state on the other.

### Next AI
Layer pairing and transfer request messages onto `LanSession` instead of creating another socket path.

## 2026-08-18 - Peer disconnect notification

### Worked on
Made explicit disconnect state propagate to the other phone.

### Changed
- Added `FLASH_DISCONNECT` protocol message.
- Added outbound disconnect notification in `LanConnectionProbe`.
- Added inbound disconnect handling in `LanProbeServer`.
- `LanController` now clears peer connection state when receiving a disconnect message.
- Service-lost callbacks now clear connection state for the lost peer.

### Verification
- Not built or retested. The project owner requested not to run builds after changes.

### Problems
- Disconnect previously only cleared local UI state.
- If one phone stopped LAN, the other phone could keep showing old connected/disconnect state until app state reset.

### Fix
Notify the peer on explicit Disconnect and clear state when NSD reports the peer service is lost.

### Remaining
- Build/install when allowed.
- Confirm explicit Disconnect updates both phones.
- Confirm Stop LAN on one phone clears the peer state on the other after NSD service-lost arrives.

### Next AI
Move from probe/disconnect messages to a real persistent session before file transfer.

## 2026-08-18 - Disconnect UI and state reset

### Worked on
Fixed stale connected state after stopping and restarting LAN.

### Changed
- `stopLan()` now clears `connectionStates`, manual connection state, manual result text, and last probe result.
- `startLan()` resets old connection state before starting discovery.
- Added device disconnect action.
- Added manual disconnect action.
- Connected buttons now show `Disconnect` and are clickable.

### Verification
- Not built or retested. The project owner requested not to run builds after changes.

### Problems
- Previously, connected state survived Stop/Start because only the device list was cleared.
- The `Connected` button was disabled, so there was no way to reset one peer row manually.

### Fix
Treat the current connected state as transient probe/session UI state and clear it on LAN restart/stop. Provide explicit disconnect controls.

### Remaining
- Build/install when allowed.
- Confirm Stop LAN clears all connected states.
- Confirm reconnect works after pressing Disconnect.

### Next AI
When replacing probes with persistent sessions, make Disconnect close the actual socket/session instead of only clearing UI state.

## 2026-08-18 - Inbound connected state

### Worked on
Made the receiving phone update its UI when it answers a LAN probe.

### Changed
- `LanProbeServer` now accepts an `onPeerProbed` callback.
- `LanController` marks the inbound peer as `CONNECTED` when a valid `FLASH_HELLO` is received and answered.
- If the inbound peer is not already in the discovered-device list, the controller adds a temporary inbound peer row.

### Verification
- Not built or retested. The project owner requested not to run builds after changes.

### Problems
- Previously, only the phone that tapped Connect changed to `Connected`; the responding phone logged the probe but did not update UI state.

### Fix
Propagate successful inbound probe events from the probe server to UI state.

### Remaining
- Build/install when allowed.
- Confirm both phones show `Connected` after one side taps Connect.

### Next AI
Replace this short-lived probe with a real session manager before implementing file transfer.

## 2026-08-18 - LAN connect routing fix

### Worked on
Diagnosed why discovered/manual LAN connections timed out.

### Changed
- Updated `LanConnectionProbe` to create sockets through the active Wi-Fi/Ethernet `Network` instead of a plain default-network `Socket`.
- Logged the network handle used for LAN probe attempts.
- Recorded the routing failure in `logs/errors.md`.

### Verification
- Not built or retested. The project owner explicitly requested not to run a build after this change.

### Problems
- Log showed Android trying to connect to peer `10.1.97.57:46589` from local address `10.177.173.19`, which is not the same LAN.

### Fix
Use `ConnectivityManager` and `Network.socketFactory` so the outbound probe goes through Wi-Fi/Ethernet.

### Remaining
- Build/install from Android Studio or when the owner allows it.
- Retest connection and confirm the source address is now on the same subnet as the peer.

### Next AI
If connection still fails, inspect the new `LAN probe connecting ... network=...` and `LAN probe failed ...` log lines, then verify both devices show manual addresses on the same subnet.

## 2026-08-18 - Stable LAN probe port

### Worked on
Diagnosed `ECONNREFUSED` after LAN routing was fixed.

### Changed
- `LanProbeServer` now prefers TCP port `45821`.
- If port `45821` is busy, it falls back to a dynamic port.
- Documented the stable probe port in `docs/protocol.md`.
- Recorded the refused-port failure in `logs/errors.md`.

### Verification
- Not built or retested. The project owner explicitly requested not to run a build after changes.

### Problems
- Log showed a connection from `10.1.97.57` to `10.1.97.67`, so routing was correct, but the target port refused the socket.

### Fix
Use a stable preferred port to reduce stale NSD/mDNS cache problems caused by random ports changing on each LAN start.

### Remaining
- Build/install when allowed.
- Fully close/reopen Flash on both phones after installing so both advertise the stable port.

### Next AI
After installing, verify both phones show port `45821`. If either shows a fallback port, check whether another process/app instance is already holding `45821`.

## 2026-08-18 - Pixel 7 LAN discovery fixes

### Worked on
Investigated Pixel 7 LAN discovery behavior from device logs and improved the LAN MVP connection flow.

### Changed
- Changed `targetSdk` from 37 to 36 for the MVP.
- Removed `ACCESS_LOCAL_NETWORK` from the manifest.
- Reworked NSD resolving to queue services and resolve them one at a time.
- Added generation checks so stale NSD callbacks after Stop do not repopulate the device list.
- Shortened NSD TXT keys from `protocol` / `capabilities` to `proto` / `caps`.
- Added manual IP/port connection UI.
- Added per-device connection states so buttons show `Connecting`, `Connected`, or `Retry`.

### Verification
- `testDebugUnitTest assembleDebug` passed.
- Installed `E:\Flash\app\build\intermediates\apk\debug\app-debug.apk` to connected ADB device `R5CN21CNJAF` with `adb install -r -t`.
- Physical-device retest with the Pixel 7 is still required.

### Problems
- Pixel 7 logs showed repeated `ACCESS_LOCAL_NETWORK` AppOps errors while the app targeted SDK 37.
- The previous discovery implementation could process late resolve callbacks after discovery had already stopped.

### Fix
For the MVP, target SDK 36 and rely on `INTERNET` for local-network access per Android documentation. Improve NSD callback handling and resolver sequencing to support multiple devices more reliably.

### Remaining
- Reinstall on the Pixel 7 and the other phones.
- Confirm the Pixel 7 is discoverable by the other phone.
- Confirm multiple devices appear at once.
- Confirm manual IP/port connection reaches a peer and changes the button to `Connected`.

### Next AI
Use physical devices to verify Pixel 7 discovery after the target SDK/permission change. If target SDK 37 is restored, implement the official Android local-network permission flow first.

## 2026-08-18 - Reliable LAN kickoff

### Worked on
Started the LAN MVP foundation.

### Changed
- Added app-scoped identity storage.
- Added a TCP LAN probe server using a dynamic port.
- Added Android NSD service registration and discovery.
- Added discovered-device model shared above the discovery layer.
- Replaced the template screen with a LAN start/stop, nearby-device list, and connect probe.
- Added Android platform notes, protocol notes, architecture notes, and an ADR for the LAN-first probe step.

### Verification
- `testDebugUnitTest` passed after running Gradle with:
  - `JAVA_HOME=E:\AndroidDev\AndroidStudio\android-studio\jbr`
  - `GRADLE_USER_HOME=E:\Flash\.gradle-user-home`
- Debug Kotlin compilation completed during the unit-test task.
- `assembleDebug` was attempted but blocked by the local sandbox/Gradle loopback error described in `logs/errors.md`.
- Physical-device LAN verification has not been performed in this environment.

### Problems
- Repository currently has no visible Git metadata from `E:\Flash`; `git status` fails with "not a git repository".
- `docs/` and `logs/` were missing and were created during this session.
- `assembleDebug` cannot currently be completed from the restricted shell because Gradle cannot establish a loopback connection for its daemon/single-use daemon process.

### Remaining
- Run `assembleDebug` from Android Studio or an unrestricted shell.
- Test NSD discovery and TCP probe on two physical Android devices on the same Wi-Fi network.
- Add TLS handshake after basic LAN reachability is stable.

### Next AI
Run `assembleDebug` from Android Studio or an unrestricted shell, then perform physical-device LAN discovery/probe validation.

## 2026-08-19 — First Stream-inspired Flash conversation screen

### Worked on
Implemented the first Flash-owned Compose conversation screen based on the visual structure of the inspected Stream message screen.

### Changed
- Added `app/src/main/java/com/transfer/flash/ui/chat/FlashConversationScreen.kt`.
- Added Flash-owned `FlashConversationUiState` and `FlashMessageUi` presentation models.
- Added conversation header, message bubbles, sender metadata, attachment-grid placeholder, composer, reactions, and message-action bottom sheet.
- Wired the new screen into `MainActivity`; the app opens the conversation screen first and can return to the existing LAN screen.
- Kept text-send and attachment actions as explicit callbacks for later Flash Chat and Flash Transfer integration.

### Verification
- Reviewed the Stream sample message and message-action screenshots.
- Confirmed the new screen does not import Stream source, models, or runtime dependencies.
- Static source review completed.
- Android build was attempted on the connected Windows computer but could not start because no Java/JDK was available in its environment. The first screen remains unverified on a device or emulator.

### Unfinished
- Connect the send callback to Flash Chat/Flash Network.
- Connect attachment selection and transfer progress to Flash Transfer.
- Replace sample conversation data with repository-backed state.
- Run `:app:assembleDebug` after a Java/Android SDK toolchain is available.

### Next AI task
Fix or provide the Android build toolchain, build the app, inspect the rendered first screen, and only then refine this screen or move to the next Stream-inspired page.

## 2026-08-19 — Flash-owned Stream-look design system and conversation UI

### Worked on
Implemented the clean-room Stream-look conversation screen plan: Flash design tokens, split composables, Flash-owned icons, repository seam, and legal ADR.

### Changed
- Added ADR-003 to `docs/decisions.md` (no Stream source/SDK incorporation).
- Added `docs/flash-design-system.md` with measured token documentation.
- Added `app/src/main/java/com/transfer/flash/ui/design/` (`FlashTokens`, `FlashColors`, `FlashTypography`, `FlashMessageStyling`, `FlashChatTheme`).
- Split chat UI into `FlashChannelHeader`, `FlashMessageList`, `FlashMessageBubble`, `FlashComposer`, `FlashMessageActionsSheet`, `FlashAttachmentGrid`, `FlashAvatar`, `FlashReactionsRow`, `FlashIcons`.
- Added 13 `flash_ic_*` vector drawables (20dp stroke icons).
- Added `FlashChatRepository` / `SampleFlashChatRepository` with grouped message positions.
- Wrapped conversation screen in `FlashChatTheme` from `MainActivity` (LAN home screen still uses generic `FlashTheme`).

### Verification
- `testDebugUnitTest assembleDebug` passed.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- No ADB device/emulator available in the build environment; side-by-side comparison with `stream-chat-android-compose-sample` remains pending on hardware.

### Unfinished
- Owner visual acceptance vs Stream compose sample on device.
- Connect repository to LAN chat protocol.
- Dark theme tuning after light theme is accepted.

### Next AI
Install APK on device/emulator, run Stream compose sample beside Flash, tune tokens in `ui/design/` until conversation screen is accepted. Do not start channel list until then.

## 2026-08-19 — Premium chat UI master plan and research-first documentation

### Worked on
Created full premium chat UI implementation specification from owner prompt. Updated AGENTS.md and project docs. **No UI code changes.**

### Changed
- Added `docs/ui/flash-premium-chat-ui-implementation.md` (master plan: UI-001–UI-045, all requirements, procedures, quality gates).
- Added `docs/ui/ui-research-index.md` (component registry, order, status).
- Added `docs/ui/component-doc-template.md` (required per-component sections).
- Added 29 stub component research docs under `docs/ui/` (NOT STARTED).
- Added AGENTS.md §34 Premium Chat UI — Research-First Rules.
- Updated AGENTS.md §4 first-run, §5 docs tree, §22 UI rules, §29 status, §32 fast start.
- Added ADR-004 to `docs/decisions.md`.
- Updated `logs/handoff.md`; marked `docs/flash-design-system.md` as superseded by UI-001 track.

### Verification
- Documentation review only. No build required for doc-only change.

### Unfinished
- UI-001 Visual identity research (`docs/ui/design-system.md`).
- All UI-002–UI-045 component research docs remain empty stubs.

### Next AI
Follow AGENTS.md §34: begin UI-001 research only. Do not implement chat UI until `design-system.md` is DESIGNED.

## 2026-08-22 — Phase P2 partial: C2.1–C2.3 + C2.7 + C2.8 crypto core (:core:security/crypto)

### Worked on
Implemented the crypto foundation of C2 (steps C2.1 identity key, C2.2 self-signed cert, C2.3 fingerprint, C2.7 E2E frames, C2.8 constant-time compares + RFC vectors) with mandatory R1 research first. Trust (C2.4/C2.5) and pairing (C2.6) packages are owned by a concurrent agent and were NOT touched.

### Changed
All new files under core/security/.../crypto/ only:
- Hkdf.kt — RFC 5869 HKDF-SHA256 extract/expand/derive (internal).
- FlashCrypto.kt — interface (identityPublicKey exposure, sign, wire-friendly ByteArray verify, ephemeral ECDH keygen, ecdhSessionKey → 32-byte AES-256 via HKDF bound to FlashProtocol.VERSION) + shared pure-JCA ops (EcP256Ops).
- KeystoreFlashCrypto.kt — AndroidKeyStore ECDSA P-256 alias lash_identity (SIGN|VERIFY, SHA-256 digest, StrongBox on API 28+ with fallback, biometric-invalidation off); selfSignedCertificate() uses the PLATFORM-generated keystore cert (AOSP AndroidKeyStoreKeyPairGeneratorSpi) — no BouncyCastle, no hand-rolled DER.
- SoftwareFlashCrypto.kt — JVM-test/fallback impl, loud NOT-FOR-PRODUCTION KDoc.
- FlashFingerprint.kt — SHA-256 fingerprint, stable XX:XX uppercase grouping, constantTimeEquals via MessageDigest.isEqual.
- E2eFrameCodec.kt — AES-256-GCM [12B nonce | ct+tag], AAD = protocol version string; nonce discipline + rekey placeholder documented.
- Tests: HkdfTest (RFC 5869 TC1+TC2 exact OKM/PRK), SoftwareFlashCryptoTest (sign/verify roundtrip+tamper, ECDH both-direction equality), E2eFrameCodecTest (roundtrip, wrong-key/tamper ⇒ AEADBadTagException), FlashFingerprintTest (hard-coded vector 82A67EF3…F4EB computed independently).

### Verification
- NOT yet built: Gradle runs are forbidden for this agent per task constraints (one consolidated run happens at session consolidation). All test vectors taken from authoritative sources; fingerprint vector independently precomputed.
- Next consolidating agent MUST run :core:security:testDebugUnitTest and record results here.

### Remaining
- C2.4–C2.6 (trust store extension, TOFU, pairing frames) — concurrent agent.
- docs/security.md threat-model update (C2.8 tail) once both agents' work merges.
- Device verification of KeystoreFlashCrypto (StrongBox path, cert generation) — JVM-only here.

### Next AI
Run the consolidated unit-test build; if AEAD/HKDF vectors fail, check Hkdf.expand counter byte first.

## 2026-08-22 " Phase P2 executed: C2.4"C2.6 (Trust pinning + TOFU + Pairing) via subagent

### Worked on
Implemented C2.4 (Room-backed trust/pin store), C2.5 (TOFU policy), C2.6 (pairing frames, numeric-comparison code, pairing state machine, pairing protocol orchestrator) in `:core:security`, per `docs/core-upgrade-plan.md` C2 with R1 research-first and strict file ownership (trust/** new files only, pairing/**, tests; crypto/** untouched " concurrent agent owns it; no .gradle/.toml edits; Gradle NOT run per instructions).

### R1 Research citations
- Numeric comparison precedent (Bluetooth): Bluetooth Core spec, Security Manager " LE Secure Connections numeric comparison value generation function g2 " both devices compute 6-digit values from BOTH parties' public data so displays match; user compares; mismatch aborts: https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core_v6.3/out/en/host/security-manager-specification.html ; walkthrough: https://www.bluetooth.com/blog/bluetooth-pairing-part-4/ ; formal analysis of comparison-based key exchange: https://eprint.iacr.org/2009/013.pdf . Applied: SHA-256 over lexicographically SORTED fingerprint pair (role-independent symmetry), first 5 bytes big-endian mod 10^6, %06d.
- TOFU pitfalls: OWASP Pinning Cheat Sheet " pin SPKI/public key NOT leaf cert chain (survives rotation), fail closed on pin failure, users click past warnings so NO bypass: https://cheatsheetseries.owasp.org/cheatsheets/Pinning_Cheat_Sheet.html ; RFC 7469 " pins are public-key relationships; TOFU residual risk = MITM on first connection; pin validation failure is non-recoverable: https://datatracker.ietf.org/doc/html/rfc7469 . Applied: TofuPolicy pins identity-key fingerprints (C2.3), FirstConnect prompt covers the first-connection risk (mitigated out-of-band by the 6-digit code), Mismatch = hard fail with UI-031 event data, blank presented fingerprint fails closed.
- Room DAO injection pattern: Android data-layer guide " inject DAO into repository-ish store via constructor, suspend one-shots + Flow observables, don't create internal scopes: https://developer.android.com/topic/architecture/data-layer ; async DAO queries (suspend/Flow): https://developer.android.com/training/data-storage/room/async-queries .

### Changed
- `core/security/src/main/java/.../security/trust/pinned/RoomTrustedStore.kt` " implements FlashTrustStore ADDITIVELY (R4; sync methods = runBlocking bridge, documented deprecated-by-convention) + new suspend `pin/isPinned/revoke`, `trustedPeers(): Flow<List<FlashTrustedPeer>>`, idempotent `importFrom(preferencesStore)` migration. Thin DAO delegations; no internal scope.
- `trust/pinned/TofuPolicy.kt` " pure Decision sealed {FirstConnect(promptData), Match, Mismatch(KEY_CHANGED|PRESENTED_FINGERPRINT_MISSING)}; constant-time compare via MessageDigest.isEqual; legacy blank-fingerprint rows re-prompt instead of trusting silently.
- `trust/pinned/LegacyTrustMigration.kt` " pure merge logic for SharedPreferences"Room migration (existing rows win " idempotent; legacy rows carry unbound sentinel).
- `pairing/FlashPairingFrames.kt` " sealed FlashPairingFrame {PairRequest, PairAccept, PairConfirm(codeHashHex), Paired}; plain Kotlin types, wire encoding deferred C4/C6 (noted in KDoc).
- `pairing/NumericComparisonCode.kt` " derive() (sorted-concat SHA-256 construction documented incl. why sorted), confirmationHashHex(), hashesEqual() constant-time, normalizeHex().
- `pairing/PairingSessionStateMachine.kt` " 8 phases mapped to profile-ui.md FlashPairingPhase in KDoc; pure reduce(state,event,timeouts,localFp); PairingTimeouts(requestExpiryMs=30s default, decisionWindowMs configurable); Expired is neutral (â‰  Failed); inapplicable events are no-ops.
- `pairing/FlashPairingProtocol.kt` " FlashPairingEvent sealed (RequestReceived w/ code6+expiresAtMs, PeerAccepted, PeerDeclined, Expired, Confirmed(fp+ephemeralPubKey), Failed); FlashPairingProtocol interface per plan target abstraction + additive onFrame/onTick integration seams; DefaultFlashPairingProtocol fully fake-constructible (no Android types, no internal scope/clock " engine drives ticks).
- Tests (JVM-only, no Robolectric needed): `pairing/NumericComparisonCodeTest` (determinism, symmetry, format/range, uniformity sanity over seeded 5k samples, hash checks), `pairing/PairingSessionStateMachineTest` (full transition matrix incl. expiry boundary, decision-window expiry, code-hash mismatch, terminal absorption, stale-requestId ignore), `pairing/DefaultFlashPairingProtocolTest` (two-party cross-wired handshake happy path, tampered confirm hard-fail, expiry, busy-beginRequest, decline, accept-without-request), `trust/TofuPolicyTest`, `trust/LegacyTrustMigrationTest`; local `testutil/FakeClock` (module-local copy ":core:common FakeTimeSource not visible across modules).

### Verification
- NOT run yet: Gradle execution was explicitly forbidden this session ("DO NOT run Gradle"). Code is written to compile against declared module deps (:core:common, :core:persistence, room-runtime, junit, kotlinx-coroutines-test " verified by reading core/security/build.gradle.kts). Existing trust/identity files untouched (R4); existing tests unaffected.
- RoomTrustedStore itself has no unit tests BY DESIGN (per task instruction): it is one-line DAO delegation; DAO semantics covered by :core:persistence invariant suite (P1). Depth placed in machine/code/TOFU/migration-merge tests.

### Problems
- Initial reducer draft used an exception-based "ignore" helper " rewrote as total pure function returning unchanged state (no exceptions escape).
- sendFrame sink initially typed `suspend` but beginRequest() is non-suspend (plan signature) " changed sink to synchronous enqueue-style `(FlashPairingFrame)->Unit` (documented: non-blocking/enqueue-only; socket I/O stays in transport queue C4/C6).
- SharedFlow(replay=0) drops emissions before collectors subscribe " protocol tests use CoroutineStart.UNDISPATCHED collectors.
- PAIR_DECLINE frame does not exist in the C2 frame set (plan lists exactly REQUEST/ACCEPT/CONFIRM/PAIRED): respondDecline() resets locally; wire-level peer-decline notification deferred to C4/C6 encoding (documented in KDoc). Machine already supports PeerDeclined " DeclinedByPeer.

### Remaining
- Wire codec for FlashPairingFrame (C4/C6).
- Real key material from concurrent crypto agent (FlashCrypto) " ephemeralPublicKeyProvider currently injected seam.
- Engine wiring: ticker scheduling, TOFU pin persistence on Confirmed events (C7), autoAcceptTrusted setting hookup.
- Physical-device verification of full handshake once C4 TLS lands.

### Next AI
1) Run consolidated testDebugUnitTest (agents normally run one Gradle pass per session " this session was blocked from doing so); expect +~30 tests. 2) Fix anything red, log errors per ERROR-0XX. 3) Coordinate with crypto agent for FlashCrypto injection into ephemeralPublicKeyProvider. 4) Update handoff.md.

## 2026-08-22 - P3 pure-logic: StandardEndpointDirectory, TxtCodec, DiscoveryRetryPolicy, CompositeDiscovery (C3.3/C3.5/C3.9)

### Worked on
Pure-JVM half of Phase P3 per task brief: directory bookkeeping, cross-radio TXT contract, deterministic retry math, multi-radio composite discovery. nsd/** untouched; no imports from nsd (own FakeTransport used).

### R1 research (citations also embedded in CompositeDiscovery KDoc)
- (a) mDNS goodbye/TTL semantics: RFC 6762 sec 10.1 goodbyes are TTL=0 records many stacks never send on crash/kill (https://datatracker.ietf.org/doc/html/rfc6762#section-10.1); record TTLs: SRV/A/AAAA ~120 s, PTR/TXT 75 min (sec 10, https://datatracker.ietf.org/doc/html/rfc6762#section-10; corroborated by systemd resolved goodbye PR https://github.com/systemd/systemd/pull/42983, openthread TTL issue https://github.com/openthread/openthread/issues/12083). => DEFAULT_GRACE_MS = 30_000 (matches plan C3.5 example; far below 120 s SRV TTL because Flash peers re-announce at app cadence; long enough to avoid flapping on single missed announcements).
- (b) StateFlow conflates by equality (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/) -> discrete Found/Lost events pushed through StateFlow would collapse for slow collectors (Found-then-Lost could vanish). Event log = SharedFlow(replay=0, extraBufferCapacity=256, DROP_OLDEST documented); snapshot = StateFlow rebuilt from directories.
- (c) Transport priority prior art: AOSP NetworkRanker/NetworkScore policy ranking (https://source.android.com/docs/core/connect/network-selection), NetworkCapabilities transport model (https://developer.android.com/reference/android/net/NetworkCapabilities), Nearby Connections Strategy bandwidth/topology tradeoffs (https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Strategy), Wi-Fi Aware vs BLE throughput (https://developer.android.com/develop/connectivity/wifi/wifi-aware). => priority LAN > WIFI_DIRECT > WIFI_AWARE > BLE, unknown names last.

### Changed (files created; NO existing file modified)
- `core/discovery/.../core/StandardEndpointDirectory.kt`: applySeen dedup by deviceId (Found once; Updated only on hostAddress/port/serviceName/friendlyName/proto change; else touch lastSeenAtMs keep firstSeenAtMs + Unchanged); sweepExpired boundary now-lastSeen >= grace (exactly-at-window IS expired); snapshot ordered lastSeenAtMs DESC then deviceId asc.
- `core/discovery/.../core/TxtCodec.kt`: cross-radio TXT contract keys {device_id,name,model,proto}; decode null when device_id missing/blank or proto unparseable (never throws); trims whitespace; ignores unknown keys.
- `core/discovery/.../core/DiscoveryRetryPolicy.kt`: attempt->delay doubling base=1000 cap=30000 maxAttempts=5, jitter-free by contract (call sites add jitter); null=give-up; reset() no-op kept for API stability.
- `core/discovery/.../core/CompositeDiscovery.kt`: implements existing FlashDiscovery + startAll(port,identity) aggregate (Success iff ALL transports advertise+browse OK; failures listed in FlashError.Unknown message); ONE EndpointDirectory per transport; mergedEvents SharedFlow (DROP_OLDEST) + discoveredEndpoints StateFlow rebuilt per diff; cross-transport dedup by deviceId keeping highest-priority endpoint; LOSS HYSTERESIS: losing high-priority sighting while lower still alive emits Updated(fallback), NOT Lost; sweep(nowMs, graceWindowMs=30s default) emits Lost once per aged peer (idempotent); state StateFlow aggregates advertising/browsing flags. stopDiscovery/stopAdvertising emulate partial stop via full stop + transparent restart (radio seam has only stop()).
- Tests (plain JUnit4, no Robolectric, no coroutines-test): StandardEndpointDirectoryTest, TxtCodecTest, DiscoveryRetryPolicyTest, CompositeDiscoveryTest. Determinism without virtual time: synchronous DirectDispatcher (CoroutineDispatcher dispatching inline) injected via scopeFactory + FakeTransport emitting into controllable MutableSharedFlow; explicit clock lambda drives all timestamps.

### Verification
- NOT run yet: Gradle execution explicitly forbidden this session ("DO NOT run Gradle"). Code written against read-only contracts (FlashRadioTransport, EndpointDirectory, FlashDiscovery, :core:common types verified by reading sources). Existing files/tests untouched (R4).

### Deviations / decisions worth noting
- Two extra OPTIONAL constructor params beyond brief signature: scopeFactory + clock (testability without coroutines-test dependency; defaults keep prod behavior). Documented in KDoc.
- "model" field comparison absent from directory Updated-detection: FlashDiscoveredEndpoint carries no model field (FlashDevice has none); noted in KDoc.
- startAdvertising(listenPort) without prior identity returns Failure (TXT needs identity from startAll).
- Lost serviceName: composite captures service names BEFORE sweeper removal so emitted Lost carries it.

### Remaining
- Gradle testDebugUnitTest pass (expect +~25 tests across 4 new classes).
- Wire NsdTransport (concurrent agent) into a CompositeDiscovery instance at engine level (C7).
- Periodic sweeper scheduling caller-side (engine ticker, C7); RetryPolicy wiring inside transports' restart loops is C3.3 impl detail of each radio.

### Next AI
1) Run consolidated testDebugUnitTest; fix reds, log ERROR-0XX if any. 2) Do not modify these five files without reading this entry. 3) Update handoff.md after verification.

## 2026-08-23 - P3.5 workstream A (identity hardening) + B2/B3 (mode wiring)

### Worked on
Plan P3.5: A2 (TXT caps/p8), A3/A4 (identity fields + inbound proto gate), B2 (NsdTransport.setMode: GHOST/ECO/BOOST), B3 (CompositeDiscovery.setMode fan-out + mode in state). R1 research-first completed BEFORE coding; contracts (FlashDiscoveryMode, DiscoveryModePolicy) were pre-existing and were NOT restructured. group/** and settings/** untouched; no gradle/toml changes; Gradle NOT run.

### Research findings (R1, cited)
- (a) TXT size limits: DNS TXT constituent strings are max 255 bytes each (RFC 1035 §3.3.14 via RFC 6763 §6.1); RFC 6763 §6.2 recommends total TXT ~200 bytes (<=400 to fit 512-byte DNS message, <=1300 NOT-EXCEEDED rule); mDNS packet cap 9000 bytes => ~8900 TXT ceiling but real-world mDNS-offload chipsets historically broke above 256 bytes. VALIDATION: our full key set {device_id(~36), name(<=24), model, proto, caps(<=120), fp8(8)} stays comfortably under the guidance; caps joined value guarded to <=120 chars so caps=+value always fits ONE 255-byte constituent string. Sources: https://www.rfc-editor.org/rfc/rfc6763.html (S6.1, S6.2, S6.4) ; https://www.zeroconf.org/Rendezvous/txtrecords.html
- (b) Zeroconf service-type spoofing/mimicry: mDNS/DNS-SD is unauthenticated — any on-link host can spoof _flash-transfer._tcp. responses and forge TXT (incl. caps/fp8); prior art treats TXT fingerprints as consistency cross-checks only, never as MITM defenses (uptrakit zeroconf security doc), and IETF draft-ietf-dnssd-prireq enumerates sender-impersonation + fingerprinting risks of rich TXT records. CONSEQUENCE: our caps is informational (no access decisions at discovery layer); capability-gating precedent = Bonjour/DNS-SD profiles advertising features via TXT keys (zeroconf.org TXT format doc: clients SHOULD ignore unknown attributes; feature info is a performance hint, TCP connection does real negotiation). Enforcement deferred to connect time (C3.10 seam). Sources: https://github.com/worried-networking/uptrakit/blob/main/docs/security/zeroconf-discovery.md ; https://www.ietf.org/archive/id/draft-ietf-dnssd-prireq-04.html (S3.3.5) ; https://www.ieee-security.org/TC/SP2021/SPW2021/WOOT21/files/woot21-farrah-slides.pdf ; https://ernw.de/download/An_Attack-in-Depth_Analysis_of%20_multicast_DNS_and_DNS_Service_Discovery.pdf
- (c) Duty-cycled scanning precedents: BLE scan modes are the platform's own duty-cycle pattern — SCAN_MODE_LOW_POWER ("consumes least power", enforced for background apps) vs BALANCED vs LOW_LATENCY ("highest duty cycle"): https://developer.android.com/reference/android/bluetooth/le/ScanSettings . Wi-Fi SCAN throttling (Android 8+: bg 1/30min; Android 9+: fg 4/2min) applies ONLY to WifiManager.startScan() — NSD is NOT subject to it (NsdManager runs via the system mDNS path): https://developer.android.com/develop/connectivity/wifi/wifi-scan ; framework confirmation: https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/refs/tags/android-9.0.0_r34/service/java/com/android/server/wifi/ScanRequestProxy.java . BUT multicast reception still costs battery — WifiManager.MulticastLock docs explicitly warn of "noticeable battery drain" and advise release when not needed, and NsdManager docs say background apps should avoid the lock post T-ext7: https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock . ECO duty cycle therefore alternates full browse bursts with idle gaps (releasing nothing extra today; lock lifecycle unchanged) — 20s/100s per DiscoveryModePolicy.

### Changed (all additive; no restructuring)
- core/TxtCodec.kt — added KEY_CAPS/KEY_FP8 + MAX_CAPS_VALUE_LENGTH=120 guard; encode emits caps only when non-empty (flag-boundary truncation via new 	runcateFlags), fp8 when non-blank; decode returns capabilities/fingerprintPrefix with emptySet/null defaults.
- core/FlashRadioTransport.kt — FlashAdvertisedIdentity gains capabilities: Set<String> = emptySet() and ingerprintPrefix: String? = null (defaults keep all existing callers compiling); interface gains setMode(policy) with no-op default body (ADR-013).
- 
sd/NsdTransport.kt — NsdTxtCodec: keys synced incl. caps/fp8; **encode now delegates to core TxtCodec** (trivial TODO(unify) closure; tolerant decode intentionally kept local); ParsedIdentity extended. NsdTransport: setMode() override (advertise toggle immediate w/ retained identity resume on GHOST exit; conflated wake-up cuts ECO idle short), ECO duty loop inside existing browse machinery (maxDutyCycles test-determinism bound), BOOST scales backoff base (cap/attempts unchanged), GHOST startAdvertising = documented Success no-op; inbound hardening: explicit proto != FlashProtocol.VERSION dropped PRE-directory (missing proto still falls back to ours — legacy tolerance preserved); peer caps logged informationally, never retained on the shared endpoint model (FlashDiscoveredEndpoint untouched).
- core/CompositeDiscovery.kt — discoveryMode: StateFlow<FlashDiscoveryMode> + setMode(mode) fan-out to every transport (default STANDARD applied implicitly at construction on both sides — setMode is suspend so eager ctor fan-out impossible); statusMessage gains additive [MODE]  prefix; refreshState consults policy so GHOST never claims isAdvertising; startAll passes identity through unchanged (now carries caps/fp8).
- Tests (same packages): TxtCodecTest +7 (roundtrip caps/fp8, missing-key defaults, malformed caps tolerance, flag-boundary truncation, oversized-set guard, omission when empty); NsdTransportLogicTest +9 (caps/fp8 on wire, version-mismatch pre-directory drop, missing-proto tolerance, caps informational accept, GHOST no-op advertise, GHOST unadvertise+resume, ECO burst/idle alternation over budget, BOOST lowered base asserting ACTUAL slept delays, STANDARD unchanged baseline); CompositeDiscoveryTest +4 via additive FakeTransport.policies recording (default STANDARD at construction, fan-out, [MODE] prefix without breaking suffix, GHOST startAll advertises suppressed while browsing runs).

### Verification
- NOT Gradle-verified this session (forbidden). All tests written deterministic (injected sleep/idleWait/slept recorder, Unconfined inline dispatch) consistent with module's existing technique. Existing 462-test suite expectations reviewed for regressions: FlashDiscoveryState default "Idle" test untouched; CompositeDiscoveryTest legacy assertions don't inspect statusMessage text; NsdTransportLogicTest retry-budget math unchanged for STANDARD.

### Deviations
1. maxDutyCycles constructor bound added (default Int.MAX_VALUE) — no-op sleeps make an ECO loop infinite under the module's no-virtual-time test technique; mirrors maxBrowsingRestarts precedent (documented in KDoc).
2. BOOST implemented as provider SCALING not replacement — preserves injected provider shape; raw provider outputs remain observable for assertions while actual slept delays reflect policy (ADR-013).
3. idleWait injectable returning Boolean (woke-early?) instead of reusing plain sleep — needed for the mid-idle immediate-resume requirement without coroutines-test.
4. TODO(unify) narrowed rather than closed: NsdTxtCodec.encode delegates to TxtCodec; tolerant DECODE stays local by design (fallback contract differs).

### Remaining
- Consolidated 	estDebugUnitTest run by owner/next session (~+20 tests expected).
- Engine wiring (C7): call composite.setMode(...) from settings; sweep caller unchanged.
- C3.10 seam: use fp8/caps at connect time once pairing lands.

### Next AI
Run testDebugUnitTest; fix reds + log ERROR-0XX. Do not touch group/** or settings/** (concurrent agent). When wiring UI mode switcher, consume CompositeDiscovery.discoveryMode + parse state suffix after the [MODE]  prefix if needed.

## 2026-08-23 — P4 pure-logic agent (C4.2/C4.3/C4.5/C4.7-aggregation + C4.9 chaos)

### Worked on
Resilience primitives + chaos harness for `:core:network`, all NEW files only (no existing file touched, no gradle/toml change, Gradle NOT run per session rules).

### Research findings (R1, cited)
- (a) Backoff+jitter: AWS "Exponential Backoff and Jitter" https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/ — Full jitter ≈ Decorrelated completion time with LESS client work; decorrelated only wins under sustained overload; Brooker https://brooker.co.za/blog/2022/08/11/backoff.html ; simulator reference https://github.com/aws-samples/aws-arch-backoff-simulator . CHOICE: full jitter WITH floor `base + rand*(min(cap, base*2^attempt) − base)` (floor = minimum P2P retry spacing; herd de-sync preserved).
- (b) Heartbeat/dead-peer: TCP keepalive defaults 2h first probe and answers at OS layer (zombie-blind) → app-level ping/pong required: https://dev.to/137foundry/why-application-level-heartbeats-beat-tcp-keepalive-for-websockets-1bfl ; websocket.org timeout guide: missed-counter pattern, "3 missed is a reasonable default", 25s interval guidance: https://websocket.org/guides/troubleshooting/timeout/ ; chat presence systems use 10–15s heartbeats, offline after 2–3 misses: https://websocket.org/guides/use-cases/chat/ . CHOICE: intervalMs=10_000, missedThreshold=3 (P2P has no proxy idle timeout → bias fast detection; worst-case declaration 30s).
- (c) Bounded-queue backpressure: reject-newest/fail-fast correct when every item matters and caller has fallback; drop-oldest only when newest invalidates oldest (video/sensors); block risks deadlock on dead peers: https://unseel.com/cs/backpressure ; https://www.techinterview.org/post/3233468900/lld-backpressure/ ; https://letsbuildsolutions.com/blog/system-design/back-pressure-in-distributed-systems-flow-control-patterns-that-prevent-cascading-overload/ . CHOICE: REJECT (typed Rejected(QueueFull|Closed), outbox retains write per C6 contract).

### Changed (all under ownership paths only)
- main `resilience/ReconnectPolicy.kt` — pure delayForAttempt(attempt[, random01]) + stateful nextDelay()/reset() (stable-connect reset), giveUpAfterMs nullable (null=infinite, P2P semantics), overflow-guarded bounds.
- main `resilience/HeartbeatPolicy.kt` (+10s/3 defaults w/ citations), `HeartbeatTracker.kt` — Alive/Suspect/Dead, onPingSent/onPongReceived/onTick(nowMs)→PingNow|AwaitPong|DeclareDead; exactly-at-threshold inclusive boundary; Dead terminal.
- main `resilience/BoundedSendQueue.kt` — capacity 64 default, Enqueued|Rejected, poll/drainInto/awaitDrained/close, ReentrantLock thread-safe.
- main `resilience/SessionHardeningPolicy.kt` — maxConcurrentSessions=8; resolveDuplicate(existingRank,newRank): strictly-lower wins, tie keeps existing; ranks LAN0>Direct1>WS2>relay/mesh(+future BLE-presence)3>unknown99 mirroring C3 priority.
- main `resilience/ConnectionHealthAggregator.kt` — MutableStateFlow holder (coroutines available transitively via lifecycle-runtime-ktx — verified LanSession already uses it); resolve(): sessions beat attempts beat peer-count; Connected when ≥1 healthy & none degraded; mixed healthy+degraded→Connected (documented precedence); Degraded only when ALL degraded.
- main `resilience/ChaosSession.kt` + `ChaosNetworkHarness.kt` — seeded drop/dup/reorder-window/delay/disconnect faults over FlashSession delegate; DedupGate helper; harness composes queue+tracker+policy.
- tests `resilience/` — ReconnectPolicyTest (seeded distribution bounds incl. cap/clamp/give-up/reset-replay), HeartbeatTrackerTest (boundary ticks incl. exactly-at-threshold, pong-reset, Dead-terminal), BoundedSendQueueTest (overflow, FIFO, closed-drainable, 4-producer/1-consumer smoke w/ per-stream FIFO check), SessionHardeningPolicyTest (ties, rank order, unknown-never-wins), ConnectionHealthAggregatorTest (precedence matrix + holder flow), ChaosResilienceTest (invariants i–iv + mid-drain outbox retention). All deterministic JVM tests, explicit nowMs / seeded rng / injected random01; NO coroutines-test dependency (runBlocking only, from transitive coroutines-core).

### Verification
- NOT Gradle-verified this session (forbidden). Logic traced by hand against contracts read first: FlashConnectionHealth enum values, FlashSession+FrameAck, FlashNetwork.connectionHealth/retryConnection, LanSession/WsConnection skim.

### Deviations
1. Full-jitter variant uses a BASE FLOOR (task spec range `[base, min(cap, base*2^attempt)]`) vs canonical AWS `[0, bound]` — spec-compliant and justified above.
2. Chaos inbound enters via explicit deliverInbound() because FlashSession exposes no inbound hook by design (reads live in transport loops like LanSession.readLoop).
3. Harness requeueAtHead shim added so failed sends retain FIFO order inside the harness (production wiring will own this via the real session manager).
4. Mixed healthy+degraded sessions map to Connected (spec left mixed case open; any healthy path dominates — documented in KDoc).

### Remaining
- Owner runs testDebugUnitTest (~+30 tests expected); fix reds + ERROR-0XX if any.
- C4.2/C4.3 engine WIRING into FlashNetwork impls (these are the pure primitives; integration step separate).
- C4.4 lifecycle binding, C4.6 endpoint plumbing untouched (not in scope).

### Next AI
Read docs/core-upgrade-plan.md C4 + this entry; wire primitives into the concrete FlashNetwork implementation behind connectionHealth; do NOT modify resilience/** APIs without reading their KDoc rationale.
