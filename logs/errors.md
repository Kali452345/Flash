
# Error Log

## ERROR-013 - Multi-stream dispatcher concurrency family (RESOLVED)

### Date
2026-08-23 (diagnosed/resolved 2026-08-24)

### Area
:core:transfer multistream (C5.7) -- dispatcher/receiver concurrency & testing

### Symptoms
MultiStreamDispatcherTest scenarios failed/hung: gated-channel stall, resume-seeding zero progress / failure, progress-monotonic timeout, channel-death survivor, E2E failures. PipelineEndToEnd resume assertNotNull(completedFrame) failure.

### Environment
Pure-JVM unit tests, Dispatchers.Default workers, loopback in-memory channels.

### Root Cause Analysis & Fixes
1. **sendFrame return value handling:** `runCatching { channel.sendFrame(...) }.isSuccess` always returned `true` because `sendFrame` returns `Boolean` (so `Result.success(false)` is still a success). Fixed all 3 occurrences to `.getOrDefault(false)`.
2. **Materializer pos=index skip bug:** When opening `ChunkStream` the materializer set `pos = index` instead of leaving `pos = 0`, so the `while (pos < index)` skip loop never ran and chunk 0 was re-sent even when in `doneIndexes`. Fixed by removing `pos = index` from the `.also` block.
3. **Test event-loop starvation:** Tests used `launch { send() }` inside `runBlocking` then polled with `Thread.sleep`, blocking the single event-loop thread. Fixed by dispatching to `Dispatchers.Default`.
4. **PipelineEndToEndTest receiver reuse:** Resume test created a fresh `ReceivePipeline` instead of reusing `firstReceiver` (which held chunks 0..11), so session was unknown and no COMPLETE was ever emitted. Fixed to reuse `firstReceiver`.
5. **COMPLETE frame caching:** Added `@Volatile completeFrameBytesHolder` so the generated COMPLETE frame is reliably available even if emitted during `ingestComplete`.

