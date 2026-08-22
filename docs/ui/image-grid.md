# Flash Image Message & Adaptive Grid Layout

**Status:** IMPLEMENTED  
**Component ID:** UI-017  
**Last updated:** 2026-08-21  
**Owner phase:** Premium Chat UI — conversation core  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Depends on:** UI-001 (`design-system.md`), UI-005 (`message-bubble.md`), UI-037 (`motion-system.md`)  
**Code:** `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashImageGrid.kt`, `core/messaging/model/FlashMessagingModels.kt`

---

## Component

`FlashImageGrid`, `FlashImageTile`, `FlashImageGridModel` — adaptive mosaic and collage presentation for single and multi-photo messages in chat bubbles.

---

## Purpose

Photos and media albums represent one of the highest-frequency message payloads in modern messaging. A naive vertical stack of full-size images clutters chat history and destroys scroll performance. UI-017 provides:

- **Adaptive Collage Layouts:** Seamless mosaic layouts for 1, 2, 3, 4, and 5+ images with `+N` overflow indicators.
- **Aspect Ratio Clamping:** Smart single-image sizing that preserves orientation while constraining extreme aspect ratios (0.5 to 2.0).
- **Bubble Contour Masking:** Outer corners smoothly match the parent bubble contour while inner gutter separators use micro-radii (2dp–4dp).
- **Caption & Timestamp Integration:** Elegant overlay pills for borderless image messages, or integrated below-image caption flow with delivery ticks.
- **Tactile Micro-interactions:** 0.98x spring scale touch response per image tile with tap-to-open viewer triggers.

---

## Research Sources

| Source | What Was Studied |
|---|---|
| Telegram Android (v10+) | Dynamic mosaic algorithm, 1–10 grouped photos, `+N` overflow chip, inner 2dp gutter, rounded corner outer clip |
| WhatsApp Android | 1–4 photo collages, side-by-side 2-column splitting, bottom-right floating frosted timestamp pill |
| Signal Android | Aspect ratio bounds (1:2 to 2:1), rounded bubble clipping, tap micro-press animation |
| iMessage / iOS Photos | Overlapping cards vs grid view, smooth spring expand triggers |
| Android Jetpack Compose Canvas / Layout | Custom `Layout` composable vs nested rows/columns for zero-overhead mosaic measurement |

---

## Existing Approaches Studied

### Approach A — Simple Vertical Column of Images
Each image renders as a full-width item in a vertical column.
- **Pros:** Trivial to implement.
- **Cons:** A batch of 5 photos takes up 5 screen heights, requiring endless scrolling; looks unpolished and amateurish.
- **Verdict:** Rejected.

### Approach B — Fixed 2-Column LazyVerticalGrid inside LazyColumn
Using a nested LazyGrid with fixed 2-column cells.
- **Pros:** Standard grid layout.
- **Cons:** Nested scrollable containers in Compose cause measurement glitches and performance overhead; doesn't support asymmetric 3-image mosaics (1 wide + 2 small).
- **Verdict:** Rejected.

### Approach C — Tailored Adaptive Mosaic Layout (`FlashImageGrid`, Chosen)
A dedicated, deterministic Compose `Layout` measuring fixed mosaic patterns based on image count $N$:
- $N = 1$: Single adaptive tile respecting aspect ratio with min ($0.5$) and max ($2.0$) clamping.
- $N = 2$: 2 columns (if wide/square) or 2 stacked rows (if tall).
- $N = 3$: 1 large leading tile (top or left) + 2 half-size companion tiles.
- $N = 4$: Balanced $2 \times 2$ matrix or 1 large header + 3 bottom columns.
- $N \ge 5$: $2 \times 2$ grid with the 4th tile displaying a semi-transparent frosted backdrop and `+N` count chip.
- **Pros:** Deterministic, lightweight, zero nested scroll overhead, optimal visual balance.
- **Verdict:** Selected.

---

## Visual Specification

| Property | Specification |
|---|---|
| Max Grid Width | $280\text{dp}$ (constrained to bubble max width) |
| Min Single Image Height | $140\text{dp}$ |
| Max Single Image Height | $320\text{dp}$ |
| Gutter Spacing | $2.5\text{dp}$ |
| Outer Corner Radius | Matches parent bubble ($16\text{dp}$–$20\text{dp}$) |
| Inner Gutter Radius | $4\text{dp}$ |
| Timestamp Pill (No caption) | Semi-transparent dark scrim (`#66000000`), $12\text{dp}$ rounded pill, white text & icons |
| Overflow `+N` Overlay | $60\%$ Black scrim backdrop, `typography.titleMedium` ($+N$ bold text) |

---

## Interaction Specification

1. **Tap Tile:** Triggers `onImageClick(index, image)` to open the full-screen media viewer (UI-018).
2. **Micro-Press Physics:** Scaling down to $0.98\times$ on touch down via `FlashMotion.springBouncySpec()`.
3. **Long Press:** Propagates to parent message bubble for selection mode (UI-007) and context menu (UI-008).

---

## Implementation Notes

- **Data Models:** `FlashImageAttachmentUi` in `:core:messaging` with `id`, `uri`, `thumbUri`, `width`, `height`, `mimeType`, and optional `caption`.
- **Composable:** `FlashImageGrid` in `:ui:chat` (`com.transfer.flash.ui.chat`).
- **Integration:** Integrated seamlessly into `FlashMessageBubble` body.
