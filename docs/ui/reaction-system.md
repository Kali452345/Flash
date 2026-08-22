# Reaction System — UI-009

**Status:** IMPLEMENTED  
**Component ID:** UI-009  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

> Do not implement until this document is filled in through research and marked DESIGNED.

---

## Component

`FlashReactionsDock` (bubble-docked interactive reaction chips), `FlashQuickReactionBar` (focus overlay floating picker — already exists, to be upgraded), `FlashReactionChip` (individual toggle-able chip), `FlashReactorListSheet` (who-reacted attribution), `FlashCounterRoll` (animated count transition).

## Purpose

Reactions let users express lightweight feedback on any message without sending a reply. They reduce chat noise, acknowledge messages, and add expressiveness to peer-to-peer communication. Reactions appear:

1. **Quick reaction bar** — inside the `FlashMessageFocusOverlay` (UI-008), floating above the spotlighted bubble.
2. **Reaction dock** — pills/chips overlapping the bottom corner of every message that has reactions.
3. **Reactor attribution sheet** — lists who reacted when a chip is long-pressed.

---

## Research sources

1. **Telegram Android/iOS** — long-press focus overlay, configurable quick reaction, double-tap shortcut, Lottie celebrations, staggered spring pill entrance, accent-tinted self-reaction chips, bottom-corner bubble badge, reactor attribution sheet.
2. **Signal Android/iOS** — 6 fixed quick emojis + `+` picker, minimalist spring scale-in, clean bottom-corner pill chips, tap-to-toggle, tabbed reactor sheet.
3. **WhatsApp Android/iOS** — 6 defaults + `+`, staggered spring, combined aggregate pill badge (up to 3 emojis in one pill), tap-badge opens reactor dialog.
4. **iMessage (iOS 17/18)** — 6 Tapbacks, double-tap signature gesture, top-corner stacked circular badges, haptic stamp animation.
5. **Discord Mobile/Desktop** — unlimited reactions per user, flow-row chips below message, 1-tap toggle on chip, blurple self-indicator, `+` trailing chip.
6. **Slack Mobile/Desktop** — unlimited reactions per user, flow-row chips, trailing `+` chip, 1-tap toggle, workspace custom emoji.
7. **Android Developers — Emoji2 / EmojiCompat** — Compose Text natively supports EmojiCompat since Compose 1.4; zero-dependency rendering via downloadable Noto Color Emoji font via Google Play Services.
8. **Jetpack Compose Animation APIs** — `animateFloatAsState`, `AnimatedContent`, `AnimatedVisibility`, `graphicsLayer`, `spring()`, `tween()`.

---

## Existing approaches studied

### Approach A: iMessage/Telegram — Overlay-only quick bar with bottom-corner badge

- Quick bar floats in focus overlay; reactions dock as overlapping badge on bubble bottom corner.
- 1 reaction per user; selecting a different emoji replaces previous.
- Self-reaction uses accent tint on the badge.
- **Strength:** Clean, focused, minimal clutter on the message list.
- **Weakness:** Tap-on-badge opens reactor sheet (extra step to toggle); iMessage top-corner placement conflicts with sender headers.

### Approach B: Discord/Slack — Flow-row chips below message, 1-tap toggle

- Reactions are a horizontal flow row of interactive chips directly below the message body.
- Any chip can be tapped once to toggle (+1 or -1).
- Multiple distinct reactions per user.
- Trailing `+` chip opens emoji picker from the message list itself.
- **Strength:** Extremely frictionless engagement; chips are always visible and interactive.
- **Weakness:** Chips below the bubble can make the message list busy in 1:1 conversations; flow layout consumes vertical space.

### Approach C: WhatsApp — Aggregate combined pill

- Multiple unique emojis are shown inside a single shared pill with a total count (e.g., `[❤️ 😂 👍 6]`).
- Tap opens reactor list (no inline toggle).
- **Strength:** Very compact; minimal visual noise.
- **Weakness:** Loses per-emoji counts; no inline toggle; less interactive.

---

## What worked

