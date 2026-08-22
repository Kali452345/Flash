# Reply System — UI-010

**Status:** IMPLEMENTED  
**Component ID:** UI-010  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

`FlashSwipeToReplyContainer` (swipe gesture handling & animated reveal badge), `FlashQuotedReplyCard` (in-bubble quoted reference snippet), `FlashReplyDock` (composer pinned staging bar), and `FlashMessageHighlight` (jump-to-message viewport scroll & pulse glow).

## Purpose

The reply system connects conversation threads by enabling users to reference specific previous messages directly. It provides:
1. **Swipe-to-reply gesture** on any message bubble to stage a reply with tactile feedback.
2. **In-bubble quoted reply card** showing who was quoted and what text/media was referenced, with 1-tap jump to the original message.
3. **Jump-to-original choreography** smoothly scrolling the message list to the referenced message and flashing a pulse glow so the user immediately recognizes the context.
4. **Composer reply dock** pinned immediately above the input field indicating active reply intent with a 1-tap dismiss action.

---

## Research sources

1. **Telegram Android** — Left-swipe gesture (inward drag), logarithmic rubber-band resistance, rotating reply icon ($ -35^\circ \to 0^\circ$), left solid 3dp vertical accent bar, 600ms background flash on jump-to-message.
2. **Signal Android** — Right-swipe gesture, clean card background, 3dp accent bar, jump to original message.
3. **WhatsApp Android/iOS** — Right-swipe gesture, combined media preview thumb, jump to original with background gray flash.
4. **iMessage (iOS 17/18)** — Swipe right inline reply, thread bubble linking, tactile haptic click.
5. **Slack Mobile** — Swipe right to start thread, quoted blockquote reference.
6. **Android 10–16 System Navigation Guidelines** — Edge-to-edge predictive back gesture disambiguation; left-swipe avoids conflict with left-edge back swipe on incoming bubbles.
7. **Jetpack Compose 2025/2026 Pointer & Motion Best Practices** — `detectHorizontalDragGestures` with touch slop consumption, zero-recomposition `graphicsLayer { translationX = ... }` drawing, and `LazyListState.animateScrollToItem()`.

---

## Existing approaches studied

### Approach A: Telegram Left-Swipe (Right-to-Left Drag)
- Swipe left drags the bubble inward from the right edge toward the center.
- Reply badge reveals on the right side behind the bubble with spring scale + rotation.
- **Strength:** Zero conflict with Android OS edge-to-edge back navigation gestures on the left screen edge. Natural right-thumb pull.
- **Weakness:** Differs from legacy iOS right-swipe conventions.

### Approach B: WhatsApp/Signal Right-Swipe (Left-to-Right Drag)
- Swipe right drags the bubble to the right.
- **Strength:** Familiar to users coming from WhatsApp.
- **Weakness:** On Android 10+, dragging an incoming message from the extreme left screen edge frequently triggers the system Back gesture instead of replying.

### Approach C: Long-Press Menu Only (No Swipe)
- Relies solely on focus overlay context menu "Reply" action.
- **Strength:** Zero gesture conflicts.
- **Weakness:** 3 steps instead of 1 (long-press $\to$ wait for menu $\to$ tap Reply). Lacks modern fluid messaging feel.

---

## What worked

| Pattern | Source | Why it works |
|---|---|---|
| Left-swipe (Right-to-Left drag) | Telegram | Eliminates Android system edge-back gesture collisions entirely |
| 52dp trigger threshold with logarithmic damping | Telegram, Signal | Crisp trigger threshold; user feels physical resistance beyond trigger point |
| Single-edge haptic trigger | Telegram, iMessage | Haptic fires exactly once when crossing threshold; avoids buzzing while holding |
| Rotating reply arrow badge reveal | Telegram | Directional rotational cue ($-35^\circ \to 0^\circ$) communicates pending reply action |
| 3dp vertical accent bar on in-bubble quote | Telegram, Signal, WhatsApp | Clean structural hierarchy without cluttering bubble typography |
| 1-tap jump to message + 600ms pulse glow | Telegram, WhatsApp | Instantly brings original context into view with clear visual spotlight |
| Zero-recomposition graphicsLayer | Compose best practice | Runs on RenderNode / GPU draw phase; maintains solid 120 FPS during flings |

## What did not work

