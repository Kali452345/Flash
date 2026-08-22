# Typing Indicator — UI-014

**Status:** IMPLEMENTED  
**Component ID:** UI-014  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

`FlashTypingIndicator` (3 animated bouncing wave dots), `FlashTypingBubble` (incoming message bubble wrapper for list presentation), and `FlashHeaderTypingStatus` (subtle inline subtitle animation for `FlashChatHeader`).

## Purpose

The typing indicator provides real-time ambient awareness that a peer is composing a message, recording audio, or preparing a file transfer. It delivers:
1. **Fluid 3-dot wave bouncing motion** with staggered phase offsets.
2. **Dedicated incoming message bubble container** with Flash concave bubble styling and spring slide-in entrance.
3. **Header subtitle integration** updating the conversation header from "Online" to "typing..." with animated wave dots.
4. **Zero-recomposition GPU execution** via `rememberInfiniteTransition` and `graphicsLayer`.
5. **Battery-safe lifecycle and accessibility support** (disabling continuous animation when off-screen or when `reduceMotion` is active).

---

## Research sources

1. **iMessage (iOS 17/18)** — Iconic 3-dot bubble with progressive grey-to-dark vertical bouncing dots in a sculpted pill container.
2. **Telegram Android / iOS** — Dual presentation: subtle animated pencil / typing text in header + dancing dots bubble in message stream.
3. **Signal Android / iOS** — Minimalist 3-dot wave animation with spring scale and opacity pulse inside an incoming bubble.
4. **WhatsApp Android / iOS** — Header typing subtitle ("typing...") with green wave dots + optional list bubble.
5. **Discord Mobile** — Ambient bar above composer showing avatar + "X is typing...".
6. **Jetpack Compose Animation APIs** — `rememberInfiniteTransition`, `keyframes`, `RepeatMode.Restart`, and `graphicsLayer` translation/scale.

---

## Existing approaches studied

### Approach A: List Bubble Container (iMessage / Signal / Telegram)
- Displays an incoming message bubble at the bottom of the message list with 3 dancing dots.
- **Strength:** Visually directly points to where the next message will appear; strong conversational continuity.
- **Weakness:** Consumes list vertical space; requires clean entrance and exit spring choreography when new message arrives.

### Approach B: Header Subtitle Only (WhatsApp / Slack)
- Swaps header subtitle from "Online" to "typing..." or "recording audio...".
- **Strength:** Zero list layout shifts.
- **Weakness:** User's eyes are focused at the bottom of the screen (near the composer), so header changes can be missed.

### Approach C: Dedicated Floating Dock above Composer (Discord / Slack Mobile)
- A small text bar above composer: "Alex is typing...".
- **Strength:** Always visible near keyboard.
- **Weakness:** Clutters composer area, especially when reply docks or attachment bars are open.

---

## What worked

| Pattern | Source | Why it works |
|---|---|---|
| Dual Presentation (Bubble in List + Header Subtitle) | Telegram | Maximum clarity; user catches the signal whether looking at the bottom list or the top header |
| 3-dot staggered wave (Offset vertical jump + scale) | iMessage, Signal | Feels organic, lively, and immediately recognizable |
| Staggered phase offset (120ms delay between dots) | Motion Design | Produces a clean continuous sine wave rhythm |
| Spring slide-up entrance on bubble appearance | Flash Motion System | Seamlessly introduces the bubble without abrupt popping |
| GPU `graphicsLayer` rendering | Compose Best Practices | Runs at 120 FPS without triggering composable recomposition cycles |

## What did not work

| Pattern | Source | Why rejected |
|---|---|---|
| Static pulsing text ("typing...") without dots | Legacy SMS apps | Lacks tactile premium feel; looks static |
| Rotating spinner / circular progress | Generic Material | Looks like network loading, not human typing |
| Continuous animation when scrolled off-screen | Bad practice | Wastes battery and CPU cycles |

---

## Chosen approach

**Flash Dual Ambient Typing System:**
1. **`FlashTypingIndicator`**: Core 3-dot wave composable.
   - 3 circular dots ($6\text{dp}$ diameter, $4\text{dp}$ spacing).
   - Phase-offset vertical wave translation ($-4\text{dp} \to 0\text{dp}$) + scale ($0.85 \to 1.15$) + alpha ($0.5 \to 1.0$).
   - Total cycle period: $900\text{ms}$ with $120\text{ms}$ delay per dot.