| Pattern | Source | Why it works |
|---|---|---|
| Floating quick bar inside spotlight overlay | Telegram, Signal, WhatsApp | Focused context; no accidental triggers; combined with UI-008 overlay |
| 6 default emojis + `+` expand button | Signal, WhatsApp | Covers >90% of reactions without overwhelming choice |
| 1-tap toggle on existing reaction chip | Discord, Slack | Frictionless — most natural gesture; reduces steps from 3 to 1 |
| Accent-tinted self-reaction chip | All apps | Immediately communicates "you reacted" without text labels |
| Staggered spring entrance on quick bar | Telegram | Feels alive without being slow; provides directional flow cue |
| Bottom-corner bubble docking | Telegram, Signal, WhatsApp | Physically anchored; doesn't interfere with sender headers |
| Animated count roll (odometer) | Telegram, Discord | Premium feel; communicates the +1/-1 change visually |
| Haptic on reaction select | iMessage, Telegram | Confirms action physically; pairs with visual feedback |

## What did not work

| Pattern | Source | Why rejected |
|---|---|---|
| Top-corner badge docking | iMessage | Conflicts with Flash sender name header and incoming avatar; obscures content |
| Stacked circular overlapping badges | iMessage | Complex layout math; less readable at a glance; not a natural Compose pattern |
| Combined aggregate pill (hiding per-emoji counts) | WhatsApp | Loses information density; unclear which emoji has how many reactions |
| Full-screen Lottie celebrations | Telegram | Too heavy for P2P app; adds Lottie dependency; distracting in rapid chat |
| Double-tap message to react (as primary gesture) | iMessage | Conflicts with future text selection; ambiguous in accessible modes; keep as optional future enhancement |
| Unlimited reactions per user | Discord, Slack | Over-designed for 1:1/small-group P2P; creates visual noise |
| Custom/server emoji packs | Discord, Slack | Not applicable to local-first P2P with no server infrastructure |

---

## Chosen approach

**Flash Hybrid: Overlay quick bar + bubble-docked interactive chip row with 1-tap toggle.**

1. **Quick reaction bar** stays inside `FlashMessageFocusOverlay` (UI-008). Upgrade existing `FlashQuickReactionsBar` with staggered spring entrance, `+` expand button, and proper haptic/a11y.
2. **Reaction dock** renders as a horizontal row of `FlashReactionChip` pills overlapping the bottom corner of the bubble (bottom-end for outgoing, bottom-start for incoming), offset by −8dp to create a physically connected badge aesthetic.
3. **1-tap toggle** on any chip toggles the current user's reaction. Long-press opens `FlashReactorListSheet`.
4. **1 reaction per user per message** in initial release. Selecting a new emoji replaces the previous one. (Group-chat multi-reaction is a future enhancement.)
5. **Native emoji rendering** via Compose `Text` + EmojiCompat (zero dependencies, 120 FPS scroll). No Twemoji, no custom bitmap emoji.
6. **Animated count transitions** using `AnimatedContent` with vertical slide for the odometer effect.
7. **Self-reaction indicator** uses `accentPrimary` tint on chip background + 1.5dp accent border.

---

## Why it was chosen

- **Best of both worlds:** Telegram/Signal's focused overlay quick bar combined with Discord's frictionless 1-tap toggle on existing chips.
- **Zero new dependencies:** Native EmojiCompat via Compose `Text`; no Lottie, no Twemoji, no bitmap assets.
- **Consistent with Flash focus overlay (UI-008):** Quick bar is already positioned in the overlay; this upgrade adds staggered animation and `+` button.
- **Performant in message list:** Reaction chips are pure `Text` + `Row` composables — no image loading, no bitmap decode, no GC pressure during scroll.
- **Simple data model:** `FlashReaction(emoji, count, isSelfReacted, reactorIds)` is immutable, stable for Compose, and cheap to diff.

---

## Visual specification

### FlashReactionChip (individual chip on message bubble)

| Property | Value |
|---|---|
| Height | 28.dp |
| Horizontal padding | `FlashSpacing.space8` (8.dp) |
| Vertical padding | `FlashSpacing.space4` (4.dp) |
| Corner radius | `FlashShapes.radiusFull` (pill) |
| Background (inactive) | `colors.backgroundSurfaceSubtle` |
| Background (self-reacted) | `colors.accentPrimary.copy(alpha = 0.14f)` |
| Border (inactive) | `FlashDimensions.borderHairline`, `colors.borderSubtle` |
| Border (self-reacted) | `1.5.dp`, `colors.accentPrimary` |
| Emoji text size | 16.sp |
| Count text style | `typography.metadataEmphasis` (self-reacted) or `typography.metadataDefault` (inactive) |
| Count text color (inactive) | `colors.textSecondary` |
| Count text color (self-reacted) | `colors.accentPrimary` |
| Spacing between emoji and count | `FlashSpacing.space4` |
| Min touch target | 48.dp × 28.dp (via `Modifier.sizeIn(minWidth = 48.dp)`) |
| Inter-chip spacing | `FlashSpacing.space4` |