| Pattern | Source | Why rejected |
|---|---|---|
| Left-to-right swipe for incoming bubbles | WhatsApp | Clashes with Android 10–16 system predictive back navigation |
| Rigid clamp at threshold | Generic implementations | Feels harsh and broken; lacks physical rubber-banding elasticity |
| Recomposing state on every drag pixel | Naive Compose | Drops frames from 120 FPS down to 45–60 FPS during fast drags |
| Full-screen thread modal | Slack | Breaks local 1:1 conversation continuity; overkill for standard chat replies |

---

## Chosen approach

**Flash Precision Left-Swipe + Sculpted Quoted Reference Card + Jump Pulse Highlight.**

1. **Left-Swipe Gesture (`FlashSwipeToReplyContainer`)**:
   - Direction: Drag left (`translationX < 0`).
   - Trigger Threshold: $T = 52\text{ dp}$.
   - Damping: Logarithmic resistance $T + 24\text{dp} \cdot \ln(1 + \Delta x/24\text{dp})$ up to an absolute cap of $80\text{ dp}$.
   - Visual: 32dp circular pill behind the bubble containing `FlashIcons.Reply`, scaling from 0.4x to 1.0x with counter-clockwise rotation from $-35^\circ \to 0^\circ$.
   - Haptic: Single `HapticFeedbackType.LongPress` tick when crossing $T$.
   - Release: Snappy spring animation (`dampingRatio = 0.65f, stiffness = 500f`) returning to 0; triggers `onReply(message)`.
2. **In-Bubble Quoted Reference (`FlashQuotedReplyCard`)**:
   - Embedded at top of message bubble.
   - 3dp rounded vertical accent bar (`colors.accentPrimary` or sender palette color).
   - Sender name in bold caption typography (`captionEmphasis`), quoted text in 2-line truncated caption (`captionDefault`).
   - Surface: Subtly tinted background (translucent white 14% on outgoing, `backgroundSurfaceSubtle` on incoming).
   - 1-tap invokes `onJumpToMessage(quotedMessageId)`.
3. **Jump-to-Original Choreography**:
   - Computes target index in `reverseLayout` list and invokes `listState.animateScrollToItem()`.
   - Highlights the target bubble with a 600ms pulse glow (`colors.accentPrimary.copy(alpha = 0.24f)` + subtle 1.02x scale pulse).
4. **Composer Staging Bar (`FlashReplyDock`)**:
   - Seamlessly expands above input pill via `FlashMotion.replyExpandEnter()`.
   - Displays quoted sender, text snippet, and a tactile 'X' cancel button.

---

## Visual specification

### Quoted Reply Card (`FlashQuotedReplyCard`)

| Property | Incoming Bubble | Outgoing Bubble |
|---|---|---|
| Position | Top of bubble content column | Top of bubble content column |
| Corner radius | `FlashShapes.radius8` (8.dp) | `FlashShapes.radius8` (8.dp) |
| Indicator bar | 3.dp width, `colors.accentPrimary`, rounded 1.5.dp | 3.dp width, `Color.White.copy(alpha = 0.85f)`, rounded 1.5.dp |
| Card background | `colors.backgroundSurfaceSubtle` | `Color.White.copy(alpha = 0.14f)` |
| Sender name color | `colors.accentPrimary` | `Color.White` |
| Text snippet color | `colors.textSecondary` | `Color.White.copy(alpha = 0.85f)` |
| Text typography | `typography.captionDefault`, max 2 lines | `typography.captionDefault`, max 2 lines |
| Padding | Horizontal 8.dp, vertical 4.dp | Horizontal 8.dp, vertical 4.dp |

### Swipe Reveal Badge

| Property | Value |
|---|---|
| Container size | 32.dp × 32.dp circle |
| Background | `colors.accentPrimary` |
| Icon | `FlashIcons.Reply` (18.dp, tint `colors.textOnAccent`) |
| Reveal placement | Right edge behind bubble, vertically centered |
| Scale progress | $0.4f + (0.6f \times \text{progress})$ with 1.12x spring overshoot on threshold |
| Rotation progress | $-35^\circ \to 0^\circ$ |

### Pulse Highlight Glow

| Property | Value |
|---|---|
| Background glow | `colors.accentPrimary.copy(alpha = 0.24f)` |
| Border highlight | `BorderStroke(1.5.dp, colors.accentPrimary.copy(alpha = 0.75f))` |
| Duration | 600 ms total (150 ms ease-in, 450 ms ease-out) |
| Scale bump | 1.02x peak scale at 150 ms |

---

## Interaction specification

