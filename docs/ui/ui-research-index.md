# Flash Premium Chat UI — Research Index

Master plan: [`flash-premium-chat-ui-implementation.md`](flash-premium-chat-ui-implementation.md)

Per-component docs use [`component-doc-template.md`](component-doc-template.md).

**Rule:** Research → compare → design → document → implement → test → polish → approve → next component.  
**Do not implement UI components until the component doc reaches at least DESIGNED status.**

---

## Legend

| Status | Meaning |
|---|---|
| NOT STARTED | No research doc filled in |
| IN RESEARCH | Sources being studied |
| DESIGNED | Spec written, not coded |
| IMPLEMENTED | Code exists, not verified |
| VERIFIED | Built + device tested |
| ACCEPTED | Owner approved |

---

## Foundation (must precede feature components)

| ID | Component / doc | File | Status | Depends on |
|---|---|---|---|---|
| UI-001 | Visual identity & design system | [design-system.md](design-system.md) | IMPLEMENTED | — |
| UI-037 | Motion design system | [motion-system.md](motion-system.md) | IMPLEMENTED | UI-001 |
| UI-002 | Custom icon system | [icon-system.md](icon-system.md) | IMPLEMENTED | UI-001 |
| — | Cross-cutting accessibility | [accessibility.md](accessibility.md) | NOT STARTED | UI-001, UI-037 |
| — | Responsive / adaptive layout | [responsive-layout.md](responsive-layout.md) | NOT STARTED | UI-001 |
| — | UI performance gates | [performance.md](performance.md) | NOT STARTED | — |

---

## Component sequence (implementation order)

