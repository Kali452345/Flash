# Flash Premium Chat UI — Implementation Master Plan

**Status:** PLANNING — documentation only; **no component implementation without per-component research docs.**  
**Audience:** AI agents and human contributors working on Flash messaging UI.  
**Index:** [`ui-research-index.md`](ui-research-index.md)  
**Template:** [`component-doc-template.md`](component-doc-template.md)

---

## 1. Mission

Flash is a **local-first peer-to-peer** communication application. Networking (LAN, Wi‑Fi Direct, future relay) is separate from UI.

This plan defines how to build a **completely custom, premium, fluid messaging interface** that feels as polished as leading messaging products (Telegram, Signal, WhatsApp-level *quality*) while:

- **Not** copying their visual identity
- **Not** depending on third-party chat UI SDKs (including Stream — see ADR-003 in `docs/decisions.md`)
- Maintaining Flash's **own visual language**

### Product scope (UI must support eventually)

- 1-to-1 and group messaging
- File transfer, images, video, voice messages, attachments
- Reactions, replies, delivery/read states
- Device-to-device communication over LAN, Wi‑Fi Direct, future multi-hop/relay

---

## 2. Core principles (non-negotiable)

### DO NOT

- Build a generic Material 3 chat app
- Use stock Material icons as the final visible icon system
- Use generic rounded rectangles for everything
- Use default `TextField` / `OutlinedTextField` / `TextArea` as the **final** composer
- Use default Material message bubbles or default `TopAppBar` as finished UI
- Blindly copy Telegram, WhatsApp, Signal, Slack, or Stream
- Implement entire screens before researching individual components
- Copy proprietary SDK/source (Stream License, etc.)

### DO

```text
Research → compare → design → document → implement → test → polish → approve → next component
```

Every major UI component requires its own research/design record in `docs/ui/`.

---

## 3. Technology direction

### Use

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Foundation | Material 3 / M3 Expressive **only** as infrastructure (a11y, semantics, layout primitives, adaptive APIs) |
| Motion | Compose Animation, gestures, shared-element transitions where appropriate |
| Async | Coroutines + Flow |
| Local state | Room where UI persistence is required |
| Images | Research first; e.g. Coil/Landscapist — document license + decision before adding |
| Illustration | Optional Lottie for selected cases only — document decision |

**Material 3 must not determine Flash's visible identity.**

Verify current Android/Compose documentation before choosing animation or UI APIs.

---

## 4. Research-first procedure (every major component)

1. Search existing high-quality implementations  
2. Search official Android / Jetpack / Compose documentation  
3. Search high-quality open-source Android apps (license-checked)  
4. Study interaction patterns from production messaging apps (reference only)  
5. Identify what feels good and what fails  
6. Compare **≥ 3 approaches** where practical  
7. Choose one approach and document why  
8. Create design specification (`docs/ui/<component>.md`)  
9. Implement **only that component**  
10. Test on physical device when possible  
11. Measure performance where relevant  
12. Check accessibility, dark mode, large font, small screen, gesture conflicts  
13. Document final decision; update logs  
14. Move to next component  

**Do not research the whole UI in one generic pass.**

---

## 5. Documentation structure (`docs/ui/`)

