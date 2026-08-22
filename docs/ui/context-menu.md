# Message Focus Overlay & Context Menu (UI-007 & UI-008)

**Status:** IMPLEMENTED  
**Component ID:** UI-007 / UI-008  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI — Component Sequence  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)  
**Code Target:** `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashMessageContextMenu.kt`, `FlashMessageBubble.kt`, `FlashConversationScreen.kt`

---

## Component

- `FlashMessageFocusOverlay` — Full-screen backdrop overlay with frosted scrim/blur that elevates and spotlights the selected message bubble above the dimmed background.
- `FlashMessageContextMenu` — Sculpted contextual menu card anchored immediately beside/above the focused message bubble with quick reaction bar + actions (Reply, Copy, Forward, Select Multiple, Pin, Delete).
- `FlashSelectionToolbar` — Multi-selection contextual action bar entered when the user taps "Select Multiple" from the menu.

---

## Purpose

When a user presses and holds (long-presses) a message, Flash creates an immersive **focused spotlight**:
1. The background chat dimms and softly blurs.
2. The target message bubble scales up subtly and stays sharply in focus.
3. A tactile floating reaction bar and contextual action card appear smoothly anchored directly to the bubble.
4. Tapping outside dismisses the focus overlay with a gentle spring animation.

---

## Research Sources

### Messaging Apps Studied (Clean-Room Analysis)

| Source | Focus & Long-Press Behavior | What Worked | What Did Not Work / Rejected |
|---|---|---|---|
| **Telegram (iOS & Modern Android)** | Long press freezes background, applies backdrop blur/scrim, lifts the message bubble into a spotlight layer, and pops open a floating reaction bubble + action menu with haptic feedback. | Immersive focus, eliminates visual noise, clear spatial connection between menu and message. | Heavy nested sub-menus for simple actions. |
| **iMessage (iOS)** | Long press lifts bubble with spring scale, applies dark frosted glass blur over background, anchors emoji reactions directly on top and contextual menu below. | The gold standard for focused message menus: tactile spring physics, backdrop blur, effortless tap-to-dismiss. | Fixed positioning can push menu off screen on small displays. |
| **Signal Android** | Long press displays floating horizontal reaction pill with bottom action bar. | Fast, snappy response. | Lacks immersive focus backdrop; background remains distracting. |
| **WhatsApp Android** | Floating reaction bar above bubble, bubble highlighted. | Lightweight. | Generic Material menu styling without blur or elevation isolation. |

### Compose & Android Platform APIs
- `Modifier.blur(20.dp)` (Android 12+ / Compose API) with fallback semi-transparent scrim (`colors.scrim` @ 60% opacity) for older Android API levels.
- `Dialog` / `Popup` with custom `PopupProperties(focusable = true, dismissOnClickOutside = true)`.
- `AnimatedVisibility` and `animateFloatAsState` for scale ($1.02\times$) and alpha backdrop animations.
- `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`.
- `FlashTheme` & `FlashMotion` design tokens.

---

## Existing Approaches Studied

### Approach A: Default Compose `DropdownMenu`
- **Pros:** Minimal code.
- **Cons:** Generic Material square popup, no background blur or focus, visually cheap, doesn't match Flash Pulse design system.
- **Verdict:** REJECTED.

### Approach B: Bottom Action Sheet (`ModalBottomSheet`)
- **Pros:** Standard for mobile.
- **Cons:** Disconnects the actions from the actual message bubble at the top of the screen; obscures composer.
- **Verdict:** REJECTED for primary long-press focus.

### Approach C: Modern Immersive Spotlight Focus Overlay with Floating Context Menu (Chosen)
- **Pros:** Dims & blurs everything else, elevates the message bubble with subtle scale ($1.02\times$), presents a sculpted floating menu card with reaction pill and core actions (Reply, Copy, Forward, Select, Delete). Matches the user's explicit request and modern chat UI standards.
- **Verdict:** SELECTED.

---

## Visual Specification

| Element | Token / Spec | Value |
|---|---|---|
| **Backdrop Scrim** | `FlashTheme.colors.scrim` | `Color(0x99000000)` (60% dark wash) |
| **Backdrop Blur** | `Modifier.blur(16.dp)` | 16dp gaussian blur (API 31+ compatible) |
| **Focused Bubble Scale**| `animateFloatAsState` | $1.02\times$ subtle elevation scale |
| **Context Menu Card** | `FlashTheme.colors.backgroundSurface` | Sculpted rounded card (`FlashShapes.large` / 16dp) |
| **Menu Border** | `FlashTheme.colors.borderSubtle` | 1dp border |
| **Menu Text Style** | `FlashTheme.typography.bodyDefault` | `colors.textPrimary` |
| **Menu Action Icons** | `FlashIcons` | `colors.textSecondary` |
| **Destructive Action** | `colors.textError` | Red highlight for "Delete" |
| **Reaction Pill Bg** | `FlashTheme.colors.backgroundSurface` | Pill container holding quick reactions (❤️, 👍, 😂, 😮, 😢, 🙏) |

---

## Interaction Specification

1. **Long-Press on Message Bubble:**
   - Haptic buzz (`HapticFeedbackType.LongPress`).
   - Background blurs and dims.
   - Message bubble lifts into the focus spotlight.
   - Context menu & reaction pill spring into view.
2. **Tap Reaction:**
   - Adds reaction to message.
   - Closes focus overlay.
3. **Tap Menu Action:**
   - **Reply:** Dismisses overlay, sets `FlashComposer` reply dock.
   - **Copy:** Copies message text to clipboard, dismisses overlay.
   - **Forward:** Dismisses overlay, initiates forward sheet.
   - **Select Multiple:** Dismisses overlay, enters `FlashSelectionToolbar` mode with this message selected.
   - **Delete:** Dismisses overlay, triggers delete confirmation/action.
4. **Tap Anywhere Outside / Back Press:**
   - Dismisses focus overlay immediately with smooth fade/scale spring.

---

## Animation Specification

- **Backdrop Scrim Fade:** `fadeIn(tween(150))` / `fadeOut(tween(120))`.
- **Menu Card & Reaction Spring:** `scaleIn(initialScale = 0.85f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeIn()`.
- **Bubble Elevation:** Scale $1.0\times \rightarrow 1.02\times$ over 150ms.
- **Reduced Motion:** If `motion.reduceMotion == true`, instantaneous swap (0ms).

---

## Accessibility Requirements

- **TalkBack:** Announces `"Message options for [Message text]. Swipe to explore actions."`
- **Dismissible:** Full screen touch bounding box handles outside dismissal.
- **Keyboard navigation:** Back button dismisses the overlay.

---

## What Makes This Flash?

Flash's focus overlay feels **instant and cinematic**. Rather than popping up a flat list, it immerses the user in their conversation by softly frosting the background and elevating the selected message with our signature Pulse accent styling and tactile spring dynamics.
