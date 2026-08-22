# Flash Design System — Chat (UI-1)

> **Superseded for premium UI work:** use [`docs/ui/design-system.md`](ui/design-system.md) and [`docs/ui/flash-premium-chat-ui-implementation.md`](ui/flash-premium-chat-ui-implementation.md) after UI-001 research. This file remains as historical notes from the exploratory scaffold only.

Flash-owned design tokens for the conversation screen. Values were derived from visual inspection of the Stream Chat Android compose sample (reference only — no Stream source was copied into this repository).

## Measurement method

1. Run `stream-chat-android-compose-sample` on a device or emulator.
2. Compare side-by-side with Flash `FlashConversationScreen`.
3. Adjust tokens in `app/src/main/java/com/transfer/flash/ui/design/` until layout, spacing, and color match.

## Color primitives (light theme)

| Token | Hex | Usage |
|---|---|---|
| Brand 50 | `#F3F7FF` | Subtle brand tint |
| Brand 100 | `#E3EDFF` | Outgoing message bubble background |
| Brand 150 | `#C3D9FF` | Outgoing attachment inset |
| Brand 500 | `#005FFF` | Accent, links, send enabled, read receipts |
| Brand 900 | `#091A3B` | Outgoing message text |
| Chrome 0 | `#FFFFFF` | App/header/composer surface |
| Chrome 50 | `#F6F8FA` | Message list background |
| Chrome 100 | `#EBEEF1` | Incoming bubble, composer input fill |
| Chrome 150 | `#D5DBE1` | Borders, dividers |
| Chrome 500 | `#687385` | Tertiary text, timestamps |
| Chrome 700 | `#414552` | Secondary text, metadata |
| Chrome 900 | `#1A1B25` | Primary text |

## Spacing and radii

| Token | Value |
|---|---|
| spacing3xs | 2dp |
| spacing2xs | 4dp |
| spacingXs | 8dp |
| spacingSm | 12dp |
| spacingMd | 16dp |
| spacingLg | 20dp |
| spacingXl | 24dp |
| radiusMd | 8dp |
| radiusLg | 12dp |
| radiusXl | 16dp |
| radius2xl | 20dp |
| bubble tail | bottom corner 0dp on sender side for last message in group |

## Typography

| Style | Size | Weight | Line height |
|---|---|---|---|
| bodyDefault | 16sp | Regular | 20sp |
| headingMedium | 18sp | SemiBold | 24sp |
| metadataDefault | 12sp | Regular | 16sp |
| metadataEmphasis | 12sp | SemiBold | 16sp |
| captionDefault | 14sp | Regular | 20sp |

## Icons

Flash-owned vector drawables use the `flash_ic_*` prefix at **20dp** intrinsic size with **1.5dp** stroke. Tint at runtime via `FlashChatTheme.colors.textPrimary` or `accentPrimary`.

Required for UI-1: `arrow_left`, `attach`, `send`, `more`, `reply`, `thread`, `flag`, `pin`, `thumb_up`, `heart`, `bolt`, `sliders`, `thumb_down`.

## Component specs (conversation screen)

### Channel header
- Center-aligned title (headingMedium) + subtitle (metadataDefault, textSecondary)
- Back icon 20dp, trailing avatar 36dp
- Bottom border 1dp `borderCoreSubtle`
- Background `backgroundCoreElevation0`

### Message bubble
- Max width ~78% of screen (~300dp on 390dp width)
- Incoming: white/chrome100 fill, subtle border
- Outgoing: brand100 fill, no heavy shadow
- Grouped bubbles: 20dp radius; tail on last message in group only

### Composer
- Surface with top border only
- Input: filled rounded container (radius2xl), chrome100 background
- Attach + send icons 20dp; send tint accentPrimary when draft non-empty

### Message actions sheet
- Reaction row: horizontal space-around, 20dp icons
- Action rows: 24dp horizontal padding, 12dp vertical, 20dp icon + bodyDefault label

## Verification checklist

- [ ] Light theme conversation matches Stream sample at 390×844
- [ ] Outgoing bubble color and text contrast acceptable
- [ ] Composer does not use Material OutlinedTextField default chrome
- [ ] No `MaterialTheme.colorScheme` used for chat-visible colors
- [ ] Dark theme deferred until light theme accepted