| File | Purpose |
|---|---|
| [`ui-research-index.md`](ui-research-index.md) | Component registry, status, order |
| [`flash-premium-chat-ui-implementation.md`](flash-premium-chat-ui-implementation.md) | This master plan |
| [`component-doc-template.md`](component-doc-template.md) | Required sections for each component |
| [`design-system.md`](design-system.md) | UI-001, UI-035, UI-036 |
| [`motion-system.md`](motion-system.md) | UI-037, UI-039–UI-041 |
| [`icon-system.md`](icon-system.md) | UI-002 |
| [`chat-screen.md`](chat-screen.md) | Screen assembly, UI-004, UI-021, UI-022, UI-030, UI-031 |
| [`message-bubble.md`](message-bubble.md) | UI-005–UI-007, UI-017 |
| [`composer.md`](composer.md) | UI-011, UI-013 |
| [`attachment-button.md`](attachment-button.md) | UI-012 |
| [`chat-list.md`](chat-list.md) | UI-003 |
| [`avatar-system.md`](avatar-system.md) | Avatars / presence |
| [`reaction-system.md`](reaction-system.md) | UI-009 |
| [`reply-system.md`](reply-system.md) | UI-010 |
| [`media-viewer.md`](media-viewer.md) | UI-018 |
| [`file-card.md`](file-card.md) | UI-016 |
| [`voice-message.md`](voice-message.md) | UI-019, UI-020 |
| [`typing-indicator.md`](typing-indicator.md) | UI-014 |
| [`delivery-status.md`](delivery-status.md) | UI-015 |
| [`search-ui.md`](search-ui.md) | UI-023, UI-024 |
| [`navigation.md`](navigation.md) | UI-033 |
| [`profile-ui.md`](profile-ui.md) | UI-032 |
| [`group-ui.md`](group-ui.md) | UI-028, UI-029 |
| [`selection-mode.md`](selection-mode.md) | UI-007 |
| [`context-menu.md`](context-menu.md) | UI-008 |
| [`notification-ui.md`](notification-ui.md) | Notification presentation |
| [`empty-states.md`](empty-states.md) | UI-025 |
| [`error-states.md`](error-states.md) | UI-027, UI-044 |
| [`loading-states.md`](loading-states.md) | UI-026 |
| [`accessibility.md`](accessibility.md) | UI-038 |
| [`responsive-layout.md`](responsive-layout.md) | UI-034 |
| [`performance.md`](performance.md) | UI-042, UI-043 |

Each component doc **must** include all sections from [`component-doc-template.md`](component-doc-template.md).

---

## 6. Flash design system (create before screens)

Centralized tokens — **no scattered magic numbers in composables.**

```text
FlashTheme
FlashColors
FlashTypography
FlashShapes
FlashElevation
FlashSpacing
FlashMotion
FlashIcons
FlashInteraction
FlashDimensions
```

### Example motion tokens

```text
FlashMotion.fast
FlashMotion.normal
FlashMotion.slow
FlashMotion.messageEnter
FlashMotion.messageExit
FlashMotion.screenTransition
FlashMotion.mediaOpen
FlashMotion.composerExpand
FlashMotion.replyExpand
```

Every major animation uses `FlashMotion`. No random per-file durations.

### Design language adjectives

Premium, modern, fast, calm, tactile, lightweight, expressive, slightly playful when appropriate, professional.

### Avoid

Excessive glassmorphism, gradients, blur, shadows, oversized cards, generic "AI app" styling, Material-template appearance, motion that slows communication.

**Goal:** Interface feels alive without being animated for animation's sake.

---

## 7. Target package architecture

```text
ui/
├── theme/
│   ├── FlashTheme.kt
│   ├── FlashColors.kt
│   ├── FlashTypography.kt
│   ├── FlashShapes.kt
│   ├── FlashSpacing.kt
│   └── FlashMotion.kt
├── icons/
│   ├── FlashIcons.kt
│   └── custom/
├── components/
│   ├── FlashAvatar.kt
│   ├── FlashButton.kt
│   ├── FlashIconButton.kt
│   ├── FlashChip.kt
│   ├── FlashSurface.kt
│   └── FlashMenu.kt
├── chat/
│   ├── FlashChatScreen.kt
│   ├── FlashChatHeader.kt
│   ├── FlashMessageList.kt
│   ├── FlashMessageBubble.kt
│   ├── FlashComposer.kt
│   ├── FlashAttachmentButton.kt
│   ├── FlashSendButton.kt
│   ├── FlashTypingIndicator.kt
│   ├── FlashReplyPreview.kt
│   ├── FlashReactionBar.kt
│   ├── FlashFileMessage.kt
│   ├── FlashImageMessage.kt
│   ├── FlashVoiceMessage.kt
│   └── FlashMessageMenu.kt
├── media/
│   ├── FlashMediaViewer.kt
│   ├── FlashImageViewer.kt
│   └── FlashVideoViewer.kt
├── navigation/
│   └── FlashNavigation.kt
└── diagnostics/
    └── UiPerformanceOverlay.kt
```

