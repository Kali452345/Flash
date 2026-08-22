# Message Press & Selection Mode (UI-007)

**Status:** IMPLEMENTED  
**Component ID:** UI-007  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI — Component Sequence  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)  
**Code Target:** `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashSelectionToolbar.kt`, `FlashMessageBubble.kt`, `FlashConversationScreen.kt`

---

## Component

- `FlashSelectionToolbar` — Contextual action bar replacing the standard chat header when one or more messages are selected. Displays selection count, Copy, Reply, Forward, Delete, and Close affordances.
- Message Selection Layer — Integrated long-press gesture recognizer, tactile press feedback, selection overlay highlight (`colors.accentPrimary` tinted tint with 0.12 alpha), and selection checkbox/indicator.

---

## Purpose

Enable users to interact with individual or multiple messages within a conversation: copy text, select for forwarding, batch delete, or trigger replies. Selection must feel **immediate, fluid, and explicit**, with unambiguous tactile response and smooth elevation/action bar transformations.

---

## Research Sources

### Messaging Apps Studied (Clean-Room Analysis)

| Source | Interaction Pattern | What Worked | What Did Not Work / Rejected |
|---|---|---|---|
| **Telegram Android** | Long press triggers multi-selection mode; top bar smoothly transforms into contextual count bar with action icons (Reply, Forward, Copy, Delete). Bubbles receive subtle tinted background highlight. | Effortless multi-selection: once in selection mode, subsequent single taps toggle selection on any message without triggering links/media. Crisp count header. | Heavy full-row green overlay that obscures custom bubble shapes. |
| **Signal Android** | Long press selects message and opens top action bar. High contrast count. Haptic feedback on entry. | Clear selection state; immediate haptic buzz. | Action overflow hidden behind standard 3-dots when 4+ items selected. |
| **WhatsApp Android** | Long press selects bubble, turns background teal-tinted. Top bar presents delete, star, info, copy, forward. | Familiar Android CAB (Contextual Action Bar) behavior. | Abrupt top bar swap without crossfade animation. |
| **iMessage (iOS)** | Long-press opens floating reaction overlay & menu (UI-008), with separate "More..." button entering multi-select with left-side radio check circles. | Elegant contextual separation between single-message menu vs multi-message selection. | Requires two distinct steps to enter multi-select mode. |

### Compose & Android Platform APIs
- `combinedClickable` (`ExperimentalFoundationApi` or custom pointer input) on `FlashBubbleSurface` for distinct tap vs long-press.
- `AnimatedContent` for seamless morphing between `FlashChatHeader` and `FlashSelectionToolbar`.
- `BackHandler`: Hardware/gesture back press deselects all messages and exits selection mode before navigating back to the chat list.
- `FlashTheme` & `FlashMotion` tokens.

---

## Existing Approaches Studied

### Approach A: Modal Bottom Sheet for Actions on Every Long Press
- **Pros:** Easy to display actions.
- **Cons:** Blocks the chat screen, impossible to select multiple messages for bulk copy or delete.
- **Verdict:** REJECTED for multi-selection (reserved for quick actions in UI-008).

### Approach B: Standard Android Contextual Action Bar (CAB / ActionMode)
- **Pros:** Standard platform pattern.
- **Cons:** Replaces entire window decor, destroys Flash Pulse design language, lacks smooth animation.
- **Verdict:** REJECTED.

### Approach C: Flash Contextual Selection Toolbar with Inline Bubble Tint (Chosen)
- **Pros:** Seamless animated replacement of `FlashChatHeader` via `AnimatedContent`; message bubbles render a delicate Pulse accent overlay (`colors.accentPrimary` @ 12% opacity) + selection check indicator; single taps toggle selection while in mode; back gesture cleanly exits mode.
- **Verdict:** SELECTED.

---

## What Worked

1. **`selectedMessageIds: Set<String>` state hoisting:** The selection state is a pure immutable set passed down from `FlashConversationScreen` to `FlashMessageList` and `FlashMessageBubble`.
2. **Single-tap toggle during selection mode:** When `selectedMessageIds.isNotEmpty()`, tapping a bubble toggles its selection instead of opening media or focusing keyboard.
3. **Dedicated `FlashSelectionToolbar`:** Features close button ('X'), selected count (`"1 selected"` / `"$N selected"`), and direct contextual action icons:
   - **Reply** (enabled when exactly 1 message selected)
   - **Copy** (copies joined text of selected messages)
   - **Forward** (unblocks forwarding flow)
   - **Delete** (triggers batch deletion)
4. **Haptic Feedback:** `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` on selection mode entry.

---

## What Did Not Work

1. **Full screen row overlay:** Darkening the entire horizontal band made it hard to see which bubbles were mine vs incoming. Highlighting the bubble surface itself preserves shape and visual hierarchy.
2. **Disabling scroll during selection:** Users need to scroll to select earlier/later messages. Scroll must remain 100% interactive.

---

## Chosen Approach

1. **State Model:**
   ```kotlin
   data class FlashSelectionState(
       val selectedIds: Set<String> = emptySet(),
       val inSelectionMode: Boolean = selectedIds.isNotEmpty(),
   )
   ```