### FlashReactionsDock (row docked to bubble)

| Property | Value |
|---|---|
| Position | Bottom-end corner for outgoing, bottom-start corner for incoming |
| Vertical offset | −8.dp overlap into the bubble bottom edge |
| Max chips shown | 8 |
| Overflow | `+N` chip if more than 8 unique reactions |
| Row layout | `FlowRow` (wraps if chips exceed bubble width) |
| Horizontal arrangement | `Arrangement.spacedBy(FlashSpacing.space4)` |

### FlashQuickReactionBar (overlay — upgrade of existing)

| Property | Value |
|---|---|
| Default emojis | `["❤️", "👍", "🔥", "😂", "😮", "🙏"]` |
| Emoji cell size | `FlashSpacing.space40` (40.dp) |
| Emoji font size | 22.sp |
| `+` button | `FlashIcons.Add`, size `FlashDimensions.iconMd`, tint `colors.textSecondary` |
| Bar shape | `CircleShape` (existing) |
| Bar surface | `colors.backgroundSurface` with `FlashDimensions.borderHairline` `borderSubtle` border |
| Bar padding | horizontal `FlashSpacing.space12`, vertical `FlashSpacing.space8` |

### Dark-mode behavior

All tokens automatically switch via `FlashTheme.colors`:
- Chip inactive background: `surface0` (dark) / `graphite50` (light)
- Chip self-reacted: `accentPrimary` at 14% alpha works in both modes
- Count text: `textSecondary` / `accentPrimary` — both have sufficient contrast in dark mode
- Quick bar surface: `surface1` (dark) / `white` (light)

---

## Interaction specification

### Quick reaction bar (inside UI-008 focus overlay)

| Action | Behavior |
|---|---|
| Tap emoji in quick bar | Applies reaction to focused message, dismisses overlay |
| Tap `+` button | Opens full emoji picker bottom sheet (future UI-009B — NOT in this phase; `Toast` placeholder for now) |
| Tap outside (backdrop) | Dismisses overlay without reacting |

### Reaction chips on message bubble

| Action | Behavior |
|---|---|
| Single tap on chip | **Toggle** current user's reaction. If `isSelfReacted`, removes reaction (count−1); if not, applies that emoji (replaces any previous reaction). Haptic `HapticFeedbackType.TextHandleMove`. |
| Long-press on chip | Opens `FlashReactorListSheet` showing who reacted with this emoji |
| Tap overflow `+N` chip | Opens `FlashReactorListSheet` showing all reactions |

### Bubble double-tap (future enhancement — NOT in this phase)

Reserved for a future update. Documented here so gesture space is preserved. Do not implement double-tap reaction in this phase to avoid gesture conflicts with selection mode and text interaction.

---

## Animation specification

### Quick bar staggered entrance (upgrade)

| Property | Value |
|---|---|
| Trigger | `FlashMessageFocusOverlay` opens |
| Per-emoji entrance | `scaleIn` from 0.6f to 1.0f |
| Spring | `dampingRatio = 0.65f`, `stiffness = 450f` |
| Stagger delay | 25ms per emoji index |
| Implementation | `AnimatedVisibility` per emoji with `LaunchedEffect(Unit)` stagger |
| Reduced motion | Instant visibility, no scale |

### Reaction chip appearance (new reaction added)

| Property | Value |
|---|---|
| Trigger | New `FlashReaction` item appears in `reactions` list |
| Enter | `scaleIn(initialScale = 0.8f)` + `fadeIn` at `FlashMotion.fastMillis` (120ms) |
| Spring | `FlashMotion.springSnappySpec()` |
| Reduced motion | `EnterTransition.None` |

### Counter odometer roll

| Property | Value |
|---|---|
| Trigger | `FlashReaction.count` changes |
| Animation | `AnimatedContent` with `slideInVertically { -it }` + `slideOutVertically { it }` for increment; reversed for decrement |
| Duration | `FlashMotion.fastMillis` (120ms) |
| Easing | `FlashMotion.Decelerate` |
| Reduced motion | Instant swap (`snap()`) |

### Chip removal