Existing code under `app/.../ui/design/` and `app/.../ui/chat/` is **provisional** until UI-001+ research completes.

---

## 8. Component registry — requirements

Below: every UI work item from the premium chat prompt.  
**Implement only after the linked doc is at least DESIGNED.**

---

### UI-001 — Visual identity

**Doc:** [design-system.md](design-system.md)

**Research:** Modern messaging visual systems; Telegram, Signal, WhatsApp, iMessage (reference); Compose apps; Material 3 Expressive guidance; typography/spacing/dynamic color systems.

**Deliver:** Flash color system, typography hierarchy, spacing scale, corner-radius strategy, surface strategy, icon philosophy, animation philosophy.

**Originality question:** What makes Flash visually distinct from Material defaults and clone apps?

---

### UI-002 — Custom icon system

**Doc:** [icon-system.md](icon-system.md)

**Do not** use Material Icons as the final visible set except where system icons are mandatory.

**Research:** Custom SVG sets, OSS libraries (license), line vs filled, stroke width, touch targets, active/inactive states.

**Create `FlashIcons` with icons for:**

send, attach, camera, gallery, microphone, stop, play, pause, download, upload, reply, forward, react, search, call, video call, more, back, close, edit, delete, pin, mute, archive, group, device, connection, retry, verified, delivered, read, failed, encryption, relay, Wi‑Fi, Wi‑Fi Direct

**Requirements:** Consistent stroke/shape language, multiple states, content descriptions, optical sizing, animation-capable where useful. Do not recolor Material icons.

---

### UI-003 — Chat list

**Doc:** [chat-list.md](chat-list.md)

**Research:** Telegram, Signal, WhatsApp, iMessage, Compose implementations — row height, avatar, title hierarchy, preview, timestamp, unread, muted, pinned, online, typing, media preview, swipe, long press, selection, animations.

**Flash-specific row required behaviors:**

- Smooth insertion/removal  
- Unread badge animation  
- Preview update animation  
- Typing transition  
- Avatar presence transition  
- Swipe-to-action  
- Press feedback  
- Selection mode  
- Context menu  
- Pinned-state transition  

---

### UI-004 — Chat header

**Doc:** [chat-screen.md](chat-screen.md)

**Do not** ship default `TopAppBar` as finished UI.

**Research:** Telegram/Signal/WhatsApp headers; collapsible headers; avatar transitions; scroll behavior.

**Required:** Back, avatar, display name, online/offline, typing, network/relay indicator, optional call actions, menu, animated state changes, smooth scroll response.

---

### UI-005 — Message bubble system

**Doc:** [message-bubble.md](message-bubble.md)  
**Priority:** HIGH

**Do not** use generic `RoundedCornerShape` rectangle as the final bubble.

**Research:** Bubble geometry, grouping, first/last shape, consecutive compression, in/out differentiation, links, quotes, reactions, media, files, system messages.

**Build `FlashMessageBubble`:**

- Geometry by position in group  
- Natural corner transitions  
- Adaptive width; text/media sizing  
- Reply preview, reactions, delivery state  
- Subtle press animation  
- Context interaction, selection, long-press, highlight  

No hard-coded bubble size.

---

### UI-006 — Message insertion animation

**Doc:** [message-bubble.md](message-bubble.md)

**Research:** Telegram insertion, spring list insert, scale+translation, opacity, scroll behavior.

**Requirements:** No large bounce; works in/out; no layout jumps; lazy list performant; different behavior when scrolled up vs at bottom; historical load vs new message.

---

### UI-007 — Message press and selection

**Doc:** [selection-mode.md](selection-mode.md)

**States:** press, long press, selection, highlighted, copied, replied, forwarded, deleted.

**Research:** Premium apps' transition into selection mode.

**Use:** Subtle elevation/surface change, coordinated background, contextual action bar, gesture-safe touches.

---

### UI-008 — Context menu

**Doc:** [context-menu.md](context-menu.md)

**Do not** use default `DropdownMenu` as final UX.

