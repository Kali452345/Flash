# Flash Visual Identity & Design System

**Status:** IMPLEMENTED  
**Component ID:** UI-001 / UI-035 / UI-036  
**Last updated:** 2026-08-22  
**Owner phase:** Premium Chat UI — foundation  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Code:** `app/src/main/java/com/transfer/flash/ui/theme/`

---

## Component

`FlashTheme`, `FlashColors`, `FlashTypography`, `FlashSpacing`, `FlashShapes`, `FlashDimensions`, `FlashElevation`

---

## Purpose

Define Flash's own visual language before any chat components are implemented or accepted. This system provides semantic tokens for color, typography, spacing, shape, elevation, and surface strategy so chat UI never depends on `MaterialTheme.colorScheme` or scattered magic numbers.

Also establishes foundations for **UI-035** (dark theme) and **UI-036** (dynamic color policy).

---

## Research sources

### Messaging apps (reference only — patterns, not visuals)

| Source | What was studied |
|---|---|
| Telegram Android | Calm list backgrounds, grouped bubble radii, metadata de-emphasis, fast scroll feel |
| Signal Android | High text contrast, minimal chrome, security/status affordances without clutter |
| WhatsApp Android | Familiar bubble grouping/tail pattern, composer dock behavior |
| Apple HIG / iMessage patterns | Timestamp alignment, subtle surface hierarchy, restrained shadows |

No proprietary UI code or assets were copied.

### Official Android / Material

| Source | URL / note |
|---|---|
| Material Design 3 in Compose | https://developer.android.com/develop/ui/compose/designsystems/material3 |
| Material 3 Expressive overview | https://m3.material.io/ — motion physics, shape morphing, expressive color |
| Dynamic color guidance | Android 12+ `dynamicLightColorScheme` / `dynamicDarkColorScheme` — accent extraction only |
| Compose Material 3 releases | https://developer.android.com/jetpack/androidx/releases/compose-material3 |

Material 3 is **infrastructure only** (semantics, adaptive APIs, platform ripples). Flash chat visible styling uses `FlashTheme` tokens.

### Compose OSS (license-checked)