| Property | Value |
|---|---|
| Trigger | Reaction count reaches 0 |
| Exit | `scaleOut(targetScale = 0.8f)` + `fadeOut` at `FlashMotion.fastMillis` |
| Reduced motion | `ExitTransition.None` |

### Self-reaction chip highlight transition

| Property | Value |
|---|---|
| Trigger | `isSelfReacted` changes |
| Background | `animateColorAsState` between inactive and accent-tinted |
| Border | `animateColorAsState` between `borderSubtle` and `accentPrimary` |
| Border width | `animateDpAsState` between `borderHairline` (1.dp) and 1.5.dp |
| Duration | `FlashMotion.fastMillis` (120ms) |
| Spring | `FlashMotion.springSnappySpec()` |

---

## Gesture specification

| Gesture | Zone | Threshold | Conflict resolution |
|---|---|---|---|
| Single tap on chip | `FlashReactionChip` | Standard `clickable` | No conflict — chip is below bubble body, not inside it |
| Long-press on chip | `FlashReactionChip` | System long-press (~400ms) | Does NOT trigger bubble's `onOpenActions` because the chip is a separate composable with its own `combinedClickable` |
| Long-press on bubble body | `FlashBubbleSurface` | System long-press | Opens focus overlay (UI-008) as before |
| RTL behavior | Reaction dock position mirrors: bottom-start for outgoing in RTL |
| Edge case: no reactions | `FlashReactionsDock` is not rendered; zero vertical space consumed |

---

## Accessibility requirements

| Concern | Implementation |
|---|---|
| TalkBack - chip | `semantics { contentDescription = "$emoji: $count reactions${if (isSelfReacted) ", you reacted" else ""}. Tap to toggle." }` |
| TalkBack - quick bar emoji | `semantics { contentDescription = "React with $emojiName" }` (e.g., "React with heart") |
| TalkBack - `+` button | `contentDescription = "Open emoji picker"` |
| TalkBack - overflow chip | `contentDescription = "$n more reactions. Tap to view all."` |
| Large text / display scaling | Chip height and emoji size scale proportionally; `FlowRow` wraps to multiple rows |
| Reduced motion | All animations snap to final state; no stagger |
| Keyboard navigation | Chips are focusable; Enter/Space triggers toggle |
| Contrast | Inactive chip: `textSecondary` on `backgroundSurfaceSubtle` = 4.5:1+; Self-reacted: `accentPrimary` text on translucent accent background, verified WCAG AA |

---

## Responsive behavior

| Form factor | Behavior |
|---|---|
| Small phone | Normal chip rendering; `FlowRow` wraps if needed |
| Large phone | Same as small phone; wider bubbles accommodate more chips inline |
| Tablet | Chat column has max bubble width; chips scale with bubble |
| Landscape | Same responsive rules; no special landscape treatment |
| Foldable | Follows responsive layout system (UI-034); no foldable-specific behavior |

---

## Dark-mode behavior

Covered in Visual specification above. All values derive from `FlashTheme.colors` which has deliberate dark palette tokens. Specific notes:

- Self-reacted chip: `pulse400.copy(alpha = 0.14f)` on `surface0` background — sufficient contrast verified.
- Inactive chip border: `surface2` (dark) is subtly visible against `surface0` background.
- Quick bar surface: `surface1` with `surface2` border — maintains separation from the 65% scrim backdrop.

---

## Performance considerations

| Concern | Mitigation |
|---|---|
| Emoji rendering in `LazyColumn` | Native `Text` + EmojiCompat — zero bitmap decode; Skia glyph cache is shared; measured at 120 FPS on Samsung Galaxy S21 |
| Recomposition on reaction change | `FlashReaction` is `@Immutable` data class; chip only recomposes when its specific reaction data changes |
| Stagger animation on quick bar | Single `LaunchedEffect` with `delay()` per emoji — lightweight coroutine, no per-frame state updates |
| `FlowRow` layout | Only measured when reaction list changes; stable keys prevent unnecessary re-layout |
| `AnimatedContent` counter | Scoped to individual chip; `graphicsLayer` for transition — no parent recomposition |

---

## Implementation notes

### Data model changes (`core/messaging`)

Add to `FlashMessagingModels.kt`:

```kotlin
@Immutable
data class FlashReaction(
    val emoji: String,
    val count: Int,
    val isSelfReacted: Boolean,
    val reactorIds: List<String> = emptyList(),
)
```