**Research:** Telegram/Signal/iMessage menus; action sheets.

**Actions:** Reply, Copy, Forward, React, Pin, Save, Select, Delete, Info

**Requirements:** Near message anchor, avoid keyboard/edge clipping, animate from anchor, RTL, large text.

---

### UI-009 — Reaction system

**Doc:** [reaction-system.md](reaction-system.md)

**Requirements:** Quick bar, full picker, animated selection, counts, multiple reactions, emoji appearance, transitions.

**Research:** Native emoji vs Twemoji vs custom vectors — **document licensing**.

---

### UI-010 — Reply system

**Doc:** [reply-system.md](reply-system.md)

**Components:** `ReplyPreview`, `ReplyComposer`, `ReplyMessageReference`, `ReplyJumpAnimation`

**Requirements:** Swipe-to-reply, selected feedback, composer preview, tap-to-jump, highlight original, smooth cancel, keyboard-safe.

Research gesture thresholds first.

---

### UI-011 — Custom message composer

**Doc:** [composer.md](composer.md)  
**Priority:** HIGH

**THIS MUST NOT BE A DEFAULT TEXT FIELD.**

**Build `FlashComposer` — adaptive states:**

```text
Normal | Typing | Multiline | Replying | Editing | Attachment-selected
Voice-recording | Sending | Disabled | Network unavailable
```

**Dynamic layout examples:**

- single-line → multiline → taller composer  
- typing → send replaces voice  
- replying → reply preview above input  
- attachment selected → preview tray expands  

**Research:** Telegram/Signal/WhatsApp composers; custom Compose inputs; IME; multiline measurement.

**Build custom:** background, border/surface, attachment control, send, mic, placeholder, cursor (where practical), padding, shape, adaptive height, keyboard-safe positioning.

---

### UI-012 — Custom attachment button

**Doc:** [attachment-button.md](attachment-button.md)

**Do not** use standard `IconButton` + paperclip as final UI.

**Research:** Telegram attachment UX, expandable/radial/linear menus, sheets, Material motion, gestures.

**Possible interaction:** tap → button transforms → actions emerge

**Actions:** Camera, Gallery, Files, Contact, Location, Music, Document — each with icon, label, enter/exit/press animation, a11y, keyboard nav.

---

### UI-013 — Custom send button

**Doc:** [composer.md](composer.md)

**Stateful:** disabled → ready → pressed → sending → sent → failed

**Motion (research first):** scale, rotation, morph, icon transform, progress ring, success transition.

**Do not** use generic Material FAB.

---

### UI-014 — Typing indicator

**Doc:** [typing-indicator.md](typing-indicator.md)

**Research:** Three-dot systems, waveforms, pulses (Telegram/Signal/etc.).

**Avoid** cartoonish bouncing dots. Prefer staggered opacity, tiny vertical movement, smooth loop.

**Copy examples:** "Alice is typing…" / "Alice and Bob are typing…"

---

### UI-015 — Delivery / read states

**Doc:** [delivery-status.md](delivery-status.md)

**States:** Sending, Sent, Delivered, Read, Failed, Retrying

**Custom symbols** — not Material checks by default.

**Animate:** sending → sent → delivered → read without replaying full animation on every recomposition.

---

### UI-016 — File message card

**Doc:** [file-card.md](file-card.md)

**States:** offered, queued, downloading, paused, completed, failed, cancelled

**Display:** icon, name, extension, size, progress, speed, ETA, pause, resume, retry, cancel, verification, completed.

Must integrate into bubbles — not generic Material `Card`.

---

### UI-017 — Image message

**Doc:** [message-bubble.md](message-bubble.md)

**Implement:** preview, progressive load, blur placeholder (if useful), reveal, download/retry, fullscreen transition, shared element, pinch zoom, swipe dismiss.

**Library:** Research Coil/Landscapist/etc. before adopting — document in component doc + `docs/decisions.md`.

---

### UI-018 — Media viewer

**Doc:** [media-viewer.md](media-viewer.md)

**Requirements:** Shared transition, pinch/double-tap zoom, pan, swipe dismiss, horizontal gallery, video playback, loading/failure, metadata, share, save, forward.

