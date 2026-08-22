# Performance Research & Stress-Test Harness

**Status:** IMPLEMENTED (UI-042 DESIGNED→IMPLEMENTED; UI-043 IMPLEMENTED — device measurement PENDING)  
**Component ID:** UI-042 / UI-043  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

> ⚠️ **Everything in this document is CODE-REVIEW-level analysis only. No device numbers exist yet.**
> Per AGENTS.md §23 ("measure first"), no optimization may be performed on the basis of
> anything below until the measurement plan has been executed on physical devices and results
> recorded in `logs/experiments.md`. All thresholds are targets to validate, not facts.

---

## Component

Two related work items sharing one document:

| ID | Deliverable | Files |
|---|---|---|
| **UI-042** | Compose performance research for the Flash chat pipeline + measurement plan | `docs/ui/performance.md` (this file) |
| **UI-043** | Large-conversation stress-test harness rendering the REAL list at 100–2000 messages | `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashStressTestScreen.kt` (+ `FlashStressLogicTest.kt`) |

## Purpose

Establish a repeatable way to *measure* (not guess) conversation-list performance before any
optimization work, per §23. The stress screen makes pathological dataset sizes reachable in one
tap so Macrobenchmark/gfxinfo runs can target a real screen instead of a synthetic preview.

---

## Research sources (UI-042, online, retrieved 2026-08-22)

