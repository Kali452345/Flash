# NEXT SESSION PROMPT — Flash project (copied to internal SSD)

Paste everything below this line into a fresh AI chat.

---

You are the lead Android engineer for **Flash**, a local-first P2P messaging + file-transfer app.
Project root: **the folder containing this file** (was `E:\Flash`, copied to internal SSD — verify
actual path with `pwd`/`git status`). Git remote: https://github.com/Kali452345/Flash.git (branch main).

## FIRST ACTIONS (never trust chat memory)
1. Read `AGENTS.md` fully (§34 premium UI rules, §22–28 especially).
2. Read `logs/handoff.md`, `logs/errors.md` (ERROR-008, -012, -013), latest `logs/progress.md` entries.
3. Read `docs/core-upgrade-plan.md` (PART 1 plan, phases P0–P8), `docs/ui-page-plan.md`.
4. Run `git log --oneline -8` and `git status`. Verify build works BEFORE changing anything.

## Build command (ADJUST paths for the new drive!)
```
$env:JAVA_HOME="<new-path>\AndroidStudio\jbr"   # was E:\AndroidDev\AndroidStudio\android-studio\jbr
$env:GRADLE_USER_HOME="<new-path>\Flash\.gradle-user-home"
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
```
NOTE: `local.properties` was deleted before the copy (contained old `sdk.dir=E:\...`) — open in
Android Studio once or create it manually pointing at the SDK on the new drive. Dependency caches
(`.gradle-user-home`) were also deleted — first Gradle run re-downloads everything.

## WHERE WE ARE — one paragraph
UI roadmap complete (UI-001–045 done except UI-045 quality gate; runs after device verification).
Core upgrade plan phases P0–P4 COMPLETE and committed (foundations+Hilt+CI; Room+SQLCipher
persistence; security stack incl. pairing/E2E/TOFU; resilient network layer with TLS option).
P5 part 1 COMMITTED (`5b0fff7`): chunked framing v2 + send/receive pipelines + wslegacy relocation.
Option-2 Dev Console integration (discovery→network bridge, tap-to-connect) also committed.

## CURRENT UNCOMMITTED WORKING TREE — the live task (ERROR-013 fix, ~90% done)
`MultiStreamDispatcher.kt` was rewritten as **v3** modeled on a segmented-downloader reference
repo (`media-downloader-main/` in project root — study its SegmentedDownloader.kt if needed):
static assignment idx%N, single materializer coroutine owning sequential ChunkStream reads,
lock-free atomics hot path, receiver-authoritative completion with grace fallback,
at-least-once wire/exactly-once write, concurrent multi-session safe (no global state).

ROOT CAUSE of all the old flaky failures was found and FIXED: v3 initially never transmitted
FILE_START, so the receive pipeline rejected every chunk as UNKNOWN_TRANSFER → no ACKs →
all-channels-dead. Fix applied: FILE_START announced on every opened channel before workers start
(see "Announce the transfer on EVERY live channel" block in MultiStreamDispatcher.kt).

**STILL TO DO (in order):**
1. Remove debug probes from `MultiStreamDispatcher.kt`: the `dbg(...)` function and its ~8 call
   sites (send start, opened=, worker$id up, phase1 done, closing shared, produced idx=,
   materializer up, workers joined, post-join) — search for `dbg(` and the msdbg.txt file writes.
2. Run `:core:transfer:testDebugUnitTest`. Expected: previously-failing scenarios (three streams
   E2E, gated channel, channel death, resume seeding, progress monotonic, racing-ACK, resume
   mid-file E2E) should now pass. The FILE_START fix addresses their common root cause.
3. If gated/resume tests still fail on timing: check `MultiStreamDispatcherTest.kt` stale
   expectations were updated (fastSentTotal==13 not 18; slow.startedSending via GatedChannel cast;
   @Test(timeout=60_000) present on E2E/gated/death/progress tests). Racing-ACK test uses
   `completeGraceMs = 0`.
4. Add the NEW test `concurrent sessions - two peers transfer at the same time and both complete`
   if missing (two independent Harness pairs, both send() concurrently via async launch, both
   Completed, assembler matches payload, 19 writes each) — proves simultaneous multi-peer use.
5. Full suite ×2 for stability. Then: remove ERROR-013 OPEN entry → RESOLVED in logs/errors.md
   (keep the history), update logs/progress.md + logs/handoff.md, commit:
   `fix(transfer): ERROR-013 resolved — FILE_START session announcement + structured concurrency v3`
6. THEN continue P5 part 2: FlashTransferRepository implementation over these pipelines (C5.2),
   SAF receive policy (C5.9), multi-file manifest (C5.10), FGS wiring (C5.12).

## KEY FILES for the live task
- `core/transfer/src/main/java/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt`
- `core/transfer/src/test/java/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcherTest.kt`
- `core/transfer/src/main/java/com/transfer/flash/core/transfer/chunked/*` (framing/pipelines — GREEN, don't break)
- `core/network/src/main/java/com/transfer/flash/core/network/bridge/DiscoveryRouteBinder.kt`

## RULES REMINDER
Conventional commits; never commit secrets; research online before unfamiliar APIs (cite);
tests must pass before claiming completion; log everything; don't delete historical logs;
no Material icons/text as final UI (ADR-009/§34); agents forbidden from running Gradle — lead runs
one consolidated build per round.

---
