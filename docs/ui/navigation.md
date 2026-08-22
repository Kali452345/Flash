# navigation

**Status:** IMPLEMENTED
**Component ID:** UI-033
**Last updated:** 2026-08-22
**Owner phase:** Premium chat UI — component sequence

---

## Component

`FlashNavigationState` / `FlashDestination` / `FlashBackStackState` / `FlashAnimatedScreen`
(`ui/chat/src/main/java/com/transfer/flash/ui/navigation/FlashNavigation.kt`).

## Purpose

Provides the screen-switching contract for the Flash showcase app: which screens exist
(ChatList → Conversation, plus Transfers and NearbyDevices per AGENTS.md §22 primary flows),
how pushes/back form a stack, and how the visible screen transition animates using the
UI-037 motion tokens reserved for exactly this component (`screenEnter()` / `screenExit()`).
It replaces ad-hoc boolean flags in `MainActivity` (`showConversation`, `showLanHome`, …)
with a single source of truth once the lead engineer wires it in.

## Research sources

- [Add support for the predictive back gesture](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture) — `BackHandler` vs `PredictiveBackHandler`; innermost enabled handler wins; intercepting back at root disables back-to-home animation.
- [Set up Predictive back | Jetpack Compose](https://developer.android.com/develop/ui/compose/system/predictive-back-setup) — predictive-back is default-on Android 15+; Navigation Compose 2.8+ needed for built-in predictive support; `popEnterTransition`/`popExitTransition` for distinct back animations.
- [Provide custom back navigation](https://developer.android.com/guide/navigation/custom-back) — Compose `BackHandler` semantics; enable/disable driven by observable UI state, not conditionals after the fact.
- [Add custom in-app transitions (PredictiveBackHandler)](https://developer.android.com/guide/navigation/custom-back/support-animations) — gesture-progress-driven animation pattern.
- [Type safety in Kotlin DSL and Navigation Compose](https://developer.android.com/guide/navigation/design/type-safety) — `@Serializable` route objects/data classes (Navigation 2.8+).
- [Navigation with Compose](https://developer.android.com/develop/ui/compose/navigation) — NavHost/NavController model, dependency footprint (`androidx.navigation:navigation-compose` + kotlinx-serialization plugin).
- [Encapsulate your navigation code](https://developer.android.com/guide/navigation/design/encapsulate) — keeping nav code out of screens; screens stay stateless via callbacks.
- [Conditional navigation with Navigation Compose (programminghard.dev)](https://programminghard.dev/compose-navigation/) — pitfalls of mixing reactive sealed-class state with imperative `NavController`: two sources of truth; argues for a single NavHost when using the library.

## Existing approaches studied

1. **Navigation-Compose library** (`NavHost` + type-safe `@Serializable` routes, 2.8+).
   Full-featured: back stack management, saved-state restoration, deep links, predictive
   back transitions, argument passing. Cost: new dependency (`navigation-compose`,
   `kotlinx-serialization` plugin), graph boilerplate for 4 destinations, and a second
   mental model alongside Compose state.
2. **Sealed-class destination + manual `when` rendering** (the current MainActivity pattern,
   formalized). Zero deps but booleans/scoped flags scale badly, no stack semantics, easy to
   create two sources of truth (the pitfall documented by programminghard.dev).
3. **Plain state-holder class + generic `AnimatedContent` wrapper** (chosen): an explicit
   tiny state machine (`FlashNavigationState`) owning the stack, rendered through one
   generic `FlashAnimatedScreen`. No new dependencies; logic is pure JVM and unit-testable;
   single source of truth preserved because UI derives entirely from `state.current`.

## What worked

- Single-source-of-truth principle (official docs + programminghard.dev): render *only* from
  `current`; never mix imperative jumps with reactive flags.
- Back handling as observable state: `canGoBack` lets the host enable/disable `BackHandler`
  declaratively instead of conditional interception (official best practice).
- Duplicate-consecutive-push guard mirrors what NavController effectively does when you
  double-tap a list row; making it explicit prevents stacked duplicates of Conversation.
- Generic `AnimatedContent(targetState)` wrapper keeps transition policy in one place and
  reuses the already-shipped UI-037 tokens (reduce-motion aware for free).

## What did not work

- **Boolean-flag switching (current MainActivity showcase)** — rejected as the end state:
  N flags → 2^N implicit states, back handling duplicated per flag, no depth cap.
- **Adopting navigation-compose now** — deferred (see decision below), not rejected forever.
- Mixing reactive screen-state selection *inside* a NavHost (two sources of truth) —
  documented failure mode in research; our design avoids it by having exactly one authority.

## Chosen approach

Dependency-free contract:

```text
FlashDestination (enum, titles)
FlashBackStackState (destination + conversationId, isValid())
FlashNavigationMath (pure helpers: MAX_STACK_DEPTH=10, nextStackSize, shouldIgnorePush, resolveConversationTitle)
FlashNavigationState (private MutableList stack; navigate/back/canGoBack/current)
rememberFlashNavigationState() (@Composable remember wrapper)
FlashAnimatedScreen<T> (AnimatedContent + FlashTheme.motion.screenEnter() togetherWith screenExit())
```

Host wiring sketch (for the lead engineer — MainActivity not modified by this agent):

```kotlin
val nav = rememberFlashNavigationState()
BackHandler(enabled = nav.canGoBack) { nav.back() }
FlashAnimatedScreen(targetState = nav.current) { entry ->
    when (entry.destination) {
        ChatList      -> FlashChatListScreen(onConversationClick = { id ->
            chatRepository.openConversation(id)
            nav.navigate(Conversation, conversationId = id)
        }, …)
        Conversation  -> FlashConversationScreen(
            onBack = { nav.back(); /* repo close on leaving Conversation */ }, …)
        Transfers     -> …
        NearbyDevices -> …
    }
}
```

## Why it was chosen

Flash has **4 flat destinations and one parameterized edge** (Conversation(id)). The
nav-library value-adds we would actually use today are stack bookkeeping and a transition —
~60 lines of pure Kotlin here, zero dependencies, fully unit-testable without instrumentation.
This matches the project rule against adding frameworks without necessity (§3) while still
giving predictive-back-compatible semantics: `back()` returning false at root lets system
back fall through so the OS back-to-home animation plays (official guidance). Honest
trade-off acknowledged: we give up saved-state restoration across process death, deep links,
and gesture-progress-driven predictive animations — all acceptable for the showcase phase.

**Decision note:** `androidx.navigation:navigation-compose` was evaluated and **deferred**,
not rejected. Revisit triggers: (a) more than ~6 destinations or nested graphs, (b) deep-link
requirements (e.g., opening a conversation from a notification), (c) need for automatic
process-death back-stack restore, (d) multi-module nav graphs. Migration path is clean:
each `when` branch becomes a `composable<FlashBackStackState>` route.

## Visual specification

None of its own — this component draws no chrome. Titles come from
`FlashDestination.title` / `FlashNavigationMath.resolveConversationTitle()` and must be
rendered by screens via `FlashText`.

## Interaction specification

- Forward: caller invokes `navigate(destination, conversationId?)`.
- Back: host exposes `BackHandler(enabled = nav.canGoBack) { nav.back() }`.
- `back()` at root returns `false` → host must NOT consume back → system handles exit.
- Pushing the exact current `(destination, conversationId)` is a no-op (double-tap safe).
- Pushing `Conversation` without a valid id is rejected (`isValid()` guard).

## Animation specification

Forward push and back pop both run `FlashTheme.motion.screenEnter() togetherWith
screenExit()` inside `AnimatedContent(label="flashNavigation")`: incoming screen fades +
slides from right (~30% width, slowMillis, Decelerate); outgoing fades + slides left
(slowMillis, Accelerate). Under reduce-motion both collapse to none/no-offset (tokens handle
this). Interruptible by construction (AnimatedContent retargets mid-flight).

## Gesture specification

System back / gesture handled via `BackHandler` at the host level (innermost enabled wins;
none enabled at root → predictive back-to-home plays). Gesture-progress-driven predictive
animation (per `PredictiveBackHandler`) is intentionally out of scope for this phase — see
Known limitations.

## Accessibility requirements

- Reduce-motion respected automatically through UI-037 tokens.
- Screen content swaps should be announced by the platforms' focus move into the new
  screen's header (screens own this); `FlashAnimatedScreen` adds no semantics of its own.
- No tap targets introduced by this component.

## Responsive behavior

Contract is layout-agnostic. For future adaptive layouts (UI-034): list-detail on
tablets can bypass the Conversation push entirely (render both panes); the state holder
does not assume phone-only stacking. `conversationId` on the entry is what enables that.

## Dark-mode behavior

Nothing drawn by this component; colors come from `FlashTheme.colors` inside screens.

## Performance considerations

- Stack ops are O(1) amortized list add/remove; depth capped at 10 entries (memory trivially bounded).
- Only the current screen composes (AnimatedContent composes target during transition, old
  screen leaves composition after exit) — no offscreen screen retention cost.
- Transition cost = one fade + one translation per side; no blurs/shadows.

## Implementation notes

- Files:
  - `ui/chat/src/main/java/com/transfer/flash/ui/navigation/FlashNavigation.kt`
  - `ui/chat/src/test/java/com/transfer/flash/ui/navigation/FlashNavigationLogicTest.kt`
- Dependencies added: **none** (uses compose-animation foundation `AnimatedContent` already
  available transitively; explicitly **no androidx.navigation**).
- Pure logic deliberately free of Compose runtime types; only the two bottom functions
  (`rememberFlashNavigationState`, `FlashAnimatedScreen`) touch Compose.

## Testing checklist

- [x] JUnit4 pure-logic tests: push/back/canGoBack, duplicate-push guard, depth cap with root
      preservation, conversation switch = distinct push, invalid-entry rejection, math helpers
      (`FlashNavigationLogicTest`)
- [ ] Physical device: forward/back transitions between ChatList ↔ Conversation
- [ ] Physical device: back at root exits app with system animation
- [ ] Reduce-motion ON: transitions snap
- [ ] Double-tap a chat row: single push
- [ ] Performance spot-check: transition jank-free during scroll-heavy list handoff

## Known limitations

- **MainActivity showcase wiring is done by the lead engineer** — this agent owns only the
  contract, wrapper, and tests; existing boolean-flag navigation remains live until wired.
- Pop does not play a mirrored (rightward) transition: `screenExit()` slides left for both
  directions since tokens are direction-unaware. Mirrored pop needs either paired
  enter/exit token sets or `PredictiveBackHandler` progress — deferred.
- No process-death restoration (stack lives in `remember`). Add `rememberSaveable`-style
  parcelization if the showcase must survive process death.
- No deep links, no nested graphs, no multi-pane coordination yet (UI-034 will define).

## Future improvements

- Direction-aware transitions (push vs pop variants) via `AnimatedContent` comparing
  previous/target states.
- Optional predictive-back progress integration (`PredictiveBackHandler`) for a
  gesture-following pop animation.
- Wire-up in MainActivity replacing boolean flags (lead engineer).
- Re-evaluate navigation-compose adoption at any revisit trigger listed above.

## What makes this Flash?

Navigation is invisible when done right — its Flash identity shows in the details: a
motion-token-driven slide-fade shared with every other surface, reduce-motion honored by
default, a deliberate duplicate-push guard so fast P2P chat-list taps never stack screens,
and a zero-dependency core that stays honest about what a four-screen app actually needs
instead of importing a framework's complexity wholesale.