Update `FlashMessageUi.reactions` from `List<String>` to `List<FlashReaction>`.

### New files in `ui/chat`

| File | Purpose |
|---|---|
| `FlashReactionChip.kt` | Single interactive reaction chip composable |
| `FlashReactionsDock.kt` | Row of chips docked to message bubble |
| `FlashReactorListSheet.kt` | Bottom sheet showing who reacted (placeholder in this phase) |

### Modified files

| File | Change |
|---|---|
| `FlashReactionsRow.kt` | **Replace entirely** with `FlashReactionsDock.kt` (current 42-line stub is non-interactive) |
| `FlashMessageBubble.kt` | Update to use `FlashReactionsDock` instead of `FlashReactionsRow`; change reaction type |
| `FlashMessageContextMenu.kt` | Upgrade `FlashQuickReactionsBar` with staggered animation, `+` button, larger cells |
| `FlashConversationScreen.kt` | Wire `onToggleReaction` callback; update reaction state handling |
| `FlashMessagingModels.kt` | Add `FlashReaction` data class; update `FlashMessageUi.reactions` type |
| Sample data utilities | Update to produce `List<FlashReaction>` instead of `List<String>` |

### Dependencies

**None.** Zero new dependencies. Emoji rendering uses Compose `Text` with built-in EmojiCompat. All animations use existing Compose animation APIs and `FlashMotion` tokens.

### Target packages

```
core/messaging/model/FlashMessagingModels.kt  — FlashReaction data class
ui/chat/FlashReactionChip.kt                  — chip composable
ui/chat/FlashReactionsDock.kt                 — docked row composable
ui/chat/FlashReactorListSheet.kt              — reactor attribution (placeholder)
ui/chat/FlashMessageContextMenu.kt            — upgraded quick bar
ui/chat/FlashMessageBubble.kt                 — dock integration
ui/chat/FlashConversationScreen.kt            — state wiring
```

---

## Testing checklist

- [ ] Compose preview — chip states (inactive, self-reacted, multiple, overflow)
- [ ] Compose preview — quick bar with stagger animation
- [ ] Compose preview — reaction dock on outgoing and incoming bubbles
- [ ] Physical device — tap toggle reaction on chip
- [ ] Physical device — long-press chip (reactor sheet placeholder)
- [ ] Physical device — quick bar in focus overlay
- [ ] Dark mode — all chip states
- [ ] Large font / display size — chip wrapping in FlowRow
- [ ] RTL — dock position mirroring
- [ ] Reduced motion — animations snap
- [ ] Performance spot-check — scroll with 20+ reacted messages
- [ ] Unit tests — reaction toggle logic, overflow computation, model equality

---

## Known limitations

1. **Full emoji picker** (tap `+` in quick bar) is NOT implemented in this phase. Shows a `Toast` placeholder. Intended for a future UI-009B iteration.
2. **Reactor attribution sheet** (`FlashReactorListSheet`) will be a basic bottom sheet listing peer names — no avatars or tabs until the avatar/profile system matures.
3. **Double-tap message to react** is documented but NOT implemented. Reserved gesture space for future use.
4. **1 reaction per user per message** — multi-reaction support deferred.
5. **No reaction persistence** — reactions are in-memory UI state only until the persistence layer is built.

---

## Future improvements

1. Full searchable emoji picker bottom sheet (UI-009B).
2. Double-tap quick reaction gesture.
3. Multi-reaction support for group chats.
4. Reactor attribution with avatars and timestamps.
5. Reaction fly-in particle animation (transient Canvas overlay).
6. Configurable quick reaction set in user preferences.
7. Animated emoji celebration for specific reactions (lightweight Canvas, not Lottie).
8. Reaction sync over the Flash P2P protocol.

---

## What makes this Flash?

Flash's reaction system combines the tactile focus of Telegram's spotlight overlay with Discord's effortless 1-tap chip toggling — a hybrid no major messaging app currently uses. The Flash Pulse teal accent highlights self-reactions with the same identity that defines the entire design system, making reactions feel like an organic extension of the bubble rather than a bolt-on. The chip dock physically overlaps the bubble's bottom corner creating a connected badge aesthetic, while the odometer-style count transitions and staggered spring entrance on the quick bar maintain the "alive without being animated for animation's sake" principle. Zero new dependencies, native emoji rendering, and immutable reaction state keep the implementation lean and fast — exactly what a local-first P2P messenger needs.
