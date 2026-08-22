# Flash UI Pages & Navigation Plan — PART 2

**Status:** PROPOSED (planning only — nothing implemented)
**Created:** 2026-08-22 · Last restructured: 2026-08-22 (split into two parts)
**Companion doc:** [core-upgrade-plan.md](core-upgrade-plan.md) — **PART 1: Core Components**
**Related:** ADR-009 · UI-033 (Navigation, implemented) · UI-034 (Adaptive layouts, implemented)

> This is **PART 2**: every page the app will ship, which core APIs each consumes (from PART 1), and how the shell fits together.
> All core upgrade steps live in **PART 1**.

---

## Removed Demo Pages (owner decision, 2026-08-22)

| Removed | Was |
|---|---|
| Icon QA sheet (`FlashIconSheet`) | Icon preview grid |
| Motion QA sheet (`FlashMotionSheet`) | Animation probe page |
| Experimental WS transfer (`WsTransferScreen`, `:ui:transfer`) | Manual host/port WS demo |
| LAN discovery demo home (`FlashHomeScreen`) | NSD scan/probe demo |

Engine classes remain in `:app` pending relocation (PART 1, C4/C5). QA coverage for icons/motion now lives in Compose `@Preview`s and unit tests instead of dedicated pages.

---

## App Shell

```text
FlashApp
 ├── Bottom navigation (4 tabs + Send FAB on Chats)
 │     Chats │ Transfers │ Nearby │ Settings
 ├── Tab content = top-level destination (tab switches do NOT push nav stack)
 └── Pushed destinations (push onto UI-033 stack):
       Conversation(conversationId) · Pairing overlay · Media viewer
```

- Navigation state: existing `FlashNavigationState` (UI-033) extended with a `Settings` destination; tab selection is shell-level state, not stack entries.
- Transitions: `motion.screenEnter()/screenExit()` via `FlashAnimatedScreen`; overlays use their own in-screen scrims.
- Back: pops pushed destinations first; on root tab, standard system back exits.
- Adaptive (UI-034): Expanded width renders Chats+Conversation as two panes; Transfers/Nearby/Settings remain single-pane full-width.
- Add `FlashDestination.Settings` to the existing enum.

---

## Page Specs

### P1 — Chats (tab, home)
- **Consumes:** `chats.chatListState` (C6), search filtering (UI-024 math + C1 RecentSearchDao), states UI-025/026/027.
- **Content:** existing `FlashChatListScreen` + search mode + Send FAB (opens attachment palette UI-012).
- **States:** loading skeleton / empty "Find devices" CTA / error retry / populated.
- **Open items:** unread badge dot from read cursors (C6.8); recent-search persistence via DAO.

### P2 — Conversation (pushed)
- **Consumes:** `chats.conversationState`, outbox send (C6.1), receipts/edit/delete (C6.3–6.4), search highlight (UI-023), sync-health hook (C6.5 → banner slot).
- **Content:** existing screen unchanged — voice/file/image cards, reactions, replies, overlay menu, media viewer.
- **Open items:** edit/delete UI affordances once model flags land.

### P3 — Transfers (tab)
- **Consumes:** `transfers.activeTransfers` flow (C5), notification deep-links (C5.12).
- **Content:** NEW `TransfersScreen` reusing UI-016 card language: active transfers with progress/speed/ETA/pause-resume-cancel; history section (completed → open/share/export via SAF); failed rows with retry.
- **States:** empty ("No transfers yet — send something from Chats or Nearby"), active, history.
- **Open items:** replaces the removed experimental WS page as the permanent surface.

### P4 — Nearby (tab)
- **Consumes:** `discovery.discoveredEndpoints/state` (C3 composite transports), pairing events + `FlashPairingDialog` (C2/UI-032), connection banner (UI-030), trusted-peer list (C2 TrustStore), encryption sheet entry (UI-031).
- **Content:** NEW `NearbyScreen`: local identity card (name/device id/port), discovered peer rows with transport glyph + Connect/Pair action, incoming `PAIR_REQUEST` opens pairing overlay, trusted-peers section (view/revoke), background-mode toggle (Phase 6), debug-only network-sim entry point (UI-044).
- **States:** scanning / found N peers / no radios available (explains missing hardware) / paired confirmation.
- **Open items:** radio-permission pre-flight explainer (Nearby Wi-Fi devices / location / notifications).

### P5 — Settings (tab + sub-pages)
- **Consumes:** `settings` DataStore (C1.4), identity store (rename), trust store (revoke), theme APIs (UI-035/036), haptics toggle (UI-039).
- **Sub-pages / groups:**
  - Appearance: theme mode (system/light/dark), dynamic accent toggle, haptics on/off.
  - Identity: display name editor (live preview avatar).
  - Security: encryption explainer entry (UI-031 sheet), trusted peers list + revoke.
  - Data: retention days slider, save-location (SAF), background-mode switch (Phase 6).
  - About: version, protocol version, device id, open-source notices.
- **Open items:** none blocking; pure consumption of Phase 1 stores.

### Overlays (attach to specific hosts)
| Overlay | Host | Source |
|---|---|---|
| Focus/context menu (UI-007/008) | Conversation | exists |
| Attachment palette (UI-012) | Composer | exists |
| Media viewer (UI-018) | Conversation images | exists |
| Pairing dialog (UI-032) | Nearby + any screen on PAIR_REQUEST | C2 wiring |
| Encryption sheet (UI-031) | Header badge / Settings→Security | C2 wiring |
| Network sim sheet (UI-044) | Nearby/debug builds only | C4 states |
| Stress test screen (UI-043) | Debug builds only | perf harness |

---

## Integration Checklist (lead-owned, ordered)

1. Shell: bottom nav bar + tab state + `Settings` destination added to `FlashDestination`.
2. Move chat-list/conversation into shell tabs; strip boolean-flag switching from MainActivity.
3. Build `TransfersScreen` (P3) against C5 flows; delete last dead demo imports.
4. Build `NearbyScreen` (P4) against C3/C2 flows; wire pairing overlay + sim sheet trigger (debug gate).
5. Build Settings tab + sub-pages against DataStore (C1.4).
6. Two-pane pass (UI-034) on expanded width; predictive-back audit.
7. Device verification backlog (see `logs/handoff.md`), then UI-045 quality gate.

---

## What makes this Flash?

Four calm tabs that mirror exactly what a local-first messenger *is*: your conversations, your bytes in flight, the humans within radio range, and the few switches that matter — all chrome drawn with Flash's own icons, motion tokens, and Pulse accent, with the Send action always one thumb-press away.
