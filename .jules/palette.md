# Palette's Journal - Flash UX & Accessibility Learnings

## 2026-09-11 - Interactive Reaction Chip Tactile Feedback & Design Token Compliance
Learning: Reaction chips (`FlashReactionChip.kt`) were using bare `material3.Text` instead of `FlashText` and lacked tactile press scale feedback. When adding `Modifier.flashPressScale(interactionSource)` to clickable surfaces that use custom `interactionSource` (like `combinedClickable`), passing the exact same `interactionSource` instance ensures the spring press scaling is perfectly synchronized with press events.
Action: Always pass a shared `remember { MutableInteractionSource() }` to both `flashPressScale` and `combinedClickable`/`clickable`, and replace any stock Material `Text` with `FlashText`.