### Verification
- `MultiStreamDispatcherTest`: 8/8 green (all scenarios un-@Ignore'd).
- `PipelineEndToEndTest`: 2/2 green.
- Full `testDebugUnitTest` suite: BUILD SUCCESSFUL, 0 failures.

### Status
RESOLVED

## ERROR-012 - PowerShell 5.1 Get-Content/Set-Content corrupts UTF-8 repo files (mojibake)

### Date
2026-08-22

### Area
Tooling / documentation workflow (not app code)

### Symptoms
After a PowerShell round-trip of `logs/handoff.md`, every em-dash/ellipsis/smart-quote in the file displayed as mojibake (`â€"`, `â€“`, etc.). File content was semantically intact but encoding-damaged across the entire document, including historical sections.

### Environment
Windows PowerShell 5.1 (default shell), file = UTF-8 without BOM.

### Error
```text
`main` â€" remote: ... / UI-025â€"027 ...  (E2 80 94 read as ANSI "â€"", then re-encoded as UTF-8)
```

### Root cause
PS 5.1 `Get-Content` without `-Encoding utf8` decodes BOM-less UTF-8 using the legacy ANSI codepage; `Set-Content -Encoding utf8` then re-encodes the already-corrupted strings. One pass destroys all non-ASCII characters.

### Failed attempts
1. In-place string replacement on the mangled text — abandoned: too many distinct mojibake sequences to reverse reliably.

### Working fix
`git checkout -- logs/handoff.md` (last commit held a clean copy), then redo all edits with the editor tooling that writes UTF-8 natively.

### Verification
Post-restore diff clean; subsequent edits verified rendering correctly.

### Related files
- `logs/handoff.md`
- Rule going forward: never round-trip repo text files through PS 5.1 Get-/Set-Content; use native edit tools or `-Encoding utf8` on BOTH sides.

### Status
RESOLVED

## ERROR-007 - Edge-to-Edge System Bar Overlap (Status Bar Cutout & Navigation Bar)

### Date
2026-08-20

### Area
Compose UI / Window Insets / Edge-to-Edge (`FlashComposer`, `FlashChatHeader`, `FlashChatListTopBar`, `FlashSelectionToolbar`)

### Symptoms
1. In chat list and conversation view, top headers drew at y=0 directly behind the status bar clock, battery, and front camera punch-hole cutout, making tabs/header buttons difficult to tap.
2. In conversation view, the message input text area and bottom buttons drew directly behind the 3-button system navigation bar or gesture pill.

### Environment
- Android physical devices (API 30–36) with `enableEdgeToEdge()` enabled in `MainActivity.kt`.

### Root cause
With `enableEdgeToEdge()` active in `MainActivity`, Android draws composables under system bars by default:
- Custom headers (`FlashChatListTopBar`, `FlashChatHeader`, `FlashSelectionToolbar`) lacked `statusBarsPadding()`, causing their interactive action buttons to be obscured by the status bar and camera notch.
- The custom composer (`FlashComposer`) only applied `imePadding()` (which is 0 when the software keyboard is closed) without `navigationBarsPadding()`, causing the composer to sit directly under the navigation bar icons.
- `FlashConversationScreen` Scaffold was setting `contentWindowInsets = WindowInsets.navigationBars`, causing double or mismatched insets calculation against the bottom bar.

### Failed attempts
None. Systematic Compose insets hierarchy applied.

### Working fix
1. Updated `FlashChatListTopBar`, `FlashChatHeader`, and `FlashSelectionToolbar` to wrap header content in a root container with `.fillMaxWidth().background(colors.backgroundSurface).statusBarsPadding()`. This draws the surface color up behind the status bar while safely positioning all text, avatars, and action icons below the camera cutout and status bar.
2. Updated `FlashComposer` to apply `.navigationBarsPadding().imePadding()`, ensuring proper clearance above the system navigation bar when closed and above the software keyboard when open.
3. Updated `FlashConversationScreen` Scaffold to use `contentWindowInsets = WindowInsets(0, 0, 0, 0)` so that `Scaffold` correctly uses measured `topBar` and `bottomBar` heights for `innerPadding`.
4. Added `statusBarsPadding().navigationBarsPadding()` to QA sheets (`FlashIconSheet`, `FlashMotionSheet`).

### Verification
- Ran full multi-module build and unit test suite.
- Validated that status bar, camera cutout, navigation bar, and keyboard insets are correctly respected.

### Related files
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashChatListTopBar.kt`
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashChatHeader.kt`
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashSelectionToolbar.kt`
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashComposer.kt`
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashConversationScreen.kt`
- `ui/theme/src/main/java/com/transfer/flash/ui/icons/FlashIconSheet.kt`
- `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashMotionSheet.kt`

### Status
RESOLVED

---

## ERROR-006 - StackOverflowError during NSD service resolution

### Date
2026-08-20

### Area
NSD Service Resolution / Core Discovery (`NsdResolveQueue.kt`)

### Symptoms
App crash on startup on `ConnectivityThread`:

```text
FATAL EXCEPTION: ConnectivityThread
Process: com.transfer.flash, PID: 7882
java.lang.StackOverflowError: stack size 1039KB
	at com.transfer.flash.core.discovery.nsd.NsdFlashDiscovery.access$isCurrent(NsdFlashDiscovery.kt:27)
	at com.transfer.flash.core.discovery.nsd.NsdFlashDiscovery$startDiscovery$listener$1.onServiceFound$lambda$2(NsdFlashDiscovery.kt:95)
	at com.transfer.flash.core.discovery.nsd.NsdResolveQueue$resolve$listener$1.onServiceResolved(NsdResolveQueue.kt:61)
	at com.transfer.flash.core.discovery.nsd.NsdResolveQueue$resolve$listener$1.onServiceResolved(NsdResolveQueue.kt:62)
```

### Environment
- Android physical device (Samsung Galaxy / Snapdragon)
- Target SDK: 36

### Root cause
Inside `NsdResolveQueue.kt`, the anonymous `NsdManager.ResolveListener` overrides `onServiceResolved(info: NsdServiceInfo)`. On line 62, it called `onServiceResolved(info)` intending to invoke the constructor lambda `onServiceResolved: (NsdServiceInfo) -> Unit`. However, because the method name in the anonymous class identical to the parameter name, the invocation resolved to the anonymous listener's own `onServiceResolved(info)` method recursively, causing an immediate `StackOverflowError` when resolving discovered mDNS services.

### Working fix
Renamed constructor parameter in `NsdResolveQueue` from `onServiceResolved` to `onResolvedCallback` and updated invocation site to `onResolvedCallback(info)`.

### Verification
- Code inspected and validated to eliminate shadowed recursion.
- Build and unit tests verified via `testDebugUnitTest assembleDebug`.

### Related files
- `core/discovery/src/main/java/com/transfer/flash/core/discovery/nsd/NsdResolveQueue.kt`
- `core/discovery/src/main/java/com/transfer/flash/core/discovery/nsd/NsdFlashDiscovery.kt`

### Status
RESOLVED

---

## ERROR-003 - LAN probe routed through wrong local network

### Date
2026-08-18

### Area
LAN connection / Android network routing

### Symptoms
Manual/discovered connection timed out even though NSD discovered the peer:

```text
NSD service resolved deviceId=d844e50b-8d38-4e77-a74c-68ef833dfea7 address=10.1.97.57:46589
LAN probe connecting address=10.1.97.57:46589 deviceId=d844e50b-8d38-4e77-a74c-68ef833dfea7
java.net.SocketTimeoutException: failed to connect to /10.1.97.57 (port 46589) from /10.177.173.19 (port 48052) after 4000ms
```

### Environment
- Device log from package `com.transfer.flash`
- Target SDK: 36

### Root cause
The peer address was on `10.1.97.x`, but Android opened the outbound socket from local address `10.177.173.19`. That means the socket used Android's default network instead of the Wi-Fi/LAN network that can reach the discovered peer. This commonly happens when the phone has mobile data, hotspot, VPN, or another active network and Wi-Fi is not the default internet network.

### Failed attempts
The previous probe used a plain `Socket()`, which lets Android choose the default network.

### Working fix
`LanConnectionProbe` now uses `ConnectivityManager` to find a Wi-Fi/Ethernet `Network` and creates the socket through `network.socketFactory`. This should force LAN probes to use the local network path.

### Verification
Not yet built or retested. The user explicitly requested not to run a build after this change.

### Related files
- `app/src/main/java/com/transfer/flash/network/LanConnectionProbe.kt`
- `app/src/main/java/com/transfer/flash/lan/LanController.kt`

### Status
OPEN

## ERROR-004 - LAN probe reaches peer but port refuses connection

### Date
2026-08-18

### Area
LAN connection / NSD advertised port

### Symptoms
After routing LAN sockets through the Wi-Fi network, the connection no longer timed out from a different subnet. Instead it failed quickly with `ECONNREFUSED`:

```text
LAN probe connecting address=10.1.97.67:45331 deviceId=ad74c205-ef18-4971-8a6d-377994ed5462 network=462967197709
java.net.ConnectException: failed to connect to /10.1.97.67 (port 45331) from /10.1.97.57 (port 44968) after 4000ms: isConnected failed: ECONNREFUSED (Connection refused)
```

### Root cause
`ECONNREFUSED` means the target device was reachable, but nothing was listening on the advertised port at that moment. The LAN MVP used a random dynamic port each time `Start LAN` ran. Because mDNS/NSD records can briefly outlive app restarts or Start/Stop toggles, another device can try a stale random port after the peer has moved to a new port.

### Failed attempts
- Previous code used `ServerSocket(0)`, causing a different port after each LAN start.

### Working fix
`LanProbeServer` now prefers stable TCP port `45821` and falls back to a dynamic port only if `45821` is unavailable.

### Verification
Not built or retested. The user explicitly requested not to run a build after changes.

### Related files
- `app/src/main/java/com/transfer/flash/network/LanProbeServer.kt`
- `docs/protocol.md`

### Status
OPEN

## ERROR-002 - Pixel 7 not reliably discoverable with SDK 37 local-network permission path

### Date
2026-08-18

### Area
LAN discovery / Android local-network permission

### Symptoms
- Pixel 7 could discover another phone, but the other phone did not reliably show the Pixel 7.
- Pixel 7 displayed a system local-network device prompt that was not part of the app UI.
- Logs repeatedly showed:

```text
AppOps system_server E Operation not found: uid=10315 pkg=com.transfer.flash(null) op=ACCESS_LOCAL_NETWORK
```

### Environment
- App package: `com.transfer.flash`
- Device involved: Pixel 7
- Previous app target SDK: 37
- Compile SDK: 37

### Error
```text
Operation not found: uid=10315 pkg=com.transfer.flash(null) op=ACCESS_LOCAL_NETWORK
```

### Root cause
The MVP targeted SDK 37 and declared `ACCESS_LOCAL_NETWORK` without implementing the SDK 37 runtime permission/system-device-picker flow. Android documentation says this permission is required for SDK 37+ but should not be declared for SDK 36 or lower, where `INTERNET` provides implicit local-network access.

The discovery implementation also allowed late NSD resolve callbacks after Stop and did not serialize resolve requests, both of which could make multi-device discovery inconsistent.

### Failed attempts
No code-level retry was attempted before this fix. The issue was diagnosed from physical-device logs supplied by the project owner.

### Working fix
- Target SDK changed to 36 for the LAN MVP.
- Removed `ACCESS_LOCAL_NETWORK` from the manifest.
- NSD resolve requests are now queued and processed one at a time.
- Stale callbacks are ignored using discovery generation checks.

### Verification
`testDebugUnitTest assembleDebug` passed. Physical Pixel 7 retest is pending.

### Related files
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/transfer/flash/discovery/LanDiscovery.kt`
- `docs/android-platform-notes.md`

### Status
OPEN

## ERROR-001 - Gradle assemble blocked by loopback restriction

### Date
2026-08-18

### Area
Build verification / local environment

### Symptoms
`assembleDebug` fails before app compilation output with:

```text
java.io.IOException: Unable to establish loopback connection
```

### Environment
- OS shell: PowerShell
- Workspace: `E:\Flash`
- Java runtime used for verification: `E:\AndroidDev\AndroidStudio\android-studio\jbr`
- Gradle user home used for verification: `E:\Flash\.gradle-user-home`

### Error
```text
FAILURE: Build failed with an exception.

* What went wrong:
java.io.IOException: Unable to establish loopback connection
```

### Root cause
The restricted shell blocks Gradle's local loopback connection used for the daemon or single-use daemon process. This appears environmental rather than caused by the Android source changes, because `testDebugUnitTest` completed successfully in the elevated Gradle run and compiled the debug Kotlin sources.

### Failed attempts
1. Ran `assembleDebug` from the restricted shell after unit tests; it failed with the loopback error.
2. Ran `assembleDebug` with `--no-daemon --offline`; Gradle still forked a single-use daemon and failed with the same loopback error.
3. Requested another elevated Gradle run; the environment rejected the escalation.

### Working fix
Run Gradle from an unrestricted shell.

### Verification
After the environment switched to unrestricted filesystem/network access, `testDebugUnitTest assembleDebug` passed.

### Related files
- `gradle.properties`
- `gradle/gradle-daemon-jvm.properties`

### Status
RESOLVED

## ERROR-011 - Focus overlay required multiple taps to dismiss (Dialog window-focus quirk)

### Date
2026-08-21

### Area
UI-007/UI-008 Message focus overlay (`FlashMessageFocusOverlay`, `FlashMessageContextMenu.kt`)

### Symptoms
On device (Samsung SM_G986U1), after long-press opening the context menu, tapping the dimmed area often did nothing on first contact; dismissal needed several taps.

### Root cause
The overlay was rendered in a separate Compose `Dialog` window. On some OEM builds the first pointer event after a dialog window gains focus is consumed by the window-focus transition (visible in logcat as `MSG_WINDOW_FOCUS_CHANGED 0→1` at dialog open), so the scrim's click handler misses it.

### Working fix
Replaced the `Dialog` with an in-screen overlay: `FlashMessageFocusOverlay` now renders as the last child of the conversation layout — a full-size scrim Box with clickable dismiss, a `BackHandler`, and an explicit top-end close button. Same visuals; taps land in the activity's own window with no focus-consumption loss.

### Status
RESOLVED (code); pending re-verification on device

---

## ERROR-010 - Voice recording started then immediately lost gesture control

### Date
2026-08-21

### Area
UI-020 Voice recording (`FlashComposer.kt` / `FlashMicButton`)

### Symptoms
Hold-to-record appeared to start then "stop"; slide-to-cancel and release-to-send never fired.

### Root cause
The composer used `AnimatedContent(recordingPhase)` around the whole input row. Pressing the mic flipped phase Idle→Holding, which swapped content and **disposed the exact `FlashMicButton` node whose `awaitEachGesture` owned the active touch stream**. The replacement mic composed fresh but never receives an in-progress stream (Compose hit-tests at touch-down), so all subsequent move/up events were lost.

### Working fix
Hoisted `FlashMicButton` out of the swapped region: the AnimatedContent now swaps only the leading/center content (input pill ↔ recording bar), while one persistent mic node occupies the trailing slot across Idle/Holding/CancelArmed. Layout swap to the full-width Locked panel happens only after finger-up, which is safe.

### Related files
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashComposer.kt`

### Status
RESOLVED (code); pending re-verification on device

---

## ERROR-009 - Blank band above composer covering conversation when keyboard opened

### Date
2026-08-21

### Area
Conversation screen insets (`FlashConversationScreen.kt` / `FlashComposer.kt` / `FlashMessageList.kt`)

### Symptoms
Tapping the message text field made the keyboard push up a blank strip that covered part of the conversation.

### Root cause
IME inset applied twice: the composer lives in Scaffold's `bottomBar` and applies `.imePadding()` itself (so bottomBar height grows with the keyboard), but `FlashMessageList`'s modifier ALSO applied `.imePadding()` on top of `innerPadding` (which already includes the grown bottomBar). Net effect: list bottom inset = 2× keyboard height.

### Working fix
Removed `.imePadding()` from the `FlashMessageList` call site; keyboard clearance now flows solely through Scaffold `innerPadding`.

### Status
RESOLVED (code); pending re-verification on device

---

## ERROR-008 - Intermittent "The device is not ready" during Gradle cache writes (E: drive)

### Date
2026-08-21

### Area
Build environment / Gradle daemon caches on E: drive (GRADLE_USER_HOME=E:\Flash\.gradle-user-home and project .gradle)

### Symptoms
- Build fails within seconds with java.io.IOException: The device is not ready thrown from DefaultFileLockManager / AsyncCacheAccessDecoratedCache cache-lock writes.
- Intermittent: direct file writes to E:\ succeed, then fail minutes later; retrying sometimes passes.
- A Gradle daemon can survive in a half-dead state ("Unable to stop one of the daemons") after these failures.
- Recurs across multiple sessions and on the owner's own terminal.

### Environment
- Windows, project + Gradle user home on E: drive (NTFS, reports Healthy, ~170 GB free).
- Machine has a Realtek PCIE Card Reader + Netac SSD - E: is believed to be removable/hot-plug storage; reader power-saving can drop the device handle mid-write.

### Error
`	ext
java.io.IOException: The device is not ready
  at java.base/sun.nio.ch.FileDispatcherImpl.write0(Native Method)
  at org.gradle.cache.internal.filelock.LockStateAccess.writeState(LockStateAccess.java:61)
`

### Root cause
The physical device hosting E: intermittently drops its I/O handle (power-saving or hot-plug behavior). Any write touching that handle fails. Gradle's multi-process safe caches are write-heavy at startup/settle, so they surface the failure first.

### Working fix / workaround
1. Kill all daemons: .\gradlew.bat --stop then 	askkill /PID <stuck java pid> /F.
2. Verify no stale locks (optional): all *.lock files under .gradle-user-home\caches and .gradle should open read/write without error.
3. Start a fresh daemon and rebuild - succeeded immediately (20 s, 357 tasks).
4. If it recurs mid-session: add --no-daemon --no-configuration-cache --no-build-cache.

### Real fix (owner decision)
Move GRADLE_USER_HOME and/or the project to an internally powered fixed disk, or disable power management on the E: device (Device Manager -> disk/reader -> Power Management / "turn off device" unchecked). Hardware-side; cannot be fixed from the repo.

### Status
MITIGATED (workaround reliable; hardware follow-up recommended)
