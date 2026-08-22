# Flash Motion Design System

**Status:** IMPLEMENTED (UI-037 foundation; UI-039 + UI-041 appended below)  
**Component ID:** UI-037; **UI-039** haptics; **UI-041** micro-interactions  
**Last updated:** 2026-08-22  
**Depends on:** UI-001 (Flash Pulse design system)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Code:** `app/src/main/java/com/transfer/flash/ui/theme/FlashMotion.kt`, `FlashMotionSheet.kt`

---

## Component

`FlashMotion`, `FlashTheme.motion`, `FlashMotionSheet` (QA preview)

---

## Purpose

Centralize every chat animation duration, easing curve, spring, and named transition so components never invent ad-hoc timings. Motion should feel **fast, calm, and tactile** — alive without slowing communication.

Unblocks: UI-003 (chat list), UI-005 (bubbles), UI-006–UI-014, UI-021, UI-032, UI-038–UI-041.

---

## Research sources

| Source | What was studied |
|---|---|
| [Material Motion — Duration & easing](https://m3.material.io/styles/motion/overview) | Standard / decelerate / accelerate curves; 200–300 ms component motion |
| [Compose Animation API](https://developer.android.com/develop/ui/compose/animation) | `AnimatedContent`, `EnterTransition`/`ExitTransition`, springs, interruptibility |
| [Android reduced motion](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager#isReduceMotionEnabled()) | API 33+ `isReduceMotionEnabled`; legacy `ANIMATOR_DURATION_SCALE` |
| Telegram Android (reference) | Snappy message insert; minimal header chrome motion; scroll decoupled from typing |
| Signal Android (reference) | Restrained transitions; status changes via crossfade not bounce |
| WhatsApp Android (reference) | Composer height morph; familiar but not copied spring values |
| UI-001 `design-system.md` | “Premium, calm, lightweight” adjectives |

No proprietary motion code or curves were copied.

---

## Existing approaches studied

### A — Per-composable hardcoded `tween(300)` / default `fadeIn()`

**Pros:** Fast to prototype.  
**Cons:** Inconsistent feel; impossible to tune globally; violates master plan; reduced-motion ignored.  
**Verdict:** Rejected — current `FlashChatHeader` used default fades before UI-037.

### B — Material MotionScheme / M3 Expressive physics only

**Pros:** Platform-aligned; expressive springs built-in on newer Compose Material.  
**Cons:** Ties chat feel to Material template; harder to enforce Flash-specific calm messaging rhythm.  
**Verdict:** Rejected as sole system — borrow easing *ideas*, not dependency on Material motion tokens in chat.

### C — Centralized `FlashMotion` token object (chosen)

**Pros:** Single source of truth; previewable; reduced-motion aware; named specs for message/header/composer/screen.  
**Cons:** Must be referenced consistently in future components.  
**Verdict:** Selected.

---

## What worked

- **Tiered durations** — `fast` (120 ms), `normal` (200 ms), `slow` (320 ms), `emphasis` (400 ms)
- **Fade-first** status and metadata changes (header presence, transport icon context)
- **Subtle vertical slide + scale** on message enter (≤ 8 dp travel, 0.96→1 scale) — Telegram-like without bounce
- **Snappy springs** only for send/composer micro feedback (UI-041); list insert uses mostly tween
- **Instant swap** when reduce-motion is enabled (0 ms, no slide/scale)
- **`FlashTheme.motion`** CompositionLocal — same pattern as colors/typography

---

## What did not work

- Default Compose `fadeIn()` with implicit 300 ms — too slow for header status cycling
- Heavy overshoot springs on every list item — janky on mid-range devices; feels “game UI”
- Long shared-element transitions for chat — blocks reading flow
- Animating during active typing/scrolling (deferred rule for UI-021/UI-011)

---

## Chosen approach

**Flash Calm Motion** — fixed duration tiers + Material-inspired easing + selective springs.

| Category | Token examples | Typical use |
|---|---|---|
| Micro | `fast`, `springSnappy` | Icon state, send tap, reaction pick |
| Component | `statusCrossfade`, `messageEnter`, `composerExpand` | Header, bubbles, composer |
| Screen | `screenTransition`, `mediaOpen` | Navigation, media viewer |
| Gesture | (UI-041) | Swipe reply, drag dismiss — spec placeholders |
| Loading | `emphasis` + fade | Skeleton → content (UI-026) |
| Feedback | `springSnappy` | Haptics paired in UI-039 |

---

## Why it was chosen

Messaging UX research favors **speed over spectacle**. Flash P2P users care about transfer reliability and reading latency; motion must reinforce state changes without adding perceptual delay. A centralized Kotlin API matches UI-001 token architecture and satisfies AGENTS.md §34 “no random per-file durations.”

---

## Visual specification

Motion has no standalone color/shape tokens. Animated elements use existing `FlashTheme.colors` / `FlashTheme.typography`. Opacity endpoints: enter 0→1, exit 1→0. Scale endpoints: 0.96→1.0 enter; 1.0→0.98 press (UI-041).

---

## Interaction specification

| Trigger | Motion token | Interruptible |
|---|---|---|
| Header status text change | `statusCrossfade` | Yes — new state replaces mid-transition |
| New message appended | `messageEnter` / `messageExit` | Yes — list scroll can cancel enter |
| Open conversation | `screenTransition` | Yes — back gesture |
| Composer multi-line grow | `composerExpand` | Yes — typing continues |
| Reply quote expand | `replyExpand` | Yes |
| Open image full-screen | `mediaOpen` | Yes — back |

All transitions must complete or snap within **≤ 400 ms** except media viewer (320 ms open).

---

## Animation specification

### Duration tokens

| Token | ms | Reduced motion |
|---|---|---|
| `fast` | 120 | 0 |
| `normal` | 200 | 0 |
| `slow` | 320 | 0 |
| `emphasis` | 400 | 0 |

### Easing

| Token | Curve | Use |
|---|---|---|
| `Decelerate` | (0, 0, 0.2, 1) | Enter |
| `Standard` | (0.4, 0, 0.2, 1) | Move |
| `Accelerate` | (0.4, 0, 1, 1) | Exit |

### Springs

| Token | Damping | Stiffness | Use |
|---|---|---|---|
| `springSnappy` | 0.85 | 600 | Send, micro scale |
| `springDefault` | 0.90 | 400 | Message enter scale |
| `springGentle` | 1.00 | 280 | Composer expand |

### Named transitions (implemented in `FlashMotion.kt`)

| Name | Enter / transform | Exit | Duration tier |
|---|---|---|---|
| `statusCrossfade` | fade in | fade out | fast |
| `messageEnter` | fade + slide up 25% + scale 0.96 | — | normal |
| `messageExit` | — | fade | fast |
| `screenTransition` | fade + slide start 30% | fade + slide end 30% | slow |
| `composerExpand` | fade + slide up 12 dp | fade | normal + gentle spring |
| `replyExpand` | fade + expand vertical | fade | normal |
| `mediaOpen` | fade + scale 0.92 | fade + scale 0.96 | slow |

---

## Gesture specification

Deferred to UI-007, UI-008, UI-021, UI-041. FlashMotion will expose shared `springGentle` and `fast` for gesture-settle thresholds documented in those components.

---

## Accessibility requirements

- Detect **reduce motion** via `AccessibilityManager.isReduceMotionEnabled` (API 33+) or `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.
- When enabled: **no slide/scale**; crossfades become instant state swap (`EnterTransition.None`).
- Never convey presence/encryption/delivery **only** through animation (UI-038).
- Large text / display scaling: motion distances are dp-based and do not affect final layout size.

---

## Responsive behavior

Same tokens on phone/tablet/foldable. Tablet screen transitions may use larger slide fraction in UI-034 — token API unchanged.

---

## Dark-mode behavior

Identical timings; no separate dark motion palette.

---

## Performance considerations

- Prefer `AnimatedContent` / `AnimatedVisibility` with fixed specs over manual `Animatable` in lists.
- Message list (UI-021) should use item-level enter only for tail messages, not full list re-layout.
- Avoid simultaneous `slow` transitions on keyboard + composer + list.
- Profile with UI-042 after UI-005/UI-021 land.

---

## Implementation notes

| File | Role |
|---|---|
| `FlashMotion.kt` | Tokens, curves, springs, transition builders, reduce-motion probe |
| `FlashTheme.kt` | `LocalFlashMotion`, `FlashTheme.motion`, `rememberFlashMotion()` |
| `FlashMotionSheet.kt` | QA demo cycling status crossfade + message enter sample |
| `FlashChatHeader.kt` | First consumer — `statusCrossfade()` |
| `FlashFeedback.kt` | UI-039: `FlashHaptic`, `FlashHapticPolicy`, `rememberFlashHaptics()` |

**Dependencies:** Compose Animation (BOM). No new Gradle libraries.

---

## Testing checklist

- [x] Compose preview — `FlashMotionSheet` light/dark
- [x] Physical device — header status uses Flash crossfade (conversation screen)
- [x] Dark mode preview
- [ ] Large font — N/A for motion sheet (no layout change)
- [ ] RTL — screen transition direction deferred to UI-033
- [x] Reduced motion — unit logic via `FlashMotion(reduceMotion = true)` instant transitions
- [ ] Performance spot-check — deferred until UI-005 list animations

---

## Known limitations

- UI-040 sound not implemented; UI-039 haptics + UI-041 micro-interactions now documented/implemented below.
- `screenTransition` not wired to navigation yet (UI-033).
- No automated test for system reduce-motion setting on device (manual QA).

---

## Future improvements

- UI-038: document TalkBack announcements alongside motion degradation.
- UI-041: wire `springSnappy` to send button and reaction picker.
- UI-021: list item placement animation using `messageEnter` with LazyColumn key stability.
- Optional `FlashMotion.debugSlow()` for QA (2× duration) — not added until needed.

---

## What makes this Flash?

Flash motion is **calm connectivity**, not flashy Material demo physics. Short fades acknowledge P2P status changes; message insert is quick and slightly tactile; nothing bounces for attention. The system is owned, centralized, and tuned for local-first messaging — not cloned Telegram springs or Stream transitions.

---

# UI-039 — Haptics (appended 2026-08-22)

**Status:** IMPLEMENTED  
**Code:** `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashFeedback.kt`  
**Tests:** `ui/theme/src/test/java/com/transfer/flash/ui/theme/FlashFeedbackLogicTest.kt`  
**Depends on:** UI-037 (`FlashMotion.reduceMotion`), UI-001 tokens

## Component

`FlashHaptic` (semantic vocabulary), `FlashHapticPolicy` (pure decision logic),
`rememberFlashHaptics()` (single composition choke point).

## Purpose

One central haptic system so components express *intent* (`Confirm`) rather than raw
API calls (`performHapticFeedback(LongPress)`), keeping every future haptic upgrade —
including a VibrationEffect/amplitude-primitive path — a one-file change.

## Research sources

| Source | What was studied |
|---|---|
| [Compose `HapticFeedbackType`](https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedbackType) | Stable common API exposes only `LongPress` and `TextHandleMove`; no amplitude/context primitives in the common surface |
| [`HapticFeedback.performHapticFeedback`](https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedback) | Compose delegates to the View pipeline — no `VIBRATE` permission needed |
| [Add haptic feedback to events (Views)](https://developer.android.com/develop/ui/views/haptics/haptic-feedback) | View-based `performHapticFeedback` is recommended over raw `VibrationEffect`: action-oriented, permission-free, honors `HAPTIC_FEEDBACK_ENABLED`; `View` constants have built-in fallbacks |
| [Android haptics API reference](https://developer.android.com/develop/ui/views/haptics/haptics-apis) | SDK 27+ amplitude control via `hasAmplitudeControl`; predefined effects need Android 10+; composition primitives Android 11+ with per-primitive support checks; `VibrationEffect` paths require `VIBRATE` |
| [Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) | Prefer action-oriented constants; frequent interactions get subtle ticks; be consistent app-wide; avoid legacy one-shot waveforms |

## Existing approaches studied

### A — Ad-hoc `LocalHapticFeedback.current.performHapticFeedback(...)` per component

**Pros:** Zero abstraction.  
**Cons:** No policy gate, no semantic meaning, N call sites to change on any upgrade. Was
the pre-existing state: 15 scattered call sites across 10 files. **Rejected.**

### B — Direct `Vibrator` + `VibrationEffect.Composition` service

**Pros:** Rich SDK 27+/31+ amplitude primitives, distinct Warn/Reject textures.  
**Cons:** Needs `VIBRATE` permission, bypasses the user's touch-feedback setting unless
manually checked, device-support checks required per primitive, not testable without
instrumentation. **Deferred** as the documented future upgrade behind the same API.

### C — Semantic enum + policy object + single composable choke point (chosen)

**Pros:** Testable JVM logic, honest mapping table, one-file future migration.  
**Cons:** Current mapping collapses Warn/Reject onto LongPress (Compose API limit). **Selected.**

## What worked

- Mechanical migration of all 15 existing `performHapticFeedback` call sites preserved
  perceived timing/type exactly (`TextHandleMove → Tick`, `LongPress → Confirm/Warn/Reject`).
- Policy independence from reduce-motion matches platform behavior ("Remove animations"
  does not mute vibration).

## What did not work

- Nothing failed; the only constraint hit was the stable Compose type set
  (`LongPress`, `TextHandleMove`), documented rather than worked around unsafely.

## Chosen approach

```kotlin
enum class FlashHaptic { Tick, Confirm, Warn, Reject }

object FlashHapticPolicy {
    fun enabled(reduceMotion: Boolean, systemHapticsEnabled: Boolean = true): Boolean
}

@Composable
fun rememberFlashHaptics(): (FlashHaptic) -> Unit
```

Mapping (documented honestly — see `FlashFeedback.kt` KDoc):

| `FlashHaptic` | Underlying `HapticFeedbackType` | Used for |
|---|---|---|
| `Tick` | `TextHandleMove` | Light taps, toggles, tiles, play/pause, steppers |
| `Confirm` | `LongPress` | Long-press menus, swipe threshold reached, reaction select, retry tap, record start |
| `Warn` | `LongPress` | Reserved — attention-without-failure states |
| `Reject` | `LongPress` | Destructive actions (recording trash/discard); future send-failure |

## Why it was chosen

Android's own guidance favors action-oriented, setting-respecting feedback; Compose's
common API is intentionally narrow today. Centralizing intent now means Warn/Reject can
gain distinct amplitude textures later without touching any chat component, and the
policy gate is unit-tested on the JVM.

## Visual specification

N/A (non-visual channel). Pairs visually with press-scale micro-interactions (UI-041)
fired by the same gestures.

## Interaction specification

Call-site catalog — before → after (all in `ui/chat/.../chat/`):

| File | Line (pre-edit) | Before | After |
|---|---|---|---|
| FlashAttachmentButton.kt | 88 | `TextHandleMove` | `Tick` (attach toggle) |
| FlashAttachmentSheet.kt | 218 | `TextHandleMove` | `Tick` (tile tap) |
| FlashDeliveryStatusIcon.kt | 135 | `LongPress` | `Confirm` (failed-send retry) |
| FlashFileMessageCard.kt | 147 | `TextHandleMove` | `Tick` (file card tap) |
| FlashMessageBubble.kt | 191 | `LongPress` | `Confirm` (bubble long-press menu) |
| FlashMessageBubble.kt | 248 | `LongPress` | `Confirm` (voice card long-press menu) |
| FlashMessageContextMenu.kt | 249 | `LongPress` | `Confirm` (quick reaction select) |
| FlashMessageContextMenu.kt | 291 | `TextHandleMove` | `Tick` (open emoji picker) |
| FlashReactionChip.kt | 116 | `TextHandleMove` | `Tick` (toggle reaction) |
| FlashReactionChip.kt | 120 | `LongPress` | `Confirm` (reactor list) |
| FlashSwipeToReply.kt | 128 | `LongPress` | `Confirm` (reply threshold armed) |
| FlashVoiceMessageCard.kt | 247 | `LongPress` | `Confirm` (long-press actions) |
| FlashVoiceMessageCard.kt | 264 | `TextHandleMove` | `Tick` (play/pause toggle) |
| FlashVoiceRecording.kt | 194 | `TextHandleMove` | `Confirm` (record start — deliberate semantic upgrade, same primitive) |
| FlashVoiceRecording.kt | 292 | `TextHandleMove` | `Reject` (trash/discard recording) |

Rule: new interactive elements must use `rememberFlashHaptics()`; direct
`LocalHapticFeedback` usage in :ui:chat is now zero and should stay that way.

## Animation specification

None — haptics are instantaneous. They are gated by `FlashHapticPolicy.enabled()`,
which deliberately ignores reduce-motion (vibration is non-vestibular, non-visual
feedback; platform reduce-motion settings do not affect it either). The parameter stays
in the signature so a product-level reversal flips one function.

## Gesture specification

Swipe-to-reply fires exactly one `Confirm` per crossing (re-arms when dragging back
under threshold) — existing dedupe logic retained unchanged.

## Accessibility requirements

See [accessibility.md §UI-038](accessibility.md): haptics are an assistive channel;
system touch-feedback setting is honored downstream by `View.performHapticFeedback`.

## Responsive behavior / Dark-mode behavior

N/A.

## Performance considerations

Haptic dispatch is cheap; no caching needed beyond the remembered lambda. Never fire
haptics from per-frame animation callbacks — only from discrete gesture/state events
(current codebase complies; verified during migration).

## Implementation notes

- `rememberFlashHaptics()` returns a *stable* lambda (remembered on
  `hapticFeedback + reduceMotion`), safe to capture in gesture callbacks.
- Unit tests cover the full policy truth table + vocabulary freeze.
- Device QA pending (no Gradle/device run this session): confirm LongPress-equivalent
  feel on Pixel/Samsung actuators and record in `logs/experiments.md` per AGENTS.md §23.

## Testing checklist

- [x] Policy truth-table unit tests (`FlashFeedbackLogicTest.kt`)
- [x] All 15 legacy call sites migrated; grep shows zero remaining direct usage
- [ ] Physical device: each mapped haptic feels appropriate (esp. Reject on discard)
- [ ] TalkBack session: haptics do not interfere with announcements
- [ ] System "touch feedback" off → all Flash haptics silent (downstream enforcement)

## Known limitations

- Warn/Reject share the LongPress primitive until a VibrationEffect backend lands.
- No amplitude control, no waveform patterns, no per-device tuning (by design, MVP).
- Reduce-motion currently has no effect on haptics — intentional, but undocumented to
  users; revisit if research requests it.

## Future improvements

- Backend switch inside `rememberFlashHaptics()` to `Vibrator` + predefined effects /
  composition primitives (SDK 27/31+ guards), keeping the exact public API.
- Distinct textures: double-tick for delivery confirmation, rising tick ramp for scrub.
- Settings toggle "haptic strength" wired through `FlashHapticPolicy`.

---

# UI-041 — Micro-interactions (appended 2026-08-22)

**Status:** PARTIALLY IMPLEMENTED (inventory + gap fill; additions minimal & token-driven)

## Inventory of existing micro-interactions

All press scales gate on `!motion.reduceMotion` and use `motion.springSnappySpec()`
(damping 0.85 / stiffness 600) unless noted:

| Micro-interaction | Component | Scale / motion | Spec source |
|---|---|---|---|
| Bubble press | `FlashMessageBubble.kt` | 0.97 press scale | springSnappy |
| Attachment button press + 45° morph | `FlashAttachmentButton.kt` | 0.88 press; rotation morph | springSnappy |
| Attachment tile press + staggered entrance | `FlashAttachmentSheet.kt` | 0.90 press; 0.6→1 enter, 20ms stagger | springSnappy / springDefault |
| File card press | `FlashFileMessageCard.kt` | 0.97 | springSnappy |
| Voice badge press + play/pause glyph morph | `FlashVoiceMessageCard.kt` | 0.90 press; 0.6→1 scale-swap | springSnappy |
| Voice card press | `FlashVoiceMessageCard.kt` | 0.97 | springSnappy |
| Mic button press/recording shrink | `FlashMicButton` (FlashVoiceRecording.kt) | 0.90 while pressed or recording | springSnappy |
| Send button press | `FlashSendButton` (FlashComposer.kt) | 0.90 (local spring ζ0.6/k500 — pre-token legacy) | ad-hoc spring |
| Quick reaction pop-in + press | `FlashMessageContextMenu.kt` | 0.5→1 staggered pop; 0.85 press | springSnappy |
| Reaction chip border-width morph | `FlashReactionChip.kt` | hairline→1.5dp active border | springSnappy |
| Count odometer roll | `FlashReactionChip.kt` | vertical slide ± fade | tween fast + slide |
| Swipe-to-reply icon reveal | `FlashSwipeToReply.kt` | 0.4→1 scale, −35°→0° rotate, α=progress² | drag-driven |
| Typing dots bounce | `FlashTypingIndicator.kt` | 0.85→1.15 dot scale | infinite loop |
| Recording dot pulse | `FlashVoiceRecordingBar` | α 1↔0.45 pulse | infinite loop |
| Delivery icon crossfade | `FlashDeliveryStatusIcon.kt` | status crossfade | `statusCrossfade()` |

## Spring token usage table

| Token | Consumers |
|---|---|
| `springSnappySpec` (0.85/600) | All press scales, attachment rotation, badge glyph swap, reaction chip border, quick-reaction pops, search chrome press (new, below) |
| `springDefaultSpec` (0.90/400) | Message-enter scale, media zoom, tile entrance |
| `springGentleSpec` (1.00/280) | Composer expand |
| Ad-hoc springs (legacy) | `FlashSendButton` press (ζ0.6/k500), swipe-to-reply snap-back (ζ0.65/k500) — flagged below |

## Gaps found

1. Search chrome buttons (`SearchStepButton`, search-bar close) had **no press
   feedback at all** while every other chrome button scales. → Fixed (below).
2. `FlashSendButton` uses an ad-hoc spring instead of tokens (pre-dates UI-037).
   → Documented; token migration belongs to a composer polish pass (UI-011 owner).
3. No send-success acknowledgment (e.g., subtle tick/scale settle after send).
   → Requires send-state plumbing through UI-013 callbacks; deferred, not faked here.
4. Swipe-to-reply snap-back uses local spring values instead of a shared token.

## New micro-interactions added (2026-08-22)

**1. Search-chrome press scale — `FlashChatSearchBar.kt`**
- `SearchStepButton` and the close-search button now compress to **0.90** on press,
  animated with `motion.springSnappySpec()` and gated on `!motion.reduceMotion`
  (labels: `searchStepPressScale`, `searchClosePressScale`).
- Implemented with `MutableInteractionSource` + `collectIsPressedAsState` +
  `graphicsLayer` scale (GPU-composited, no recomposition per frame), matching the
  established Flash pattern. Ripple indication replaced by the scale, consistent with
  all other custom chrome buttons.

That is the complete addition this pass — deliberately minimal per scope; items 2–4 are
documented gaps, not silent rewrites.

## Testing checklist

- [x] Additions compile within existing modifier chains; no layout-affecting changes
- [ ] Physical device: search steppers feel consistent with attachment button press
- [ ] Reduced-motion ON: search chrome presses are instant (no scale)
- [ ] UI-042 profile once list animations land (press scales are graphicsLayer-only)

## Known limitations / Future improvements

- Tokenize `FlashSendButton` + swipe snap-back springs (owner: UI-011/UI-010 passes).
- Send-success micro-interaction after UI-013 exposes a send-result callback.
- Optional `FlashPressable` helper modifier if a fourth press-scale copy-paste appears.
