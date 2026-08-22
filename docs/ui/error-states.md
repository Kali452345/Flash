# Component Research & Design Document — Error States

**Status:** DESIGNED (UI-027) · IMPLEMENTED (UI-044, 2026-08-22)
**Component ID:** UI-027, UI-044
**Last updated:** 2026-08-22
**Owner phase:** Premium Chat UI — system states track
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)

---

## Component

`FlashErrorState` — full-container error panel with severity distinction (failure vs environmental/offline), plain-language copy, and exactly one recovery action. Per-message send failures already have inline retry (UI-015 `FlashDeliveryStatusIcon`); this doc covers container-level failures.

## Purpose

When a data surface fails to load, the user must be told what happened in plain language, whether it's their situation or the app's, and given a way forward. Research consensus: an error with no action is a dead end; "Something went wrong" is barely better than blank.

## Research sources

- web.dev offline UX guidelines: distinguish connection failure from app failure; neutral color for environmental conditions (red = error, not offline); always state what the user can still do.
- Coder Legion offline-handling guide (2026): three visual responses for normal / slow / fully-offline; disabled-with-explanation beats hidden; failed ≠ pending.
- UIGuides empty/error guide: name the problem specifically; always include one action (retry / clear / support).
- Android Developers offline-first architecture guide: LCE (Loading-Content-Error) modeling; exponential backoff for retries is an engine concern, not a UI concern.
- 137foundry trust article: errors must be honest about *why*, with recovery paths.

## Existing approaches studied

1. **Generic red banner "Error occurred"** — no cause, no recovery. Rejected.
2. **Full-screen blocking dialog on network failure** — traps users on unstable networks (web.dev anti-pattern). Rejected.
3. **Severity-distinguished inline panel replacing only the failed region, with single Retry** — **Selected.**

## Chosen approach & why

`FlashErrorState(title, message, retryLabel, onRetry, severity)`:
- **Severity.Failure** (data/peer failure): `textError` icon accent (`FlashIcons.Failed`), headline like "Couldn't load conversations".
- **Severity.Environmental** (offline / no reachable peers): neutral styling — `textSecondary` accents with `FlashIcons.Wifi`/`Connection`, because offline is a condition, not a fault (web.dev).
- Both: centered medallion layout matching `FlashEmptyState` (same family), body sentence naming the likely cause, single pill Retry button.

Copy lives in `FlashStateCopy` (pure, unit-tested non-blank + specificity rules). Retry behavior/backoff belongs to the future repository/engine layer (Android LCE guidance) — v1 wires manual retry callbacks.

## Visual specification

Identical skeleton to UI-025 (72dp medallion, `headingSmall`, `metadataDefault`) so empty/loading/error read as one family:
- Failure: medallion fill `textError.copy(alpha = 0.10f)`, icon `textError`.
- Environmental: medallion fill `accentPrimary.copy(alpha = 0.10f)`, icon `textSecondary`.
- Retry pill: `accentPrimary` bg (both severities — recovery is always the primary path).

## Interaction / accessibility

- Retry = Role.Button, 48dp target, press physics as siblings.
- Panel merged semantics announce title + body (state change reaches assistive tech without focus movement).
- No color-only meaning: icon + text carry the severity.

## Known limitations / future

- Auto-retry w/ backoff indicator (UI-044 network simulation) once engine exposes connectivity state.
- Inline banner variant for transient failures deferred.

## What makes this Flash?

The severity split — red for faults, calm Pulse-neutral for "your peer is just not reachable" — encodes Flash's P2P reality (peers go away; that's normal) instead of treating every failure like a server crash, all through the same token family as its empty/loading siblings.

---

# UI-044 — Network-state simulation UI

**Status:** DESIGNED → IMPLEMENTED (2026-08-22)

## Component

`FlashNetworkSimSheet`, `rememberSimulatedHealth`, `FlashNetworkSimMath` — file: `ui/chat/.../FlashNetworkSimSheet.kt` (tests: `src/test/.../FlashNetworkSimLogicTest.kt`). Extends this doc's severity language: the panel only *forces* the four `FlashConnectionHealth` states (UI-030) for demos/QA — it never invents new severities and touches no engine code.

## Purpose

Let developers and QA force conversation-level connection states (Connected / Connecting / Degraded / Offline) on demand, so banner behavior, copy, severity tones, and blocking rules can be demoed and regression-checked without real peers, routers, or Wi-Fi Direct hardware. Engine-independent by construction: the override lives entirely in UI state.

## Research sources