| Action | Behavior |
|---|---|
| Swipe message bubble left $> 52\text{dp}$ and release | Haptic clicks at 52dp, bubble snaps back, `replyingToMessage` set on composer, keyboard opens if closed |
| Swipe message bubble left $< 52\text{dp}$ and release | Bubble snaps back without triggering reply; no haptic |
| Long-press bubble $\to$ tap "Reply" in context menu | Opens focus overlay, sets `replyingToMessage` on composer, dismisses overlay |
| Tap quoted reply card inside any message bubble | Viewport smooth-scrolls to original message and plays 600ms pulse glow highlight |
| Tap 'X' button on composer reply dock | Dismisses active reply staging bar |

---

## Animation specification

| Animation | API / Tokens | Spec |
|---|---|---|
| Bubble drag snap-back | `Animatable<Float>.animateTo(0f)` | `spring(dampingRatio = 0.65f, stiffness = 500f)` |
| Reply icon scale & rotation | `graphicsLayer { scaleX = ..., rotationZ = ... }` | Direct gesture progress mapping |
| Reply dock expand / collapse | `AnimatedVisibility` | `expandVertically(tween(200, easing = Decelerate))` / `shrinkVertically(tween(150, easing = Accelerate))` |
| Jump highlight pulse | `Animatable<Float>.animateTo(0f)` | 600ms total: 150ms rise + 450ms deceleration fade |

---

## Gesture specification

- **Touch Slop Disambiguation**: Horizontal drag only consumes touch events when horizontal delta exceeds `touchSlop`. Vertical flings pass through directly to `LazyColumn`.
- **Direction Lock**: Drag delta is clamped to $\le 0$ (leftward drag only). Rightward drags are ignored, preventing interference with parent containers.
- **RTL Support**: In RTL layouts, drag direction automatically mirrors to rightward drag.

---

## Accessibility requirements

- **TalkBack Announcements**:
  - Quoted card: `contentDescription = "Replying to ${quote.senderName}: ${quote.textSnippet}. Double-tap to view original message."`
  - Reply dock: `contentDescription = "Replying to ${quote.senderName}. Tap cancel button to clear."`
- **Reduced Motion**: Under `reduceMotion == true`, snap-back is instantaneous (`snap()`), and jump scroll uses `scrollToItem()` instead of `animateScrollToItem()`.

---

## Performance considerations

- Zero recomposition during drag: all translation, scaling, and rotation operations run inside `graphicsLayer` blocks without writing to Compose snapshot state during pointer movement.
- Quoted reply text measurements are cached in `TextLayoutResult`.
- Highlight pulse uses a single `drawBehind` or `graphicsLayer` overlay with no list item rebuilds.

---

## Implementation notes

### Data Models (`:core:messaging`)
- Add `FlashQuotedReplyUi(messageId, senderName, textSnippet, isMine)` in `FlashMessagingModels.kt`.
- Update `FlashMessageUi` with `val replyTo: FlashQuotedReplyUi? = null`.

### New / Updated Files (`:ui:chat`)
- `FlashQuotedReplyCard.kt` — in-bubble quote reference card composable.
- `FlashSwipeToReply.kt` — swipe-to-reply gesture modifier and background icon reveal container.
- `FlashMessageBubble.kt` — embed quote card, wrap in swipe container, add highlight overlay.
- `FlashConversationScreen.kt` & `FlashMessageList.kt` — jump-to-message scrolling and highlight choreography.

---

## Testing checklist

- [ ] Compose preview — quoted reply on incoming and outgoing bubbles
- [ ] Compose preview — composer reply dock with long text truncation
- [ ] Physical device — swipe-to-reply gesture with single haptic trigger
- [ ] Physical device — jump-to-message smooth scroll and pulse highlight
- [ ] Dark mode — quote card contrast on incoming (dark void) and outgoing (teal) bubbles
- [ ] TalkBack — quote card announcements and jump actions
- [ ] Unit tests — quoted reply data mapping, jump index computation, reply draft lifecycle

---

## What makes this Flash?

Flash's reply system adopts a deliberate left-swipe gesture that solves the fundamental ergonomic flaw of right-swipe chat apps on modern Android devices: zero collisions with system predictive back edge gestures. The quoted reference card is sculpted directly into Flash's concave bubble silhouette with crisp 3dp rounded accent bars, while jump-to-original animations use a signature Flash Pulse teal glow that anchors conversation flow without disorienting the user. Zero dependencies, pure GPU-accelerated gesture physics, and complete keyboard safety make Flash replies feel tactile, fast, and calm.