| ID | Component | File | Status | Blocked by |
|---|---|---|---|---|
| UI-003 | Chat list | [chat-list.md](chat-list.md) | IMPLEMENTED | UI-001, UI-002, UI-037 |
| UI-004 | Chat header | [chat-screen.md](chat-screen.md) (header section) | VERIFIED | UI-001, UI-002 |
| UI-005 | Message bubble system | [message-bubble.md](message-bubble.md) | IMPLEMENTED | UI-001, UI-037 |
| UI-006 | Message insertion animation | [message-bubble.md](message-bubble.md) | IMPLEMENTED | UI-005 |
| UI-007 | Message press & selection | [selection-mode.md](selection-mode.md) | IMPLEMENTED | UI-005 |
| UI-008 | Context menu & focus overlay | [context-menu.md](context-menu.md) | IMPLEMENTED | UI-005, UI-007 |
| UI-009 | Reaction system | [reaction-system.md](reaction-system.md) | IMPLEMENTED | UI-005, UI-008 |
| UI-010 | Reply system | [reply-system.md](reply-system.md) | IMPLEMENTED | UI-005, UI-011 |
| UI-011 | Custom message composer | [composer.md](composer.md) | IMPLEMENTED | UI-001, UI-002, UI-037 |
| UI-012 | Custom attachment button | [attachment-button.md](attachment-button.md) | IMPLEMENTED | UI-011, UI-037 |
| UI-013 | Custom send button | [composer.md](composer.md) | IMPLEMENTED | UI-011, UI-037 |
| UI-014 | Typing indicator | [typing-indicator.md](typing-indicator.md) | IMPLEMENTED | UI-001, UI-037 |
| UI-015 | Delivery / read states | [delivery-status.md](delivery-status.md) | IMPLEMENTED | UI-005, UI-002 |
| UI-016 | File message card | [file-card.md](file-card.md) | IMPLEMENTED | UI-005 |
| UI-017 | Image message | [image-grid.md](image-grid.md) | IMPLEMENTED | UI-005 |
| UI-018 | Media viewer | [media-viewer.md](media-viewer.md) | VERIFIED | UI-017 |
| UI-019 | Voice message playback | [voice-message.md](voice-message.md) | IMPLEMENTED | UI-005 |
| UI-020 | Voice recording interface | [voice-message.md](voice-message.md) | IMPLEMENTED | UI-011, UI-019 |
| UI-021 | Chat scrolling | [chat-screen.md](chat-screen.md) | IMPLEMENTED | UI-005, UI-006 |
| UI-022 | Jump to latest | [chat-screen.md](chat-screen.md) | IMPLEMENTED | UI-021 |
| UI-024 | Global / chat-list search | [search-ui.md](search-ui.md) | IMPLEMENTED | UI-003 |
| UI-031 | Encryption indicators | [chat-screen.md](chat-screen.md) | IMPLEMENTED | UI-030 |
| UI-025 | Empty states | [empty-states.md](empty-states.md) | IMPLEMENTED | UI-001 |
| UI-026 | Loading states | [loading-states.md](loading-states.md) | IMPLEMENTED | UI-001 |
| UI-027 | Error states | [error-states.md](error-states.md) | IMPLEMENTED | UI-001 |
| UI-028 | Group chat header | [group-ui.md](group-ui.md) | IMPLEMENTED | UI-004 |
| UI-029 | Group member presentation | [group-ui.md](group-ui.md) | IMPLEMENTED | UI-028 |
| UI-030 | Device / network status UI | [chat-screen.md](chat-screen.md) | IMPLEMENTED | UI-001 |
| UI-031 | Encryption indicators | [chat-screen.md](chat-screen.md) | IMPLEMENTED (see L63) | UI-030 |
| UI-032 | Device pairing flow UI | [profile-ui.md](profile-ui.md) | IMPLEMENTED | UI-001, UI-037 |
| UI-033 | Navigation | [navigation.md](navigation.md) | IMPLEMENTED | UI-001 |
| UI-034 | Adaptive layouts | [responsive-layout.md](responsive-layout.md) | IMPLEMENTED | UI-033 |
| UI-035 | Dark theme | [design-system.md](design-system.md) | IMPLEMENTED | UI-001 |
| UI-036 | Dynamic color | [design-system.md](design-system.md) | IMPLEMENTED | UI-001, UI-035 |
| UI-038 | Reduced motion / a11y | [accessibility.md](accessibility.md) | IMPLEMENTED | UI-037 |
| UI-039 | Haptics | [motion-system.md](motion-system.md) | IMPLEMENTED | UI-037 |
| UI-040 | Sound feedback | [motion-system.md](motion-system.md) | IMPLEMENTED (opt-in, default off — D6 approved 2026-08-22; device QA pending) | UI-037 |
| UI-041 | Micro-interactions | [motion-system.md](motion-system.md) | IMPLEMENTED | UI-037 |
| UI-042 | Performance research | [performance.md](performance.md) | IMPLEMENTED (code-level; device numbers pending) | Implemented components |
| UI-043 | Large conversation stress test | [performance.md](performance.md) | IMPLEMENTED (harness; device runs pending) | UI-021, UI-005 |
| UI-044 | Network-state simulation UI | [error-states.md](error-states.md) | IMPLEMENTED | UI-030 |
| UI-045 | Design-system quality gate | [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md) | NOT STARTED | All above |

---

## Shared systems (cross-cutting docs)

| Doc | Scope |
|---|---|
| [avatar-system.md](avatar-system.md) | Avatars, presence, palettes |
| [notification-ui.md](notification-ui.md) | In-app + system notification presentation |
| [chat-screen.md](chat-screen.md) | Full conversation screen assembly |

---

## Current codebase note (2026-08-19)

Exploratory clean-room conversation UI exists under `app/.../ui/design/` and `app/.../ui/chat/`.  
It is **provisional** and must be **re-evaluated or replaced** as each UI-00X component completes research-first design.  
Do not treat it as accepted premium UI.

---

## Next action for AI

1. Read [`flash-premium-chat-ui-implementation.md`](flash-premium-chat-ui-implementation.md).
2. **ALL UI-001–045 IDs are now IMPLEMENTED except UI-045** (quality gate — runs after device verification). UI-040 implemented 2026-08-22 (opt-in sounds, default off).
3. Device verification is now the critical path. See `logs/handoff.md` testing backlog.