| Source | Takeaway applied |
|---|---|
| [Chrome DevTools — Network features reference](https://developer.chrome.com/docs/devtools/network/reference) | Throttling/override controls live in one panel; DevTools shows a persistent warning icon while an override is active → our sheet copy + "Active" semantics make the override state explicit |
| [Android Emulator — advanced networking](https://developer.android.com/studio/run/emulator-networking-advanced) | Platform precedent: network simulation is a developer tool kept out of product surface |
| [Halcyon Mobile — What could a debug menu contain?](https://halcyonmobile.com/blog/mobile-app-development/android-app-development/what-could-a-debug-menu-contain/) | Debug panels expose state toggles QA can't otherwise reach; must never ship in release builds |
| [Beagle debug menu library](https://github.com/pandulapeter/beagle) | Bottom sheet/drawer are the preferred in-app debug containers; production builds get a noop variant → Flash keeps wiring at call sites so release entry points simply don't reference the sheet |
| [Rork — production-safe in-app debug menu](https://rorklab.net/en/articles/rork-dev/rork-in-app-debug-menu-production-safe-implementation) | Keep real values and overrides separate, merge at read time; badge/label active overrides; entrance gated by build type (seven-tap/shake precedents) → exactly what `rememberSimulatedHealth(real, simulated)` does |
| [Tapadoo/DebugMenu](https://github.com/Tapadoo/DebugMenu) | Shake-to-open vs FAB trigger debate — both acceptable; Flash documents a quieter long-press trigger on existing chrome |

Pattern guidance extracted:

- Overrides must be **separate from reality and merged at read time** (never mutate engine state) — Rork.
- An **active override must be visibly labeled** so testers don't mistake forced states for real ones — Rork badge, DevTools warning icon.
- Debug surfaces **must be excluded from release** by build type, not merely hidden — Halcyon, Beagle noop, Rork.
- Trigger gestures (shake, multi-tap) are conventional; a **long-press on existing chrome** is the least intrusive discoverable option.

## Existing approaches studied & rejected

1. **Shake-to-open gesture** — conflicts with system/app gestures, hard to test in CI, accidental triggers mid-demo. **Rejected** as primary trigger (documented as alternative).
2. **Always-visible settings row** — debug tooling must not leak into user surface (Halcyon/Rork). **Rejected.**
3. **Material `RadioButton` rows** — §34 forbids default Material controls as final visible components; custom drawn indicator matches sibling sheets. **Rejected.**

## Chosen approach & why

Three pieces in one file:

1. **`FlashNetworkSimMath`** — pure logic: `nextHealth(current)` cycles Connected→Connecting→Degraded→Offline→Connected; `healthFromIndex(i)` floor-mod wraps any integer; `simLabel(health)` → "Force …" copy. Unit-tested without instrumentation.
2. **`FlashNetworkSimSheet(currentHealth, onSelect, onDismiss)`** — ModalBottomSheet styled per `FlashAttachmentSheet`/`FlashEncryptionSheet` conventions (radius24 top corners, manual drag handle, navigationBars insets). Title "Network simulation", one selectable row per health state with a drawn radio indicator (`Box`/`CircleShape`, no Material RadioButton), trailing `FlashIcons.Check` on the selected row, explanatory caption that this is a demo override, 48dp Close button. Rows use `Role.Button` + merged semantics ("Force Offline" / "… Active").
3. **`rememberSimulatedHealth(real, simulated): State<FlashConnectionHealth>`** — returns `simulated` when non-null else `real`. Keeps override/reality separation at read time (Rork pattern); caller passes the result into the `AnimatedVisibility` gate from chat-screen.md UI-030.

### Optional trigger affordance (wiring owned by lead)

Long-press on the transport badge opens the sheet — snippet, not wired here:

```kotlin
var showSimSheet by remember { mutableStateOf(false) }
val health = rememberSimulatedHealth(
    real = FlashNetworkStatusMath.resolveHealth(transport, presence, peerCount),
    simulated = simulatedOverride,
)
if (showSimSheet) {
    FlashNetworkSimSheet(
        currentHealth = health.value,
        onSelect = { simulatedOverride = it },
        onDismiss = { showSimSheet = false },
    )
}
```

Apply via `combinedClickable(onLongClick = { showSimSheet = true })` where the badge is placed (debug builds only).

## Visual specification

| Element | Token / value |
|---|---|
| Sheet container | `backgroundSurface`, top radius 24, manual drag handle (36×4dp circle `borderSubtle`), navigationBars insets |
| Title | `headingSmall` + 48dp Close icon button (`FlashIcons.Close`) |
| Override caption | `metadataDefault`, `textTertiary` |
| Row | min height `minTouchTarget`, chip shape, selected bg `backgroundSurfaceSubtle`, else transparent |
| Radio indicator | 20dp circle, 2dp border (`accentPrimary` when selected, `textTertiary` otherwise), inner dot `accentPrimary` when selected |
| Label | `metadataEmphasis`, `textPrimary` |
| Selected check | `FlashIcons.Check`, `iconSm`, `accentPrimary` |

## Interaction / accessibility

- Every row: `Role.Button`, ≥48dp target, merged semantics announcing label (+ "Active" when selected).
- Close button has explicit description; sheet dismissible via swipe/backdrop too.
- Override state never conveyed by color alone — check icon + "Active" text carry selection.

## Known limitations / future

- No auto-clear/TTL of overrides yet (Rork's 24h-TTL + badge idea tracked as follow-up); clearing requires re-opening the sheet or process death.
- Release-build exclusion (build-type gating of the trigger) is integrator responsibility — documented above, not enforced in this file.
- Only whole-state forcing; latency/bandwidth throttling (DevTools-style) would need engine hooks and is explicitly out of scope.

## Testing checklist

- [x] Unit tests — `FlashNetworkSimLogicTest`: cycle order, no-self-return invariant, positive/negative index wrapping, full-cycle coverage, state-order parity, "Force …" labels, distinctness/non-blank guard
- [x] Compose previews — sheet light (offline forced) / dark (connected default)
- [ ] Device QA — long-press trigger + TalkBack pass (lead wires trigger)
- [ ] Demo run-through driving `FlashConnectionBanner` through all four states

## Implementation notes

```
ui/chat/
├── FlashNetworkSimSheet.kt   — sim math, effective-health holder, sheet
└── src/test/.../FlashNetworkSimLogicTest.kt
```

## What makes this Flash?

A QA rig that speaks the same visual language as the product: the panel forces exactly the calm-vs-attention states defined by error-states.md/UI-030, keeps override and reality separate so demos never lie about the engine, and stays invisible to end users — Flash's P2P flakiness becomes rehearseable without faking it.