Research gesture priority conflicts.

---

### UI-019 — Voice message component

**Doc:** [voice-message.md](voice-message.md)

**Requirements:** play/pause, waveform, progress, duration, speed, playback/download states.

**Do not** use generic progress bar as final UI.

---

### UI-020 — Voice recording interface

**Doc:** [voice-message.md](voice-message.md)

**Transform:** normal composer → recording mode

**Research:** hold-to-record, slide-to-cancel, lock-to-record, timer, amplitude viz, send/cancel gestures.

**Test all thresholds on physical device.**

---

### UI-021 — Chat scrolling

**Doc:** [chat-screen.md](chat-screen.md)  
**Priority:** HIGH (engineering)

**Must handle:** new messages, image load resize, keyboard open/close, reply, scroll to history, history load, new message while scrolled up.

**Research:** Reverse lists, `LazyColumn`, stable keys, state preservation, auto-scroll, jump-to-bottom.

Build custom "new messages" indicator (when appropriate).

---

### UI-022 — Jump to latest

**Doc:** [chat-screen.md](chat-screen.md)

**Flow:** scroll up → new messages → animated indicator → tap → smooth scroll to bottom.

Must not cover important content.

---

### UI-023 — In-chat search

**Doc:** [search-ui.md](search-ui.md)

**Research:** In-conversation search, highlight, prev/next, keyboard, result count — no abrupt jumps.

---

### UI-024 — Chat list / global search

**Doc:** [search-ui.md](search-ui.md)

**Support:** people, groups, messages, files, media — separate research from UI-023.

---

### UI-025 — Empty states

**Doc:** [empty-states.md](empty-states.md)

**Branded Flash empty states** — not generic "No messages yet".

**States:** no chats, no devices, no connection, no results, no files, no media, empty group.

Animation sparingly.

---

### UI-026 — Loading states

**Doc:** [loading-states.md](loading-states.md)

**Research:** skeletons, shimmer, placeholders — not default `CircularProgressIndicator` everywhere.

Skeletons where layout is known; progress only where progress exists.

---

### UI-027 — Error states

**Doc:** [error-states.md](error-states.md)

**Examples:** Device unavailable, connection lost, transfer failed, message couldn't send, permission required, storage unavailable.

Provide explanation, recovery, retry, dismiss; no raw exceptions to users.

---

### UI-028 — Group chat header

**Doc:** [group-ui.md](group-ui.md)

Group avatar, member count, online count, typing, relay/network, settings, search, media/files.

Adapt to 2 / 5 / 50+ members.

---

### UI-029 — Group member presentation

**Doc:** [group-ui.md](group-ui.md)

Custom member rows: avatar, name, online, role, admin badge, relay status, shared media — avoid stock list-item look.

---

### UI-030 — Device / network status UI

**Doc:** [chat-screen.md](chat-screen.md)

**P2P-aware states:** Direct, LAN, Wi‑Fi Direct, Relay, 2 hops, 3 hops, Offline, Connecting, Reconnecting.

User-friendly copy e.g. `● Direct` or `↗ Connected through 2 nearby devices` — not raw protocol jargon.

---

### UI-031 — Encryption indicators

**Doc:** [chat-screen.md](chat-screen.md)

**States:** Secure, Verifying, Unknown device, Verification required.

Unobtrusive; expandable explanation; no crypto overload.

---

### UI-032 — Device pairing flow UI

**Doc:** [profile-ui.md](profile-ui.md)

**Research:** QR pairing, verification numbers, device confirmation, trusted-device UI.

**Flow:** Discovering → Device found → Pair request → Verification → Trusted

Each state: distinct visual + animation.

---

### UI-033 — Navigation

**Doc:** [navigation.md](navigation.md)

**Do not** use default `NavigationBar` styling as final UI.

**Research:** Telegram nav, bottom nav, adaptive nav, nav rail, desktop windows.

Custom Flash navigation; M3 Adaptive as infrastructure only on large screens.

---

### UI-034 — Adaptive layouts

