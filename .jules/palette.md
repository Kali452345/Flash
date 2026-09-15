## 2026-09-12 - Tactile Press Scale on CombinedClickable Chips

Learning: In Flash Compose UI, interactive chips (like `FlashReactionChip`) using `combinedClickable` need a single shared `MutableInteractionSource` passed to both `.flashPressScale(interactionSource, pressedScale = 0.94f)` and `.combinedClickable(interactionSource = interactionSource, indication = null, ...)` to ensure smooth tactile scale feedback without recomposition or ripple conflicts. Always replace stock `material3.Text` with `FlashText` to comply with Flash design system guidelines.
Action: Pass a shared `remember { MutableInteractionSource() }` to `.flashPressScale(interactionSource)` before `.combinedClickable` and use `FlashText`.