| Project | License | Relevance |
|---|---|---|
| [Jetpack Compose Samples](https://github.com/android/compose-samples) | Apache 2.0 | Theme CompositionLocal patterns, preview structure |
| [Now in Android](https://github.com/android/nowinandroid) | Apache 2.0 | Semantic color naming, dark theme layering |
| [Element Android](https://github.com/element-hq/element-android) | Apache 2.0 | Messaging density, metadata typography (reference) |

### Typography / spacing references

- 8dp grid baseline (Material layout guidance)
- WCAG 2.1 contrast targets for body text (4.5:1 minimum)
- Tabular numerals for timestamps, transfer speeds, file sizes

---

## Existing approaches studied

### Approach A — Material You / dynamic color as primary identity

Use `dynamicLightColorScheme` / `dynamicDarkColorScheme` for all chat surfaces and accents.

**Pros:** System personalization, low maintenance.  
**Cons:** Destroys Flash brand; wallpaper-dependent bubbles look unprofessional; competes with Telegram/WhatsApp generic Material feel.  
**Verdict:** Rejected for chat. Allowed only as optional accent tint (UI-036).

### Approach B — Stream-look clean-room palette (prior scaffold)

Blue `#005FFF` accent, slate chrome, Stream-measured spacing from exploratory scaffold.

**Pros:** Already implemented; visually polished reference.  
**Cons:** Too close to Stream.io product identity; fails ADR-004 originality bar; wrong accent for P2P/local-first story.  
**Verdict:** Superseded by Flash Pulse (this document).

### Approach C — Flash Pulse (chosen)

Teal **pulse** accent (local connectivity, speed) + **graphite** neutrals + **spark** amber for transfer/status only. Layered dark surfaces (void → surface3), not inverted light theme.

**Pros:** Distinct from Telegram blue, WhatsApp green, Signal blue; supports P2P/status semantics; works light + dark with deliberate dark palette.  
**Cons:** Requires custom token maintenance.  
**Verdict:** Selected.

---

## What worked

- **Semantic tokens** over raw hex in composables (Now in Android pattern)
- **CompositionLocal** theme provider separate from Material LAN theme
- **Grouped bubble tail** radius strategy (familiar pattern, own colors)
- **Minimal elevation** — borders and surface steps instead of card shadows
- **8dp spacing scale** with 2/4dp micro steps for tight chat density
- **Tabular numerals** for timestamps and future transfer metrics

---

## What did not work

- Full Material dynamic color for chat surfaces — brand loss
- Stream-derived `#005FFF` accent — wrong identity + ADR-003/004 conflict
- Simple color inversion for dark mode — flat, poor bubble separation
- Heavy shadows on bubbles — generic Material card look

---

## Chosen approach

**Flash Pulse design system** implemented in `ui/theme/` with:

- Fixed Flash-owned light and dark semantic palettes
- Optional `dynamicAccent` flag tinting **accent/link only** on Android 12+
- Centralized spacing, shapes, dimensions, elevation
- `FlashMaterialTheme` retained for LAN MVP only

---

## Why it was chosen

Matches owner requirement for original premium identity, ADR-004 research-first workflow, and P2P product story (local pulse, transfer spark). Keeps Material as invisible infrastructure per AGENTS.md §34.

See **ADR-005** in `docs/decisions.md`.

---

## Visual specification

### Color primitives (reference — use semantic tokens in UI)

| Token | Hex | Role |
|---|---|---|
| pulse500 | `#0D9488` | Primary accent (light) |
| pulse400 | `#1FB8A6` | Primary accent (dark) |
| pulse100 | `#C8F0EA` | Outgoing bubble (light) |
| pulse900 | `#042F2B` | Outgoing text (light) |
| pulse800 | `#0F3D38` | Outgoing bubble (dark) |
| spark500 | `#E8950A` | Transfer / speed indicator |
| graphite900 | `#171A22` | Primary text (light) |
| graphite50 | `#F5F6F8` | Primary text (dark) |
| graphite100 | `#EBEDF2` | Chat list background (light) |
| void | `#07080A` | App background (dark, AMOLED-friendly) |
| surface0–3 | `#101218`–`#2C3244` | Layered dark surfaces |

### FlashColors semantic tokens (light)

| Token | Hex | Usage |
|---|---|---|
| accentPrimary | `#0D9488` | Send enabled, links, focus |
| accentSecondary | `#E8950A` | Transfer progress, speed |
| backgroundApp | `#F5F6F8` | Root scaffold |
| backgroundChat | `#EBEDF2` | Message list |
| backgroundSurface | `#FFFFFF` | Header, composer, sheets |
| chatBgIncoming | `#FFFFFF` | Incoming bubble |
| chatBgOutgoing | `#C8F0EA` | Outgoing bubble |
| chatTextIncoming | `#171A22` | Incoming body |
| chatTextOutgoing | `#042F2B` | Outgoing body |
| borderSubtle | `#EBEDF2` | Dividers |

### FlashColors semantic tokens (dark)

| Token | Hex | Usage |
|---|---|---|
| accentPrimary | `#1FB8A6` | Brighter accent on dark |
| backgroundApp | `#07080A` | AMOLED-friendly root |
| backgroundChat | `#101218` | Message list |
| backgroundSurface | `#181C26` | Header, composer |
| chatBgIncoming | `#222836` | Incoming bubble |
| chatBgOutgoing | `#0F3D38` | Outgoing bubble |
| chatTextOutgoing | `#C8F0EA` | Outgoing body on dark |
| textOnAccent | `#042F2B` (pulse900) | Text/icons on accent fills — UI-035 audit fix (was white, only ~2.5:1 on pulse400; pulse900 is ~5.9:1) |
| avatarPlaceholderText | graphite300 `#A8B0C0` | Initials on surface3 — UI-035 audit fix (graphite500 was ~2.8:1) |

Dark theme uses **layered surfaces** (void → surface3), not inverted light values.  
Deliberately shared across themes: `textTertiary` / `chatTextTimestamp` (graphite500 is a mid-tone passing ≈4.4:1 on both graphite50 and void).

### FlashTypography

| Style | Size | Weight | Line height | Usage |
|---|---|---|---|---|
| display | 24sp | SemiBold | 32sp | Rare full-screen titles |
| headingLarge | 20sp | SemiBold | 28sp | Chat list title |
| headingMedium | 18sp | SemiBold | 24sp | Conversation header |
| headingSmall | 16sp | SemiBold | 20sp | Section labels |
| bodyDefault | 16sp | Regular | 22sp | Message body |
| bodyEmphasis | 16sp | SemiBold | 22sp | Emphasis inline |
| captionDefault | 14sp | Regular | 20sp | Secondary labels |
| captionEmphasis | 14sp | SemiBold | 20sp | Chips, badges |
| metadataDefault | 12sp | Regular | 16sp | Timestamps, subtitles |
| metadataEmphasis | 12sp | SemiBold | 16sp | Sender names |
| numericDefault | 12sp | Regular (tabular) | 16sp | Clocks, speeds |
| numericEmphasis | 12sp | SemiBold (tabular) | 16sp | Transfer metrics |

Font family: system default (Roboto / manufacturer). Custom font deferred to post UI-001 acceptance.

### FlashSpacing

`2 / 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40` dp — 8dp grid with 2/4 micro steps.

### FlashShapes

| Token | Radius | Usage |
|---|---|---|
| bubbleGrouped | 20dp | Middle/top grouped messages |
| bubbleIncomingTail / bubbleOutgoingTail | 20dp + 0dp tail | Last in group |
| composerInput | 20dp | Composer text container |
| sheet | 24dp top corners | Bottom sheets |
| chip | 8dp | Reactions, tags |
| attachment | 12dp | Image grid |

### FlashElevation

| Token | Value | Usage |
|---|---|---|
| none | 0dp | Bubbles, list, composer |
| sheet | 2dp | Bottom sheets |
| overlay | 4dp | Reaction bar, FAB |
| modal | 8dp | Full-screen modal |

Prefer borders + surface steps over shadows.

### FlashDimensions

| Token | Value |
|---|---|
| minTouchTarget | 48dp |
| iconMd | 20dp |
| avatarXs / Md / Lg | 28 / 36 / 48dp |
| bubbleMaxWidthFraction | 0.78 |
| bubbleMaxWidth | 320dp |

### Surface strategy

| Layer | Light | Dark |
|---|---|---|
| App root | graphite50 | void |
| Chat list | graphite100 | surface0 |
| Chrome (header/composer) | white | surface1 |
| Incoming bubble | white + border | surface2 |
| Outgoing bubble | pulse100 | pulse800 |
| Sheet | white | surface1 |
| Scrim | `#66000000` | `#99000000` |

### Icon philosophy (UI-002 prep)

- **Stroke weight:** 1.5dp at 20dp optical size
- **Style:** Rounded caps/joins; geometric, not filled Material glyphs
- **States:** Primary, secondary, tertiary, accent, disabled — via `FlashColors` tints
- **System exceptions:** Share sheet, permission dialogs may use platform icons

### Animation philosophy (UI-037 prep)

- **Calm, fast, tactile** — motion supports communication, never decorates it
- **Target durations:** 120ms micro, 200ms component, 280ms screen (to be tokenized in UI-037)
- **Springs** for message insert and composer height; **tween** for fades
- **Reduced motion:** instant state change + opacity-only where required
- Full tokens: `FlashMotion` in UI-037 (`motion-system.md`)

### Dynamic color policy (UI-036) — IMPLEMENTED

Priority: **Flash identity > accessibility > system personalization**

| Setting | Behavior |
|---|---|
| Default (`dynamicAccent = false`) | Fixed Flash Pulse palette |
| `dynamicAccent = true` (Android 12+ / SDK 31) | Wallpaper scheme tints **only** `accentPrimary`, `accentSecondary`, `textLink` |
| Surfaces, neutrals, bubbles, status tokens (incl. spark `statusTransfer`) | Always Flash-fixed |

**Public API** (`FlashTheme.kt`):

```kotlin
@Composable
fun FlashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicAccent: Boolean = false,   // default preserves Pulse identity
    ...
)
```

- On SDK ≥ 31 with `dynamicAccent = true`, `dynamicDarkColorScheme`/`dynamicLightColorScheme`
  provide the wallpaper seed; **only** `scheme.primary` → accentPrimary + textLink and
  `scheme.secondary` → accentSecondary are copied via `FlashColors.withAccent(accent, secondary)`.
- Pure helper `resolveAccent(dynamicAccent, sdkInt, dynamicPrimary?, dynamicSecondary?, fallback)`
  contains all decision logic (SDK gate, null handling) as a JVM-pure function — unit-tested
  without Robolectric in `FlashDynamicAccentTest`.
- SDK < S or a null dynamic color falls back to stock Pulse accents.

**Decisions**
- Accent-only tinting per ADR-005: Material You tonal palettes derive from the wallpaper; adopting
  them for surfaces would erase Flash's graphite/void identity. Google's own guidance recommends a
  static fallback scheme when dynamic color is unavailable — our fixed Pulse palette *is* that fallback.
- `accentSecondary` now follows `scheme.secondary` (falls back to primary hue if absent). The spark
  amber survives through the dedicated `statusTransfer` token, which stays fixed.
- `dynamicAccent` defaults to **false**, unlike most apps where dynamic color is on by default —
  deliberate brand-first tradeoff documented in ADR-005.

**Known limitations**
- Dynamic color affects accents only, never surfaces/neutrals — by design.
- No user-facing settings toggle yet (`follow system / light / dark` mode selector is also still
  pending); callers must pass `darkTheme`/`dynamicAccent` explicitly. Android guidance maps those
  modes to `UiModeManager.setApplicationNightMode` (API 31+) / `AppCompatDelegate.setDefaultNightMode`
  (API ≤30), persisted via DataStore — to be built in a settings component.
- Wallpaper-derived accents can land below 3:1 against dark surfaces for thin strokes; M3 tones are
  contrast-managed within their own roles but we pair them with Flash surfaces. Acceptance: verify
  send button/cursor visibility during UI-045 QA.

**Sources**
- Enable dynamic color: https://developer.android.com/develop/ui/views/theming/dynamic-colors
- M3 in Compose (dynamic schemes, accessibility): https://developer.android.com/develop/ui/compose/designsystems/material3
- Color system / HCT tones & contrast: https://developer.android.com/design/ui/mobile/guides/styles/color
- AOSP tonal palettes (monotone neutrals vs vibrant accents): https://source.android.com/docs/core/display/dynamic-color

### Dark theme (UI-035) — IMPLEMENTED

Principles:

- **AMOLED-friendly** app background (`void`)
- **Layered grays** (surface0–3) for depth — not `#FFFFFF` inversion
- **Brighter accent** on dark (`pulse400`) for WCAG tap targets
- **Outgoing bubbles** deep teal, not bright inverted light blobs
- **Borders** replace shadows for incoming bubble definition
- **Graphite/void rule:** pure black `#000000` / pure white `#FFFFFF` are never used as app or
  surface backgrounds in dark (pure black creates excessive contrast with bright media and cannot
  express elevation layering; Material uses #121212-class grays for the same reason)

**Slot-by-slot audit (light vs dark, 2026-08-22):** every semantic slot was compared. Findings:

| Finding | Action |
|---|---|
| `textOnAccent` dark = white → **2.48:1** on pulse400 (< 3:1 non-text minimum) | **Fixed:** dark now uses pulse900 `#042F2B` (~5.9:1). Light keeps white (3.7:1 on pulse500) |
| `avatarPlaceholderText` dark = graphite500 → ~2.8:1 on surface3 | **Fixed:** graphite300 (~5.8:1); decorative initials, but cheap to correct |
| `textTertiary` / `chatTextTimestamp` identical in both themes | **Accepted deliberately:** graphite500 mid-tone passes ≈4.4:1 on both graphite50 and void |
| `statusOffline` = graphite500 both themes | **Accepted:** status dots never carry meaning by color alone (§ Accessibility), paired with labels |
| Waveform played/unplayed bars (UI-019): accentPrimary on chatBgIncoming ≈ 5.9:1 dark, pulse500 pair ≈ 4.5:1 light; outgoing played bar uses chatTextOutgoing (≥ 12:1 both) | **No change needed** — meets the ≥ 3:1 component-doc requirement |

All remaining slots diverge deliberately (accent400/500, text50/900, surface0–3 vs graphite ramp,
scrim 60% vs 40% alpha, media backdrop darker in dark).

**Verification**
- Unit guards in `FlashDarkPaletteTest`: key slots differ between themes; no pure black/white
  backgrounds; monotonic surface layering; WCAG contrast computed from sRGB luminance
  (body text ≥ 4.5:1, accents/waveform bars/textOnAccent ≥ 3:1).
- `FlashThemeDarkPaletteSweepPreview` in `FlashThemeSwatches.kt` renders every dark
  background/foreground pair for visual QA.

**Known limitations**
- Physical-device dark screenshots beyond UI-001's existing set not yet re-taken after the two
  audit fixes; re-verify send buttons/swipe-reply arrows (textOnAccent consumers) at UI-045.
- Elevation is expressed via authored surface steps, not Compose elevation overlays — intentional
  (no-shadow policy), noted because M3 elevation-overlay guidance does not apply directly here.

**Sources**
- Dark theme implementation & testing: https://developer.android.com/develop/ui/views/theming/darktheme
- App quality dark-theme bar (100% screens, 4.5:1 text contrast): https://developer.android.com/distribute/aep/aep-req-dark-theme
- Theme modes (system default / light / dark, `UiModeManager.setApplicationNightMode`): https://developer.android.com/develop/ui/views/theming/darktheme#ChangeThemes
- Contrast checking tooling (UI Check / Accessibility Scanner): https://developer.android.com/guide/topics/ui/accessibility/testing

---

## Interaction specification

Design system level only:

- Minimum touch target 48dp (`FlashDimensions.minTouchTarget`)
- Text/link colors meet contrast on their surfaces
- Focus order follows visual hierarchy: header → list → composer

Component interactions defined in UI-003+ docs.

---

## Animation specification

See UI-037. UI-001 defines philosophy only; no animation tokens in this component.

---

## Gesture specification

N/A at design-system level. Bubble/composer gestures in UI-005, UI-011.

---

## Accessibility requirements

- Body text contrast ≥ 4.5:1 on bubble and surface backgrounds (verified pulse900 on pulse100, graphite900 on white)
- Large font: typography uses sp; layouts must wrap (verified `@Preview fontScale = 1.5`)
- TalkBack: semantic tokens enable consistent contentDescription styling in components
- Reduced motion: policy documented; tokens in UI-037
- Do not convey status by color alone — pair with icons/labels (UI-030, UI-031)

---

## Responsive behavior

- `bubbleMaxWidthFraction = 0.78` with 320dp cap for tablets/foldables
- Spacing scale unchanged across breakpoints; UI-034 adds adaptive layout rules
- LAN home screen uses separate `FlashMaterialTheme` until redesigned

---

## Dark-mode behavior

Fully specified above. Dark palette is **authored**, not generated from light. UI-035 adds user toggle + QA screenshots.

---

## Performance considerations

- `@Immutable` color/typography data classes — stable for recomposition
- Static spacing/shapes objects — zero allocation
- No dynamic color computation unless `dynamicAccent` enabled
- Previews for light/dark/large font without device

---

## Implementation notes

```
ui/theme/
├── FlashTheme.kt          CompositionLocal + provider
├── FlashColors.kt         Semantic colors + FlashPalette primitives
├── FlashTypography.kt
├── FlashSpacing.kt
├── FlashShapes.kt
├── FlashDimensions.kt
├── FlashElevation.kt
├── FlashThemeSwatches.kt  Preview verification
└── Theme.kt               FlashMaterialTheme (LAN MVP only)
```

Provisional `ui/design/*` deprecated — thin wrappers for migration.

**Dependencies:** None added. Uses existing Compose Material 3 BOM.

---

## Testing checklist

- [x] Compose preview — light swatches
- [x] Compose preview — dark swatches
- [x] Compose preview — large font (1.5x)
- [x] `./gradlew.bat testDebugUnitTest assembleDebug`
- [x] Physical device install (Samsung R5CN21CNJAF)
- [x] Device screenshots — `logs/screenshots/ui-001-conversation-light.png`, `ui-001-conversation-dark.png`
- [x] UI-035 unit palette audit (`FlashDarkPaletteTest`) — contrast + graphite/void guards
- [x] UI-036 unit tests (`FlashDynamicAccentTest`) — SDK gate, null fallback, pass-through
- [x] Previews: `FlashThemeDarkPaletteSweepPreview`, dynamic-accent light/dark sweeps
- [ ] Device re-screenshot of dark theme after textOnAccent/avatarPlaceholder audit fixes
- [ ] RTL spot-check (UI-034)

---

## Known limitations

- Custom font not selected
- `dynamicAccent` (UI-036) has no user-facing toggle yet; no follow-system/light/dark mode selector yet
- Provisional chat components not yet re-validated against new palette on device
- `FlashMotion` not implemented (UI-037)
- Dark theme audit fixes (`textOnAccent`, `avatarPlaceholderText`) not yet re-screenshotted on device

---

## Future improvements

- UI-037: `FlashMotion` tokens
- UI-002: custom icon set aligned to pulse/spark palette
- Settings component: theme-mode selector + `dynamicAccent` toggle (persisted, DataStore)
- UI-045: design-system quality gate

---

## What makes this Flash?

Flash Pulse is built for **local-first P2P** messaging — not a Material template, not a Stream/Telegram clone. The teal **pulse** accent reads as nearby, live connectivity; **spark** amber appears only for transfer speed and activity; **graphite** neutrals stay cool and professional without generic purple Material You. Bubbles use soft 20dp geometry with directional tails but **owned colors and spacing**. Dark mode uses void/layered surfaces instead of washed-out inversion. Flash keeps Material invisible under the hood while every visible pixel comes from documented Flash tokens — calm, fast, and distinctly ours.