### Official Android documentation
1. [Lazy lists and lazy grids](https://developer.android.com/develop/ui/compose/lists) — item keys,
   `contentType`, avoiding 0-height items, measuring only in release/R8 builds.
2. [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices) —
   `remember` expensive calculations, stable lazy keys, `derivedStateOf`, deferred reads via lambda
   modifiers, avoiding backwards writes.
3. [Compose performance overview](https://developer.android.com/develop/ui/compose/performance) —
   phases model, Baseline Profiles, stability.
4. [Stability in Compose](https://developer.android.com/develop/ui/compose/performance/stability) —
   skippable vs restartable; collections (`List`/`Set`) always unstable; cross-module types unstable.
5. [Strong skipping mode](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping) +
   [blog post](https://medium.com/androiddevelopers/new-ways-of-optimizing-stability-in-jetpack-compose-038106c283cc) —
   auto-lambda memoization; unstable params compared by instance equality.
6. [Fix stability issues](https://developer.android.com/develop/ui/compose/performance/stability/fix) —
   `@Immutable` contract warnings; not every composable needs to be skippable.
7. [Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) +
   [overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) —
   `StartupTimingMetric`, `FrameTimingMetric` (`frameDurationCpuMs`, `frameOverrunMs`, P50–P99),
   `CompilationMode`, `StartupMode.WARM` for scroll benchmarks, UI Automator + `testTagAsResourceId`.
8. [Create Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile) +
   [codelab](https://developer.android.com/codelabs/android-baseline-profiles-improve) — ~30% first-run
   speedup; TTID/TTFD; `ReportDrawn*`; profile must include scroll CUJs to help them.
9. [Capture a heap dump](https://developer.android.com/studio/profile/capture-heap-dump) +
   [Optimizing bitmap images](https://developer.android.com/develop/ui/compose/graphics/images/optimization) —
   duplicate-bitmap detection in heap dumps (Narwhal+), Native Size column for bitmaps.
10. [Memory usage vitals](https://developer.android.com/topic/performance/vitals/usage) (`dumpsys meminfo`),
    [record Java/Kotlin allocations](https://developer.android.com/studio/profile/record-java-kotlin-allocations).

### Engineering deep-dives
11. [Inside Jetpack Compose List Animations: animateItem internals](https://xckevin.com/en/blog/compose-lazycolumn-animate-item-placement/) —
    placement animations run on separate layers from scroll translation; `beyondBoundsItemCount`
    keeps off-screen items alive so placement animations can finish (cost: memory); recommends
    list animations ≤300 ms so they finish within the viewport without retention.
12. [How LazyColumn Works Under the Hood](https://doveletter.dev/articles/lazycolumn-internals) (skydoves, 2026-05) —
    composition happens during the *measurement* phase via `SubcomposeLayout`; recycling retains up to
    7 slots **per contentType**; prefetcher pre-composes items about to become visible.
13. [LazyColumn items staying in memory](https://stackoverflow.com/questions/70009905/) —
    data-set churn can retain item compositions; relevant to rapid message arrival (UI-021).

---

## Existing approaches studied

| Approach | Summary | Verdict for Flash |
|---|---|---|
| A. Ad-hoc debug screens per feature | Each component gets its own fake-data preview/screen | Rejected: doesn't exercise the real list end-to-end; duplicates scaffolding |
| B. Paging library over Room for history | Window messages through DB | Deferred: correct long-term for huge histories, but UI-043 must first measure whether plain in-memory lists of 2000 actually jank (per §23: measure first). Revisit if stress shows memory/CPU pressure |
| C. Dedicated stress harness driving the real pipeline (chosen) | Deterministic generator → real `FlashMessageList` → profile with standard tooling | Chosen: zero production-code changes, exercises actual bubble/list cost, deterministic across agents/devices |

## What worked (already present in the codebase — code-review findings)

- `FlashMessageList`: `reverseLayout = true` with `messages.asReversed()` (O(1) view);
  stable unique keys (`message.id`); constant `contentType = "flashMessage"` so recycling reuses
  same-shaped slots (sources 1, 12); typing bubble separated by its own key/contentType.
- `derivedStateOf` gates the "at bottom" check → recomposition only when crossing the threshold
  (source 2).
- Entrance animation is pure `graphicsLayer { }` (alpha/scale/translationY read `enterProgress`
  during draw phase — deferred reads; no recomposition per frame). Press scale likewise
  graphicsLayer-only (motion-system.md already flagged this as safe pending profiling).
- `animateItem` placement springs use `FlashMotion` tokens keyed off the motion system.
- Image grid items use null URI → seed-color gradient fallback with no bitmap decode path
  (verified in `FlashImageGrid.kt` `produceState` block) — stress images render cheap by design.

## What did not work / known costs (code-review level — NOT measured)

- `BoxWithConstraints` wraps every bubble → subcomposition per message item (known cost;
  needed for fraction-based max width; do not remove without measuring).
- `buildHighlightedMessageText` rebuilds an `AnnotatedString` for every visible bubble whenever
  `searchQuery` changes — expected O(visible × text length) per keystroke (search-ui.md predicted
  this; quantify here).
- In `itemsIndexed`, when `showSenderHeaders = false`, each item does `message.copy(...)` during
  composition — allocation + equals cost per visible item per recomposition pass.
- Every bubble allocates fresh lambdas (`onOpenActions = { onOpenMessageActions(message) }`, etc.)
  per item; without strong skipping guarantees this defeats skippability of `FlashBubbleSurface`
  (source 5). Candidate fix later: hoist/stable-lambda patterns — but only after profiling.
- `animateItem` placement springs can be interrupted mid-flight when an item scrolls out of view
  (item composition disposed — source 11). Current specs are short; verify no visual popping at
  high fling speeds in the stress harness.

## Chosen approach

Two-part harness:

1. **`FlashStressMath`** (pure object, unit-tested): xorshift64-seeded generator producing a
   deterministic mix of text-only, reacted, image (null URI), voice (procedural amplitudes),
   file-card, and quoted-reply (every 7th) messages; group positions computed via the production
   `computeMessageGroupPositions`. O(n) total; ~2000 msgs « 100 ms target (CI guard: <1 s).
2. **`FlashStressTestScreen`**: preset chips (100/500/1000/2000), generation-time stat chip,
   renders the **real** `FlashMessageList` unchanged inside a minimal Scaffold. Debug/QA only —
   not part of production navigation.

## Why it was chosen

- Deterministic ⇒ two agents/devices compare apples to apples (§33 continuity requirement).
- Reusing the production pipeline means measurements transfer directly to the shipping screen.
- No new dependencies (benchmark artifacts intentionally excluded — see Recommendations).

## Component-cost inventory (code-review estimates — to be confirmed by measurement)

| Per-message composable | Cost class | Notes |
|---|---|---|
| `FlashTypingBubble` (single keyed item) | negligible | One instance |
| Sender header row (`FlashAvatar` + `FlashText`) | low | Avatar gradient + text; only for incoming group headers |
| Bubble surface (clip+background+border+combinedClickable) | medium | `BoxWithConstraints` subcomposition; press-scale is draw-phase ✓ |
| Text body `FlashText` | low | Plain string unless search active |
| Search highlight `AnnotatedString` build | medium (query-change bursts) | Rebuilt per visible bubble per keystroke (UI-023) |
| `FlashImageGrid` (null URI) | low-medium | Gradient boxes, no decode; real URIs shift cost to bitmap decode/cache (out of scope here) |
| `FlashVoiceMessageCard` waveform Canvas | low | Pure draw; amplitudes pre-resampled |
| `FlashFileMessageCard` | low | Static card |
| `FlashReactionsDock` | low-medium | FlowRow + chips; only when reactions exist |
| Quoted reply card | low | Static content + click |
| `animateItem` placement spring | low per frame | Modifier/layout-phase; risk only when item leaves viewport mid-animation (source 11) |
| Entrance `graphicsLayer` | low per frame | Draw-phase only ✓ |

## Measurement plan (device — PENDING; record results in logs/experiments.md)

### Tooling
- **Frame metrics (quick):** `adb shell dumpsys gfxinfo com.transfer.flash reset` → scripted fling
  through the stress screen → read janky_frames %, P50/P90/P95/P99.
- **Macrobenchmark (rigorous):** separate `:benchmark` module (recommended, see below);
  `FrameTimingMetric` + `StartupTimingMetric`, `CompilationMode.None()` vs `Partial(Require)`
  to isolate Baseline-Profile gains; `StartupMode.WARM` for scroll tests; navigate to the stress
  screen via a deep link/testTag + UI Automator fling.
- **Memory:** Studio Profiler heap dump on the 2000-message preset; filter duplicate bitmaps;
  track `Bitmap` Native Size during album-grid scrolling; allocations recording while switching
  presets (watch for retained compositions — source 13).
- **Recomposition counting:** Layout Inspector recomposition counts or compiler metrics reports
  (`composables.txt` skippability audit) on `ui/chat` module.

### Bottleneck classification (fill after measurement — AGENTS.md §23)

| Hypothesis | Classification test | Expected evidence |
|---|---|---|
| Scroll jank at 2000 msgs | FrameTimingMetric during fixed fling | CPU-bound if frameDurationCpuMs↑ with message complexity; GC-bound if paired with alloc spikes |
| Search highlight lag | gfxinfo during typing into search bar | CPU-limited; correlate with AnnotatedString rebuilds |
| Memory growth over preset switches | Heap dump diff | storage-limited unlikely here; look for retained item compositions |
| Insertion burst (rapid arrivals) | Simulated N-msg burst while scrolled up | protocol/network-limited out of UI scope; watch unseen-pill recompositions |
| Voice waveform scroll cost | Fling through voice-heavy segment | Draw-limited if frames fine on CPU but RenderThread spikes |

### Pass/fail targets (to validate — not assumptions)
- Sustained fling at 1000 messages: no frame >16 ms sustained (i.e., janky-frame % ≈ 0 on a
  60 Hz device; frameOverrunMs median ≤ 0); isolated single-frame outliers tolerated but logged.
- Preset switch 100→2000: generation + first composition < 300 ms perceived (TTID-style).
- Steady-state heap at 2000-message preset: no growth >10 MB across 5 preset round-trips.
- Recomposition count on tail-insertion: only affected item + pill recompose (verify via
  Layout Inspector), never the whole LazyColumn.

## Implementation notes

- Target files: `FlashStressTestScreen.kt` (screen + `FlashStressMath`), `FlashStressLogicTest.kt`
  (JUnit4 pure-logic tests, matching the `*LogicTest` convention).
- Dependencies added: **none** (hard rule). Macrobenchmark/Baseline-Profile artifacts are a
  documented recommendation only (below).
- Public API:
  ```kotlin
  object FlashStressMath {
      fun generateMessages(count: Int, seed: Long = 42L): List<FlashMessageUi>
      fun stressPresets(): List<Int>          // [100, 500, 1000, 2000]
      fun estimatedItemBytes(): Int           // ~640 B/item rough retained-size estimate
  }
  @Composable fun FlashStressTestScreen(onBack: () -> Unit, modifier: Modifier = Modifier)
  ```
- Preview uses the internal 50-message variant to keep preview rendering fast.

### Entry-point wiring (LEAD TASK — snippet provided, wiring deliberately left to lead)

Hidden debug entry behind `BuildConfig.DEBUG`, e.g. long-press on the chat-list header:

```kotlin
// FlashChatListScreen.kt (lead wires; not done by UI-042/UI-043 agent per file ownership)
var showStress by remember { mutableStateOf(false) }
FlashChatHeader(
    state = state.header,
    onBack = onBack,
    modifier = Modifier.combinedClickable(
        onClick = onHeaderClick,
        onLongClick = { if (BuildConfig.DEBUG) showStress = true },
    ),
)
if (showStress) {
    FlashStressTestScreen(onBack = { showStress = false })
}
```

Alternative: navigation route `"debug/stress"` registered only in debug builds.

## Accessibility requirements

Stats chip exposes `contentDescription` ("Stress stats: …") but no live region — it is a QA
surface and must not spam TalkBack. Preset chips are clickable with text labels. Screen itself is
debug-only; full a11y audit not required (documented limitation).

## Testing checklist

- [x] JUnit4 pure-logic tests: determinism, count honored, attachment-kind mix incl. every-7th
      replies, presets list, item-byte estimate bounds, 2000-msg generation <1 s CI guard
- [x] Compose preview (internal 50-message body)
- [ ] Physical device: scroll/fling all four presets, record gfxinfo (PENDING)
- [ ] Macrobenchmark module numbers (PENDING — recommended follow-up)
- [ ] Memory profile at 2000 msgs incl. preset round-trips (PENDING)
- [ ] Dark mode spot-check (uses theme tokens throughout — low risk)
- [ ] Reduced-motion behavior inherited from `FlashMotion` via the real list (no extra work expected)

## Known limitations

- Synthetic images carry no bitmaps, so bitmap-decode/cache costs (the heaviest media cost in
  production) are **not** exercised by this harness; album-scroll bitmap profiling needs real
  content or a seeded MediaStore fixture (follow-up).
- 2000-message ceiling: master plan asks for 5k/10k datasets; presets kept ≤2000 until a device
  run proves headroom (generator is size-independent — raising presets is a one-line change plus
  test update).
- Harness measures the conversation list only; chat-list (UI-003) stress is a separate future task.

## Future improvements

1. Add `:benchmark` Macrobenchmark + Baseline Profile Gradle modules (new dependencies — requires
   ADR per §34 rules; recommended because manual gfxinfo runs don't produce regression-gating CI
   numbers).
2. Seed real-bitmap fixtures behind a flag to profile image decode paths.
3. Extend presets to 5k/10k once device baseline exists.
4. Stability audit of `ui/chat` via Compose compiler metrics; consider strong-skipping implications
   for per-item lambdas (only if profiling shows skipped-recomposition misses matter).

## What makes this Flash?

The harness treats performance as part of the P2P identity: conversations here are long-lived,
offline-tolerant threads between peers, so the app is engineered — and *proven* — to stay fluid at
thousands of locally-stored messages without a server paging things out. Telegram-grade fluidity
with receipts: every claim above stays unverified until a device signs it.

---
**Last updated:** 2026-08-22 · UI-042/UI-043 agent · Statuses also tracked in
[ui-research-index.md](ui-research-index.md) (index update owned by lead).