2. **Header Swap:**
   `FlashConversationScreen` swaps `FlashChatHeader` $\leftrightarrow$ `FlashSelectionToolbar` using `AnimatedContent(transitionSpec = { motion.statusCrossfade() })`.
3. **Bubble Highlight:**
   When `message.id in selectedIds`, `FlashBubbleSurface` draws an overlay border `BorderStroke(1.5.dp, colors.accentPrimary)` and a translucent highlight overlay `colors.accentPrimary.copy(alpha = 0.12f)`.
4. **Interaction Flow:**
   - Long press on unselected bubble $\rightarrow$ Haptic buzz $\rightarrow$ Adds ID to `selectedIds` $\rightarrow$ Selection toolbar appears.
   - Tap on bubble while `inSelectionMode` $\rightarrow$ Toggles ID in `selectedIds`. If set becomes empty $\rightarrow$ Exits selection mode.
   - Back press while `inSelectionMode` $\rightarrow$ Clears `selectedIds` $\rightarrow$ Exits selection mode.
   - Tap 'X' on `FlashSelectionToolbar` $\rightarrow$ Clears `selectedIds`.

---

## Visual Specification

| Element | Token / Spec | Value |
|---|---|---|
| **Selection Toolbar Bg** | `FlashTheme.colors.backgroundSurface` | Surface background with `borderSubtle` bottom hairline |
| **Selection Count Text**| `FlashTheme.typography.headingMedium` | e.g. `"2 selected"`, `colors.textPrimary` |
| **Close Icon** | `FlashIcons.Close` | `colors.textPrimary` |
| **Action Icons** | `FlashIcons.Reply`, `FlashIcons.Copy`, `FlashIcons.Forward`, `FlashIcons.Delete` | `colors.textPrimary` (disabled: `colors.iconDisabled` / `colors.textTertiary`) |
| **Bubble Highlight Tint** | `colors.accentPrimary.copy(alpha = 0.12f)` | Pulse Teal subtle wash |
| **Bubble Selected Border**| `BorderStroke(1.5.dp, colors.accentPrimary)` | Crisp 1.5dp selection contour |
| **Selection Checkmark** | `FlashIcons.Check` (or Pulse check pill) | Visible indicator when selected |

---

## Interaction Specification

- **Entry:** Long press on any bubble $\rightarrow$ `HapticFeedbackType.LongPress` $\rightarrow$ enter mode.
- **Toggle:** Single tap on any bubble when `inSelectionMode == true` $\rightarrow$ adds/removes item.
- **Copy Action:** Concatenates selected messages in chronological order, copies to Android clipboard, shows confirmation toast/feedback, exits selection mode.
- **Reply Action:** Available when `selectedIds.size == 1`. Sets composer `replyingTo` state and exits selection mode.
- **Delete Action:** Emits `onDeleteSelected(selectedIds)` callback and clears selection.
- **Exit:** Hardware back button or toolbar Close button immediately clears all selections.

---

## Animation Specification

- **Toolbar Swap:** `AnimatedContent` using `motion.statusCrossfade()` (200ms decelerate fade).
- **Bubble Selection Highlight:** `animateColorAsState(targetValue = if (selected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent, animationSpec = tween(motion.fastMillis))`.
- **Reduced Motion:** If `motion.reduceMotion == true`, instantaneous swap (0ms).

---

## Accessibility Requirements

- **TalkBack Announcements:**
  - On mode entry: `"Selection mode activated, 1 message selected"`
  - On toggle: `"$N messages selected"`
  - Toolbar close: `"Exit selection mode"`
  - Action buttons labeled clearly (`"Copy selected messages"`, `"Delete selected messages"`).
- **Keyboard / D-Pad Navigation:** Focus traversal remains linear: Close $\rightarrow$ Action icons $\rightarrow$ Message list.

---

## Responsive & Dark Mode

- **Responsive:** Toolbar scales across all phone widths and tablet splits, keeping action icons spaced and readable.
- **Dark Mode:** Deep charcoal toolbar (`#161B22`), crisp high-contrast Pulse teal selection border and checkmark.

---

## Performance Considerations

- `selectedIds` is a `Set<String>` providing $O(1)$ membership checks during LazyColumn item emission.
- Recompositions are isolated to only the bubbles whose selection state changes.

---

## Testing Checklist

- [ ] Long-press enters selection mode with haptic feedback.
- [ ] Single-tap toggles selection in multi-select mode.
- [ ] Deselecting the last item automatically exits selection mode.
- [ ] Back press exits selection mode before navigating away.
- [ ] Copy action puts selected message texts onto the system clipboard.
- [ ] Reply action populates `FlashComposer` reply dock.
- [ ] Compose previews for `FlashSelectionToolbar` in 1-item, multi-item, light, and dark modes.

---

## What Makes This Flash?

Flash message selection is **subtle, fast, and tactile**. Rather than masking the screen with jarring modal sheets or heavy opaque green bars, Flash highlights the message's natural contours with a crisp Pulse accent border and gentle translucent wash, transforming the top bar with calm precision.
