# Custom Message Composer & Send Button (UI-011 / UI-013)

**Status:** IMPLEMENTED  
**Component ID:** UI-011 / UI-013  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI — Component Sequence  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)  
**Code Target:** `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashComposer.kt`, `FlashSendButton.kt`, `FlashReplyPreview.kt`

---

## Component

- `FlashComposer` — Adaptive multi-state input container, input pill, contextual attachments, expanding text box, IME handling.
- `FlashSendButton` — Tactile, stateful action button (Disabled, Active/Ready, Pressed, Sending, Sent/Burst).
- `FlashReplyPreviewBar` — Contextual reply/edit preview anchor docked above the text box.

---

## Purpose

The message composer is the central interaction surface in Flash conversation. It transforms user input into messages, attachments, or commands. It must feel **snappy, calm, and tactile**, never jumping unexpectedly, respecting software keyboard insets, dynamically expanding from single-line to multi-line without jank, and visually communicating delivery readiness.

---

## Research Sources

### Messaging Apps Studied (Clean-Room Analysis)

| Source | Patterns Studied | What Worked | What Did Not Work / Rejected |
|---|---|---|---|
| **Telegram Android** | Smooth multi-line expansion, voice/send button morphing, reply banner dock, subtle border glow | Clean input pill, snappy spring toggle between send and secondary actions | Overly bouncy send particle animations; floaty decoupled composer pill that wastes screen space |
| **Signal Android** | Minimalist dock, high-contrast borders, secure send lock affordances | Crystal-clear typography, tight vertical density, solid hit targets | Rigid non-morphing buttons; stark elevation changes |
| **WhatsApp Android** | Pill layout, voice lock slide gesture, detached circular FAB send | Familiar ergonomics, smooth keyboard transition | Cluttered icon clustering inside the input box; generic Material 2 shadow |
| **iMessage (iOS)** | Pill input with contextual expandable left drawer, subtle send bubble | Calm, clean aesthetics, vertical expand capped at 5 lines with inner scroll | Hidden attachment drawer requires extra tap to discover common actions |

### Compose & Android Platform APIs
- `WindowInsets.ime` and `Modifier.imePadding()`: Dynamic keyboard height synchronization.
- `BasicTextField` with custom `decorationBox`: Total control over padding, placeholder, and cursor without Material `TextField` overhead or forced indicator lines.
- `AnimatedContent` & `animateContentSize`: Smooth transitions for reply preview bar and multi-line expansions.
- `FlashTheme` tokens (`FlashColors`, `FlashTypography`, `FlashShapes`, `FlashSpacing`, `FlashMotion`).

---

## Existing Approaches Studied

### Approach A: Material 3 `OutlinedTextField` or `TextField`
- **Pros:** Standard Material component, built-in placeholder.
- **Cons:** Rigid minimum height (56dp), heavy internal padding, built-in indicator lines and label logic unsuited for chat messengers, excessive recompositions.
- **Verdict:** REJECTED.

### Approach B: Fixed-Height Bottom Bar
- **Pros:** Simple layout, predictable height.
- **Cons:** Horrible multiline UX; typing more than one line scrolls horizontally or clips text.
- **Verdict:** REJECTED.

### Approach C: Adaptive Flash Pill Composer with Contextual Dock (Chosen)
- **Pros:** Fluid auto-expanding height (1 to 6 lines, 40dp min to ~140dp max), docked reply banner, tactile send button with `FlashMotion.springBouncy` micro-press feedback, dedicated attachment affordance, full `imePadding` keyboard safety.
- **Verdict:** SELECTED.

---

## What Worked

1. **`BasicTextField` inside a clipped `FlashShapes.composerInput` pill:** Eliminates all Material overhead, zero unintended padding, perfect vertical centering.
2. **Contextual Action Slot:** Morphing/crossfading between Voice/Secondary and Send button based on text presence (`draft.isNotBlank()`).
3. **Reply Dock Banner:** Positioned immediately above the input pill within the composer surface, sliding in smoothly with `FlashMotion.enterFadeSlide` and dismissible with one tap.
4. **Haptic & Scale Press Feedback:** 0.92 scale feedback on send press with quick release spring.

---

## What Did Not Work

1. **Unbounded height expansion:** Letting the text box grow beyond 6 lines pushed the entire conversation off screen. Capping at `maxLines = 6` with internal scrolling is necessary.
2. **Placing attachment button inside the text input pill:** Reduced typing width on small screens and caused cursor collision. Attachment button belongs in the outer composer row.

---

## Chosen Approach

Build `FlashComposer` as a cohesive, layered composable:
1. **Outer Container:** Background `colors.composerSurface`, subtle top divider `colors.borderSubtle`, `Modifier.imePadding()`.
2. **Reply/Edit Header Slot:** Animated visibility banner above the input row showing sender name, quote snippet, and dismiss button.
3. **Main Input Row:**
   - **Attachment Button (Left):** Custom circular button with `FlashIcons.Attach`, triggering attachment picker.
   - **Input Pill (Center):** Rounded surface (`colors.composerInputBackground`, `FlashShapes.composerInput`), housing `BasicTextField` (max 6 lines), `colors.textPrimary` text, `colors.textTertiary` placeholder ("Message...").
   - **Action Button (Right):** `FlashSendButton` — when empty, presents secondary action (or disabled state); when text present, transitions to filled Pulse accent circle (`colors.pulse`) with white `FlashIcons.Send` icon, supporting press-scale animation.

