# Component Research & Design Document — Loading States

**Status:** DESIGNED
**Component ID:** UI-026
**Last updated:** 2026-08-21
**Owner phase:** Premium Chat UI — system states track
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)

---

## Component

`FlashSkeletonChatList` + `FlashSkeletonConversation` — layout-matched skeleton placeholders for the two data surfaces (conversation list rows, message bubbles), with a delay-guard rule and reduce-motion-safe pulse.

## Purpose

While conversation/message data resolves, show structure-preserving placeholders so users keep orientation instead of staring at a spinner or a blank screen. Flash's list row geometry is highly predictable — exactly the condition where research says skeletons beat spinners.

## Research sources

- NN/g video "Skeleton Screens vs Progress Bars vs Spinners" (2024): skeletons = structural preview for page-shaped loads.
- 72technologies loading-pattern guide (2026): skeletons require predictable layout, matched dimensions within ~10%, matched row count; static muted blocks read as loading without animation cost; shimmer optional; `prefers-reduced-motion` guard mandatory.
- accessible-data-interfaces.com: skeletons are decorative — keep them out of the accessibility tree, announce state via a polite status message (`aria-busy`/live-region equivalents in Compose = `liveRegion`/merged content description); never destroy focus during load.
- Codexical review of Viget 2017 / ACM ECCE 2018 studies: mismatched or generic skeletons can feel *slower* than spinners; accuracy is what makes them work → Flash skeletons mirror real row/bubble geometry closely.
- web.dev offline UX guidelines: always show that loading is happening; don't block content.

## Existing approaches studied

1. **Full-screen centered spinner** — no structure, no ETA, feels stalled on >1s loads. Rejected as primary pattern (allowed only inline inside controls).
2. **Generic gray bars unrelated to final layout** — creates false expectations (Viget failure mode). Rejected.
3. **Layout-matched skeletons with soft opacity pulse + 300ms delay guard** — **Selected.**

## Chosen approach & why

Two skeleton composables mirroring real geometry:
- Chat list: N rows at `chatListRowHeight` (72dp) — 48dp avatar circle + title bar (~40% width) + preview bar (~65%) exactly where the real row places them.
- Conversation: alternating bubble rounded-rects (outgoing/incoming widths ~70/60%, bubble radii) above a composer pill.

Pulse = opacity 0.5↔1 loop (`tweenNormalMillis`); under `reduceMotion` placeholders render fully static (accessible default per research). Delay guard: `shouldShowLoadingIndicator(elapsedMs ≥ 300ms)` prevents flash on fast loads (pure function, unit-tested). Skeletons carry `clearAndSetSemantics {}`-style null semantics — they are decoration; the host screen announces "Loading…" via its own merged description.

## Visual specification

- Placeholder fill: `backgroundSurfaceSubtle` on `backgroundApp` (list), `chatBgIncoming` @ 60% (bubbles) — ≥1.5:1 against background per contrast guidance.
- Row height matches `FlashDimensions.chatListRowHeight`; avatar circle matches `avatarLg`.
- No shimmer gradient in v1 (research: static/pulse is the accessible default; shimmer reserved for hero content later).

## Accessibility requirements

- Skeletons excluded from semantics (no names/roles — visual promise, not content).
- Screen-level "Loading" announcement responsibility documented for hosts.

## Known limitations / future

- Hosts don't yet emit TalkBack "loading/loaded" announcements (needs repository wiring) — tracked for the states integration pass.
- Shimmer variant may be tested later behind UI-042 measurement.

## What makes this Flash?

The skeletons are dimensionally honest — same 72dp rows, same avatar sizes, same bubble shapes the app actually renders — so the handoff from placeholder to content is seamless, and the pulse respects the same `reduceMotion` contract every other Flash component obeys.