2. **`FlashTypingBubble`**: Message list bubble.
   - Incoming bubble styling (`colors.bubbleIncoming`, `FlashShapes.bubbleIncomingGrouped`).
   - Sized to $48\text{dp} \times 32\text{dp}$ containing `FlashTypingIndicator`.
   - Animated entrance: `slideInVertically` with spring physics + `fadeIn`.
3. **`FlashHeaderTypingStatus`**: Header subtitle integration.
   - Replaces static presence with "typing" + 3 mini-dots ($3.5\text{dp}$) in `colors.accentPrimary`.

---

## Visual specification

### 3-Dot Typing Wave (`FlashTypingIndicator`)

| Property | Value |
|---|---|
| Dot diameter | $6\text{dp}$ |
| Dot spacing | $4\text{dp}$ |
| Dot count | 3 |
| Inactive / Base dot color | `colors.textSecondary` (or `colors.accentPrimary` for header) |
| Container padding | Horizontal $12\text{dp}$, Vertical $8\text{dp}$ |

### Message List Typing Bubble (`FlashTypingBubble`)

| Property | Value |
|---|---|
| Surface background | `colors.bubbleIncoming` |
| Border | `FlashDimensions.borderHairline`, `colors.bubbleIncomingBorder` |
| Shape | `FlashShapes.bubbleIncomingGrouped` |
| Alignment | Start (Left / incoming alignment) |
| Minimum dimensions | $52\text{dp} \times 32\text{dp}$ |

### Header Typing Subtitle (`FlashHeaderTypingStatus`)

| Property | Value |
|---|---|
| Text label | "typing" |
| Typography | `typography.captionEmphasis` (11.sp, Medium) |
| Text color | `colors.accentPrimary` (Teal Pulse) |
| Mini-dot diameter | $3.5\text{dp}$ |
| Mini-dot spacing | $2.5\text{dp}$ |

---

## Animation specification

| Animation | API / Tokens | Spec |
|---|---|---|
| Dot wave translation | `rememberInfiniteTransition` + `animateFloat` | $0\text{dp} \to -4\text{dp} \to 0\text{dp}$ over $900\text{ms}$ (`FastOutSlowInEasing`) |
| Dot wave scale | `animateFloat` | $0.85f \to 1.15f \to 0.85f$ |
| Dot wave alpha | `animateFloat` | $0.45f \to 1.0f \to 0.45f$ |
| Phase offsets | Dot 0: $0\text{ms}$, Dot 1: $120\text{ms}$, Dot 2: $240\text{ms}$ |
| Bubble entrance | `AnimatedVisibility` | `slideInVertically(springDefault) + fadeIn(tweenFast)` |
| Bubble exit | `AnimatedVisibility` | `slideOutVertically(springSnappy) + fadeOut(tweenFast)` |
| Reduced motion | `motion.reduceMotion` | Replaces wave bounce with static 3 dots with fixed $0.75$ alpha |

---

## Accessibility requirements

- **TalkBack Announcements**:
  - `FlashTypingBubble`: `contentDescription = "$peerName is typing..."` with `liveRegion = LiveRegionMode.Polite`.
  - `FlashHeaderTypingStatus`: `contentDescription = "Status: $peerName is typing..."`.
- **Reduced Motion**: Under `reduceMotion == true`, infinite bouncing loop is replaced with static dots.

---

## Implementation notes

### New Files in `:ui:chat` (`com.transfer.flash.ui.chat`)
- `FlashTypingIndicator.kt` — 3-dot wave animation and `FlashTypingBubble` composables.

### Modified Files in `:ui:chat`
- `FlashChatHeader.kt` — integrate `FlashHeaderTypingStatus` when peer state is typing.
- `FlashMessageList.kt` — add optional typing bubble at bottom of message stream.
- `FlashConversationScreen.kt` — support peer typing state simulation.

---

## Testing checklist

- [ ] Compose preview — `FlashTypingIndicator` in default and accent modes
- [ ] Compose preview — `FlashTypingBubble` in light and dark themes
- [ ] Physical device — 3-dot wave runs at smooth 120 FPS
- [ ] Physical device — typing bubble slides in/out smoothly without list jank
- [ ] TalkBack — announces "$peerName is typing" via live region
- [ ] Reduced motion — respects accessibility setting with static dots
- [ ] Unit tests — typing state models and phase calculation

---

## What makes this Flash?

Flash's typing indicator reflects the app's electric, tactile identity. Rather than generic fading dots, Flash uses a coordinated vertical jump-and-scale pulse with Teal accent highlights in the header and concave incoming bubble shaping in the list, delivering immediate ambient presence during local P2P conversations.