**Doc:** [responsive-layout.md](responsive-layout.md)

**Targets:** small/large phone, tablet, foldable, landscape, desktop Android windows.

```text
phone:     Chats → Chat
tablet:    Chats | Chat
large:     Chats | Chat | Details
```

Do not stretch phone UI across tablet.

---

### UI-035 — Dark theme

**Doc:** [design-system.md](design-system.md)

Deliberate dark theme — not simple inversion. Research AMOLED, gray surfaces, contrast, images, selection, status colors.

---

### UI-036 — Dynamic color

**Doc:** [design-system.md](design-system.md)

Investigate Android dynamic color. May derive *part* of palette from system while protecting brand colors.

**Priority:** Flash identity > accessibility > system personalization

---

### UI-037 — Motion design system

**Doc:** [motion-system.md](motion-system.md)

Document durations, easing, springs, velocity, entrance/exit, interruption, gesture-driven, shared-element, list movement, state transitions.

**Categories:** Micro, Component, Screen, Gesture, Shared element, Loading, Feedback

Every animation spec includes: trigger, initial, target, duration/spring, easing, interruptibility, reduced-motion behavior.

---

### UI-038 — Reduced motion / accessibility

**Doc:** [accessibility.md](accessibility.md)

Graceful animation degradation. TalkBack, large text, display scaling, keyboard nav, magnification.

Never communicate important info **only** through motion or color.

---

### UI-039 — Haptics

**Doc:** [motion-system.md](motion-system.md)

Subtle haptics: send, attachment, record start/cancel, reaction, pairing success, transfer complete, error. Do not overuse.

---

### UI-040 — Sound feedback

**Doc:** [motion-system.md](motion-system.md) + ADR

Research optional sounds for send/receive/success/failure. **Do not add by default** — document decision.

---

### UI-041 — Micro-interactions

**Doc:** [motion-system.md](motion-system.md)

Intentional micro-interactions: reaction selected, copied, attachment added, file completed, failed, device connect/disconnect, typing start/stop, pinned, muted, archived.

---

### UI-042 — Performance research

**Doc:** [performance.md](performance.md)

Investigate recomposition, LazyColumn, image memory, bitmap decode, animation cost, overdraw, jank, large histories, reactions, rapid arrival.

**Targets:** smooth scroll, responsive typing, no layout jank, minimal unnecessary recomposition. Profile on real devices.

---

### UI-043 — Large conversation stress test

**Doc:** [performance.md](performance.md)

**Test data:** 1k / 5k / 10k messages with text, images, files, reactions, replies, voice.

**Measure:** startup, scroll, memory, frames, image load, insertion.

---

### UI-044 — Network-state simulation UI

**Doc:** [error-states.md](error-states.md)

UI must survive: instant disconnect, slow connection, reconnect, offline, peer gone, transfer pause/resume, relay path change.

React without rebuilding entire screen.

---

### UI-045 — Design-system quality gate

Before declaring premium UI complete, verify:

- [ ] No generic Material icons where custom expected  
- [ ] No default TextField as final composer  
- [ ] No default Material Button for premium actions without justification  
- [ ] No generic message bubble remains  
- [ ] No random animation timings  
- [ ] No duplicate motion definitions  
- [ ] No arbitrary spacing in composables  
- [ ] No hard-coded device-specific dimensions  
- [ ] No unnecessary full-screen transitions  
- [ ] No inaccessible gestures  
- [ ] No unexplained colors  
- [ ] No undocumented borrowed components  

---

## 9. Research source rules

**Prefer (in order):**

1. Android Developers  
2. Official Jetpack documentation  
3. Official library documentation  
4. Active, well-maintained OSS (license verified)  
5. Quality design research  
6. Production apps as **visual/interaction reference only**  

Never rely exclusively on a random GitHub project.

For every third-party library record:

```text
Library | Version | License | Why chosen | Alternatives | Risks | Maintenance
```

---

## 10. Dependency rules

Before adding any dependency:

1. Verify current version  
2. Check maintenance  
3. Check license  
4. Check Android + Compose compatibility  
5. Check duplication of platform capabilities  
6. Check binary size + performance  
7. Check customization limits  
8. Document in component doc + `docs/decisions.md` if architectural  

