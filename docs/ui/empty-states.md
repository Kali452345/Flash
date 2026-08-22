# Component Research & Design Document — Empty States

**Status:** DESIGNED
**Component ID:** UI-025
**Last updated:** 2026-08-21
**Owner phase:** Premium Chat UI — system states track
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)

---

## Component

`FlashEmptyState` — branded placeholder for containers with no content: first-run chat list, empty conversation, no search results (future UI-023/024).

## Purpose

An empty screen must answer three questions (NN/G): what is this screen for, why is it empty, what should I do next. A blank surface reads as a broken app. In Flash the critical first-run moment is an install with zero conversations — the P2P equivalent of an empty inbox, where the correct next action is *find nearby devices*, something generic templates never say.

## Research sources

- NN/g "Designing Empty States in Complex Applications" (2021): communicate status, teach the feature, provide one direct pathway; never dead-end.
- Carbon Design System empty-states pattern: replace the data region entirely; keep words minimal; one action.
- 137foundry "Empty States That Earn Trust": name the screen; differ first-run vs returning-user emptiness; be honest about why it's empty.
- Pixxen SaaS empty-state patterns: one primary CTA on first-run; completion/zero states stay light.
- shadcn empty-inbox example: icon + title + description + single action structure.

## Existing approaches studied

1. **Blank space** — worst option: users can't distinguish empty vs loading vs broken. Rejected.
2. **Generic "Nothing here yet" template reused everywhere** — misses screen-specific teaching; explicitly called out as an anti-pattern. Rejected.
3. **Icon circle + screen-specific headline + one-line explanation + single CTA** (NN/g + Carbon consensus) — **Selected.**

## Chosen approach & why

Centered `Column`: 72dp icon medallion (soft accent-tinted circle + Flash-owned glyph), `headingSmall` headline naming the screen, one-sentence body explaining how content arrives, optional single pill CTA. Zero new dependencies; pure token styling; screen-specific copy lives in `FlashStateCopy` (pure, unit-tested).

### Copy (Flash-specific, not generic)

| Kind | Headline | Body | Action |
|---|---|---|---|
| ChatListFirstRun | No conversations yet | Start a chat with a nearby device and it will show up here. | Find devices |
| ConversationEmpty | Say hello | Messages you send appear right here — everything stays on your network. | — |
| NoResults (future search) | No matches | Nothing matches your search. Try different words. | Clear search |

## Visual specification

- Medallion: 72dp circle, `accentPrimary.copy(alpha = 0.10f)` fill, Flash icon `iconLg` at `accentPrimary`.
- Title: `headingSmall`, `textPrimary`; body: `metadataDefault`, `textSecondary`, centered, max ~2 lines padding.
- CTA: pill (`FlashShapes.avatar`), `accentPrimary` bg, `textOnAccent` label, 48dp target.
- Whole block centered vertically at ~40% height (not dead-center — feels less like a crash).

## Interaction / animation / accessibility

- CTA press scale 0.97× via `springSnappySpec()`.
- Entrance: fade+rise quarter-height `tweenNormalSpec()`; reduce-motion → none.
- Container semantics: merged description = headline + body; decorative medallion `null` description.

## Known limitations / future

- Search-no-results variant wired when UI-023/024 land.
- No illustration artwork yet — icon medallion is deliberate (research: illustrations optional; clarity does the work).

## What makes this Flash?

The copy is about *nearby devices* and *staying on your network* — P2P identity no template can produce — rendered entirely from Flash tokens with Flash-owned glyphs, and the same medallion language reused by loading/error siblings so all three states read as one family.
