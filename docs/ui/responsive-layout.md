# responsive-layout

**Status:** DESIGNED → IMPLEMENTED
**Component ID:** UI-034
**Last updated:** 2026-08-22
**Owner phase:** Premium chat UI — component sequence

---

## Component

`FlashWindowSizeClass` / `FlashAdaptiveMath` / `rememberFlashWindowSize` /
`FlashAdaptiveTwoPane`
(`ui/chat/src/main/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLayouts.kt`;
tests: `ui/chat/src/test/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLogicTest.kt`).

## Purpose

Lets Flash present its chat list and conversation **side by side** on tablets,
foldables in book posture, and desktop-class windows, while keeping the existing
single-pane phone behavior byte-for-byte identical. It gives UI-033's navigation
contract a sanctioned way to bypass a Conversation push on wide screens and gives
every future screen one shared answer to "how wide am I?".

## Research sources

- [Use window size classes | Android Developers](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes) — Material width classes: compact < 600dp, medium 600–840dp, expanded ≥ 840dp; compute once, pass down as state.
- [Support different display sizes | Android Developers](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes) — official endorsement of `BoxWithConstraints` for constraint-based branching ("if you want to change *what* you show"), with the documented cost that it defers composition into the layout phase.
- [Build a list-detail layout | Android Developers](https://developer.android.com/develop/adaptive-apps/guides/list-detail) — canonical behavior: expanded shows both panes; medium/compact show one at a time with detail replacing the list; back returns to list; state preserved across size changes.
- [Canonical layouts | Android Developers](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts) — "List-detail is ideal for messaging apps"; placeholder detail pane when nothing is selected.
- [Make your app fold aware | Android Developers](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware) — Jetpack WindowManager `FoldingFeature` (`isSeparating`, tabletop/book posture); requires a new dependency → recorded as future work, not implemented.
- [`WindowSizeClass` API reference](https://developer.android.com/reference/kotlin/androidx/window/core/layout/WindowSizeClass) and [`calculateWindowSizeClass` reference](https://developer.android.com/reference/kotlin/androidx/compose/material3/windowsizeclass/package-summary) — the `material3-window-size-class` API surface evaluated below.

## Existing approaches studied

1. **`androidx.compose.material3:material3-window-size-class` (+ optionally
   `material3-adaptive`'s `ListDetailPaneScaffold`).** Officially maintained
   breakpoints and scaffolds, predictive-back-aware pane navigation, posture support
   via `currentWindowAdaptiveInfo()`. Cost: two new dependency graphs for what Flash
   currently needs (3 buckets + one two-pane row), plus an opinionated pane scaffold
   that would fight our own UI-037 motion tokens and Flash-owned screens.
2. **Manual breakpoints via `BoxWithConstraints`.** The exact pattern the official
   display-sizes guide demonstrates for changing *what* composes. ~60 lines of pure,
   JVM-testable Kotlin; zero dependencies; weights/divider fully under token control.
   Cost: we own breakpoint maintenance and lose saved-state pane navigation.
3. **Jetpack WindowManager posture-aware layout** (`FoldingFeature.isSeparating`,
   book/tabletop). Would let an unfolded foldable split panes along a vertical hinge
   even at medium widths. Cost: new dependency plus flow-collection lifecycle wiring;
   valuable later, over-engineered for this phase.
4. **`LocalConfiguration.screenWidthDp`** (pre-Compose-adaptive apps commonly did
   this). No layout-phase cost, but it reflects *configuration*, not actual window
   constraints — wrong under multi-window/split-screen resizing, which is exactly
   where adaptive layouts matter.

## What worked

- Material's three-bucket model itself: it matches real device widths (phones ≈360–480dp,
  large phones/small tablets ≈600–800dp, tablets/unfolded ≥840dp) and is now the
  ecosystem-wide contract, so design review vocabulary is shared.
- Canonical list-detail rules (both panes on expanded; detail wins single-pane;
  placeholder keeps geometry stable) map cleanly onto ChatList/Conversation without
  any screen rewrite — the container owns the branching.
- Passing the size class *down as state* (per official guidance) instead of letting
  every leaf re-query keeps recomposition scoped to the container.

## What did not work

- **Reading `LocalConfiguration`** — rejected (see approach 4): stale under
  split-screen/window resize.
- **Adopting `ListDetailPaneScaffold` now** — deferred, not rejected forever: its
  navigator/back-stack interplay duplicates part of UI-033's job before the lead has
  wired either together; revisiting after integration QA is cheaper than untangling
  two navigation models simultaneously.
- A naive `remember { }`-of-a-local inside `BoxWithConstraints` content does **not**
  propagate (the content lambda runs deferred during layout) — the working shape
  hoists the result into snapshot state and converges one frame after first layout.

## Chosen approach

Approach 2 — zero-dependency manual breakpoints:

```text
FlashWindowSizeClass (enum: Compact / Medium / Expanded)
FlashAdaptiveMath    (pure: 600/840 breakpoints, isTwoPaneAllowed, list/detail weights)
rememberFlashWindowSize() (@Composable, BoxWithConstraints-derived)
FlashAdaptiveTwoPane(listPane, detailPane?, modifier)
```

Expanded: `Row` with list at weight `0.38f`, hairline divider drawn via a
token-styled `Box` (`FlashDimensions.borderHairline` + `FlashTheme.colors.borderSubtle`),
detail at weight `0.62f`; null detail renders an empty placeholder pane so geometry
never jumps when a conversation opens. Medium/Compact: single `Box` showing whichever
pane is non-null, **detail wins** — matching the canonical rule that on narrow widths
a selected conversation takes over the whole window and system back (via the host's
UI-033 `BackHandler`) returns to the list.

## Why it was chosen

The Material breakpoints are public constants; the value-add of
`material3-window-size-class` today is a wrapper around them plus Activity plumbing,
while `material3-adaptive`'s scaffold adds pane-navigation machinery that overlaps
UI-033. Per §3 (no framework without necessity) the honest call is ~60 testable lines
and zero dependencies. **Recommendation, not implementation:** if Flash later needs
posture awareness, saved-state pane restoration, or predictive-back pane transitions,
adopting `androidx.compose.material3:material3-window-size-class` (Apache 2.0,
alternatives: manual code above, or the newer `material3-adaptive`
`currentWindowAdaptiveInfo()` API) becomes strongly justified — migration is
mechanical because all call sites read only the `FlashWindowSizeClass` enum, which can
be backed by `calculateWindowSizeClass` without signature change. Decision ownership
stays with the lead per project rules.

## Visual specification

No new colors, type, or shapes are introduced — deliberately. Panes are transparent
containers over whatever their content draws. The only painted element is the divider:
width `FlashDimensions.borderHairline`, color `FlashTheme.colors.borderSubtle`,
full height. Pane weights (`0.38f` / `0.62f`) live in `FlashAdaptiveMath` alongside
the breakpoint constants so geometry stays in one auditable place.

## Interaction specification

None added — taps, long-presses, selection, and keyboard focus behave exactly as
inside each pane's own component. On medium/compact, showing the detail pane replaces
the list; returning to the list is the host's back handling concern (UI-033),
not this container's.

## Animation specification

None in this phase. Pane appearance/disappearance on size-class crossing is instant.
A cross-pane fade/slide using `FlashMotion` tokens was considered and deferred: size
transitions are rare events (rotation, fold, window drag) and animating them risks
jank exactly when the whole tree is already relaying out. Revisit with UI-042 profiling.

## Gesture specification

No gesture thresholds introduced. RTL is handled by construction: `Row` lays out
start→end, so list-left/detail-right in LTR mirrors correctly in RTL; the divider sits
between panes in both directions. System-back semantics on narrow widths belong to the
UI-033 host (documented there).

## Accessibility requirements

- No tap targets or semantic nodes added; TalkBack traversal order follows the pane
  composition order (list → detail on expanded), which matches visual reading order.
- Size class derives from **dp**, not px, so large-font/display-size scaling never
  flips a phone into two-pane by accident.
- Reduced motion: trivially satisfied (no animations).
- Contrast: divider uses `borderSubtle`, consistent with existing hairlines; it is
  decorative, not the sole carrier of meaning (pane adjacency conveys the structure).

## Responsive behavior

This component *is* the responsive layer:

| Device class | Typical width | Result |
|---|---|---|
| Phone portrait | 360–480dp | Compact → single pane |
| Phone landscape / small tablet | 540–839dp | Medium → single pane |
| Tablet / unfolded foldable / desktop window | ≥ 840dp | Expanded → list + detail side by side |

Foldables: posture awareness (tabletop/book, hinge separation) intentionally **not**
implemented — requires Jetpack WindowManager (see Existing approaches #3). An unfolded
foldable still lands correctly via plain width. Multi-window/split-screen works
correctly because constraints come from the real window, not `LocalConfiguration`.

## Dark-mode behavior

Nothing painted except the divider, whose `borderSubtle` token resolves through
`FlashColors.dark()` automatically. No palette decisions made here.

## Performance considerations

- `BoxWithConstraints` defers composition to the layout phase (officially documented
  cost); acceptable here because the container wraps each screen exactly once, near
  the root — it must not be nested per-row/per-item.
- `rememberFlashWindowSize` writes through snapshot state guarded by an equality
  check, so unchanged-width recompositions are no-ops; readers update once per
  genuine breakpoint crossing.
- Weight-based `Row` measurement is O(panes) = O(2); no lazy-list impact inside panes.
- First frame after entering composition reports `Compact` before constraints resolve
  (one-frame convergence); harmless at app start, worth remembering in previews.

## Implementation notes

- Files:
  - `ui/chat/src/main/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLayouts.kt`
  - `ui/chat/src/test/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLogicTest.kt`
- Dependencies added: **none** (foundation layout + ui-theme only).
- Public API:

```kotlin
enum class FlashWindowSizeClass { Compact, Medium, Expanded }

object FlashAdaptiveMath {
    const val MediumMinWidthDp = 600f
    const val ExpandedMinWidthDp = 840f
    const val ListPaneExpandedWeight = 0.38f
    const val DetailPaneExpandedWeight = 0.62f
    fun windowSizeForWidth(widthDp: Float): FlashWindowSizeClass
    fun isTwoPaneAllowed(size: FlashWindowSizeClass): Boolean
    fun listPaneWeight(size: FlashWindowSizeClass): Float
    fun detailPaneWeight(size: FlashWindowSizeClass): Float
}

@Composable fun rememberFlashWindowSize(): FlashWindowSizeClass

@Composable fun FlashAdaptiveTwoPane(
    listPane: @Composable () -> Unit,
    detailPane: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
)
```

## Testing checklist

- [x] JUnit4 pure-logic tests: boundary values (599.9→Compact, 600→Medium, 839.9→Medium,
      840→Expanded), degenerate widths, two-pane allowance, pane weights incl. sum-to-one
      (`FlashAdaptiveLogicTest`)
- [ ] Compose preview: two-pane expanded vs compact preview of `FlashAdaptiveTwoPane`
- [ ] Physical device — tablet/emulator resizable: ChatList + Conversation side by side
- [ ] Physical device — phone: zero behavioral change vs current single-pane screens
- [ ] Dark mode: divider visible but unobtrusive on both palettes
- [ ] Large font / display size: no accidental class flip (dp-based)
- [ ] RTL: pane order mirrored correctly
- [ ] Foldable posture: deferred (dependency decision pending)

## Known limitations

- **No screen consumes two-pane yet.** `FlashChatListScreen` and
  `FlashConversationScreen` remain single-pane (read-only for parallel agents);
  integrating them behind `FlashAdaptiveTwoPane`, including the UI-033
  bypass-push-on-expanded wiring, is **the lead engineer's** integration task.
- One-frame convergence of `rememberFlashWindowSize` on first composition (Compact
  default until constraints resolve).
- No fold/hinge posture awareness (would require Jetpack WindowManager — decision
  deferred to lead).
- No pane-transition animation (see Animation spec).
- No saved-state pane selection across process death (single-pane mode relies on
  UI-033's stack, which has the same limitation).

## Future improvements

- Adopt `material3-window-size-class` / posture APIs if revisit triggers fire
  (fold-aware layouts, saved-state pane restore, predictive-back pane transitions).
- Height size classes (e.g., composer/tabletop arrangements) once a use case exists.
- Token-driven pane transition animation via `FlashMotion`, profiled under UI-042.
- Placeholder detail content for expanded-with-no-selection (currently empty surface).

## What makes this Flash?

Most messaging apps treat tablets as stretched phones or import Google's scaffold
wholesale. Flash instead encodes the *smallest honest kernel* of adaptive design —
two numbers, one enum, one container — styled entirely from its own token system, so
the two-pane experience is unmistakably the same product as the phone one: same
hairlines, same surfaces, same P2P-aware list, just more room. And true to the
project's engineering memory culture, the decision to *not* take the official
dependency is documented with its exact revisit triggers rather than silently buried.