**Default:** Compose/AndroidX when sufficient; library when meaningful value.

---

## 11. Originality requirement

Every major component must answer: **What makes this Flash?**

Examples:

| Component | Flash differentiation |
|---|---|
| Message bubble | Custom grouping geometry + adaptive status integration |
| Composer | Adaptive shape + attachment morph + state-aware send |
| Network indicator | P2P-aware connectivity visualization |
| File card | Native transfer progress in conversation |
| Pairing | Trust as part of conversation identity |

---

## 12. Animation quality rules

### Avoid

Animation on every element; excessive bounce/scale; long blocking animations; non-interruptible transitions; motion during typing; content jump.

### Prefer

Springs where physical; tweens where deterministic; gesture-driven; interruptible; shared-element for media; subtle opacity/translation; shape morph; coordinated related elements.

### Lazy list rules

Stable keys; no expensive per-item animation state; no infinite off-screen animations; profile before blind optimization.

---

## 13. Physical-device validation

Preview is insufficient for acceptance.

**Test matrix:** low/mid/high-end; Android 13+; current release; small/large phone; landscape.

**Measure when relevant:** frame time, jank, memory, CPU, decode time, startup, scroll.

---

## 14. AI working procedure (UI tasks)

```text
STEP 1  Inspect existing project
STEP 2  Read AGENTS.md, docs/ui/, logs/
STEP 3  Determine current UI state (ui-research-index.md)
STEP 4  Create/update research doc for NEXT component only
STEP 5  Perform research (≥3 approaches)
STEP 6  Document findings in component doc
STEP 7  Design (visual + interaction + motion + a11y)
STEP 8  Implement ONLY that component
STEP 9  Run unit/build checks
STEP 10 Compose previews
STEP 11 Install physical device when appropriate
STEP 12 Test interaction + gestures
STEP 13 Profile if animation/performance sensitive
STEP 14 Fix problems
STEP 15 Document implementation in component doc
STEP 16 Update logs/progress.md, logs/errors.md, logs/handoff.md
STEP 17 Commit if git configured and user requested
STEP 18 Proceed to next component in index order (unless owner reprioritizes)
```

---

## 15. Never do this

```text
❌ Create entire chat screen then design later
❌ Copy Telegram UI then change colors
❌ Material icons everywhere
❌ Default TextField composer
❌ Default Material Card for everything
❌ Arbitrary undocumented animations
❌ Libraries because they look cool
❌ Next component while current has known broken behavior
❌ Copy Stream/Telegram/etc. source or SDKs
```

---

## 16. Completion definition (premium UI)

UI is **not** complete when screens compile.

Complete when:

- Every major component has documented research + design rationale  
- Visual, motion, icon, interaction, and accessibility languages are coherent  
- Composer, bubbles, attachments, icons are custom (or deliberately justified exceptions)  
- Adaptive layouts, dark mode, accessibility verified  
- Performance tested (including UI-043 stress scenarios where practical)  
- Physical-device testing performed  
- Documentation and logs current  
- Next AI can continue without chat history  

---

## 17. Long-term quality target

```text
Telegram-level fluidity
+ Signal-level clarity
+ WhatsApp-level familiarity
+ Flash-specific visual identity
+ native Android performance
+ P2P-aware interaction design
```

**Not** a visual clone of any reference app.

**Objective:** A new messaging interface as refined as the best apps, fully controlled by Flash architecture.

---

## 18. Relationship to existing work

| Artifact | Status |
|---|---|
| `docs/flash-design-system.md` | Early exploratory tokens — supersede via UI-001 research |
| `app/.../ui/design/`, `app/.../ui/chat/` | Provisional scaffold — re-evaluate per component |
| ADR-003 (`docs/decisions.md`) | No Stream SDK/source — still applies |
| LAN MVP UI (`MainActivity` home) | Separate from premium chat track until integrated |

**Next scheduled work:** UI-001 Visual identity — fill [design-system.md](design-system.md) after research. **No implementation until then.**