---

## Visual Specification

| Element | Token / Spec | Value |
|---|---|---|
| **Outer Surface** | `FlashTheme.colors.composerSurface` | Light: `#FFFFFF`, Dark: `#161B22` |
| **Top Hairline** | `FlashTheme.colors.borderSubtle` | 1 dp border |
| **Input Pill Background** | `FlashTheme.colors.composerInputBackground` | Light: `#F0F3F6`, Dark: `#21262D` |
| **Input Pill Shape** | `FlashShapes.composerInput` | `RoundedCornerShape(20.dp)` |
| **Input Pill Min Height** | `FlashDimensions.touchTargetMin` | `40.dp` (compact pill) |
| **Input Text Style** | `FlashTheme.typography.bodyDefault` | 15sp / 20sp line height |
| **Placeholder Style** | `FlashTheme.typography.bodyDefault` | Color: `colors.textTertiary` |
| **Send Button Shape** | `CircleShape` | 40 dp diameter |
| **Send Button Active Bg** | `FlashTheme.colors.pulse` | `#00A896` (Pulse Teal) |
| **Send Button Inactive Bg**| `Color.Transparent` | Subdued tint |
| **Send Button Icon Tint** | `colors.surfaceVoid` (active) / `colors.iconDisabled` (inactive) | Contrast white / muted |
| **Reply Banner Bg** | `colors.surface1` with accent left stripe | 3 dp left bar (`colors.pulse`) |

---

## Interaction & State Specification

```text
Composer States:
├── EMPTY: Placeholder visible, Send button disabled/subdued, Attachments ready.
├── TYPING: Text present, Send button switches to active Pulse accent, typing events debounced.
├── MULTILINE: Input grows smoothly up to 6 lines, internal scroll enabled past 6 lines.
├── REPLYING: Reply header docked above input with quote preview and close 'X'.
├── DISABLED / READ-ONLY: Network disconnected or spectator mode, input disabled.
```

- **Tap Input:** Requests focus, software keyboard animates in, whole composer moves flush above keyboard.
- **Tap Send:** Triggers `onSend()`, fires tactile scale-down-then-up spring, clears draft, keeps keyboard open.
- **Tap Attach:** Triggers `onAttachmentClick()`.
- **Dismiss Reply:** Tapping 'X' on reply bar clears active reply context with slide-out animation.

---

## Animation Specification

- **Send Button State Transition:** `AnimatedContent` with `fadeIn(tween(150)) + scaleIn(tween(150, easing = LinearOutSlowInEasing))` and `fadeOut(tween(100)) + scaleOut(tween(100))`.
- **Send Button Press Physics:** `animateFloatAsState(targetValue = if (pressed) 0.92f else 1.0f, animationSpec = FlashMotion.springBouncy)`.
- **Reply Banner Enter/Exit:** `expandVertically(tween(200)) + fadeIn(tween(200))` / `shrinkVertically(tween(150)) + fadeOut(tween(150))`.
- **Reduced Motion:** If `FlashTheme.motion.isReducedMotion` is true, all animations use `0ms` instant swap.

---

## Accessibility Requirements

- **Content Descriptions:**
  - Attachment button: `"Attach media or file"`
  - Send button (active): `"Send message"`
  - Send button (disabled): `"Send message, disabled (no text)"`
  - Dismiss reply button: `"Cancel reply"`
- **Touch Target:** Minimum 48dp touch bounding box for all interactive icons (using `Modifier.size(40.dp)` with default minimum touch target padding or 48dp frame).
- **Focus Order:** Reply Cancel $\rightarrow$ Attach $\rightarrow$ Input field $\rightarrow$ Send button.
- **Large Font Scaling:** Spacing and pill height dynamically adapt without clipping text.

---

## Responsive & Dark-Mode Behavior

- **Responsive:** Fills width on phone; on tablet/desktop, maintains max comfortable input width or spans cleanly across chat column.
- **Dark Mode:** Deep charcoal surface (`#161B22`), input pill (`#21262D`), vivid pulse accent (`#00A896`), crisp white typography.

---

## Performance Considerations

- Uses `BasicTextField` to avoid Material sub-composition costs.
- Passes lambda callbacks `onDraftChanged`, `onSend`, `onAttachmentClick` to avoid unnecessary parent recomposition.
- Hoists state cleanly: `FlashComposer` is stateless, driven by `draft: String`, `replyMessage: FlashMessageUi?`, `enabled: Boolean`.

---

## Testing Checklist

- [ ] Compose Previews for Empty, Typing, Multiline, Replying, and Disabled states.
- [ ] Physical device keyboard appearance and dismissal (`imePadding`).
- [ ] Smooth growth from 1 line to 6 lines without layout flicker.
- [ ] Dark mode contrast verification.
- [ ] TalkBack accessibility traversal.
- [ ] Reduced motion setting adherence.

---

## What Makes This Flash?

FlashComposer embodies **calm speed**. Rather than overwhelming the user with cluttered action ribbons or rigid Material boxes, it presents an ultra-clean, sculpted pill with subtle border definitions and our signature Pulse teal send button. When replying or attaching, contextual layers slide into place with purpose and zero wasted motion.
