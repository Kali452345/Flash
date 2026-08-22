# profile-ui

**Status:** UI-032 **DESIGNED → IMPLEMENTED** (2026-08-22)
**Component ID:** UI-032 (device pairing flow UI)
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

`FlashPairingDialog` + `FlashPairingMath` + `FlashPairingRequestUi`/`FlashPairingPhase` (`ui/chat/.../FlashPairingFlow.kt`, tests in `ui/chat/src/test/.../FlashPairingLogicTest.kt`) — the pairing **consent flow between two nearby devices**: an in-screen centered card overlay showing who wants to pair, a 6-digit numeric comparison code, a live countdown, Accept/Decline actions, and distinct terminal states for Awaiting / Paired / Declined / Expired.

Scope note: this is the **flow UI only** with a demo state machine. Pairing crypto/handshake is engine-side (owned elsewhere); engine hooks replace the demo state later.

## Purpose

When a nearby device requests pairing, Flash must let the user answer three questions before trusting the link: *who* is asking, *how do I know the channel isn't intercepted*, and *what happens if I do nothing*. The numeric-comparison pattern answers all three: identity via avatar/name/transport, MITM resistance via a code both screens must show, and safety via an always-available decline plus a visible timeout.

## Research sources

Pattern study only — nothing copied; all visuals are Flash Pulse tokens.

- Bluetooth SIG blog — "Bluetooth Pairing Part 4: LE Secure Connections – Numeric Comparison" (<https://www.bluetooth.com/blog/bluetooth-pairing-part-4/>): both devices compute/display a **6-digit confirmation value**; user checks match and confirms YES/NO; mismatch ⇒ abort. Only two buttons needed — no keypad.
- Silicon Labs — "Security Pairing Processes" (<https://docs.silabs.com/bluetooth/latest/bluetooth-security-pairing-processes/>): numeric comparison is the *authenticated* association model vs "Just Works" (unauthenticated); both sides must display simultaneously.
- Nordic Developer Academy — "Pairing process" (<https://academy.nordicsemi.com/courses/bluetooth-low-energy-fundamentals/lessons/lesson-5-bluetooth-le-security-fundamentals/topic/pairing-process/>): method taxonomy; Just Works = accept-only with no verification.
- BLEFYI glossary — "Numeric Comparison" (<https://blefyi.com/glossary/numeric-comparison/>): typical flow takes 3–5 s; glance + tap beats typing a passkey; mismatch indicates a MITM attempt.
- Signal blog — "Safety number updates" (<https://signal.org/blog/safety-number-updates/>): reduce comparison burden by grouping digits (12×5 groups); one shared value per conversation rather than per-user fingerprints.
- Signal Support — "What is a safety number…" (<https://support.signal.org/hc/en-us/articles/360007060632>) and EFF SSD "How to: Use Signal" (<https://ssd.eff.org/module/how-to-use-signal>): compare in person; explicit "Mark as verified"; changed/unexpected values deserve friction.
- Signal blog — "A Synchronized Start for Linked Devices" (<https://signal.org/blog/a-synchronized-start-for-linked-devices/>): linked-device consent = explicit primary-device approval step before any key exchange.

### Extracted pattern guidance

| Pattern | Application in UI-032 |
|---|---|
| Explicit consent steps | RequestReceived shows peer identity → code → Accept/Decline; nothing auto-pairs |
| Simultaneous code comparison | 6-digit code shown on both devices; large grouped digits ("123 456") |
| Mismatch ⇒ abort | Decline exits immediately, no partial trust state |
| Cancel safety | Back, scrim tap, and Decline always available; no side effects on cancel |
| Timeout feedback | BLE-style short window (~30 s) with visible countdown; expiry ≠ fault |

## Existing approaches studied

1. **System Bluetooth pairing dialog clone** — OS-chrome look, no brand control, hides countdown. Rejected.
2. **Passkey entry (type digits from peer)** — requires keyboard UI; slower than glance+tap for phone-to-phone where both have screens. Rejected as default (numeric comparison is strictly easier).
3. **Numeric-comparison consent card with countdown** (Bluetooth/Signal consensus adapted to Flash tokens) — **Selected.**

## What worked

- Two-button confirm (Accept/Decline) around a large grouped code — lowest-effort authenticated pairing.
- Grouped digits ("123 456") reduce misreading vs one unbroken string (Signal's finding).
- Distinct terminal visuals: success (green), declined (red/fault), expired (**neutral/environmental**, per `error-states.md` severity language — a timeout is a condition, not an error).
- In-screen overlay (scrim Box + `BackHandler`) so dismiss taps land on first contact (same rationale as the message focus overlay).

## What did not work

- Unbroken 6-digit strings are harder to compare at arm's length (Signal pre-grouping era).
- Auto-dismissing terminal states without copy leaves users unsure whether pairing succeeded — Paired state keeps an explanatory note instead of vanishing instantly.
- Treating expiry as a red error reads as device failure; neutral styling matches reality.

## Chosen approach

- **State model:** `FlashPairingPhase { Idle, RequestReceived, AwaitingPeerConfirmation, Paired, Declined, Expired }` + `FlashPairingRequestUi(peerName, peerInitials, numericCode, transport, expiresInSeconds = 30)` defined UI-side; core models untouched (separate owner).
- **Pure logic** in `FlashPairingMath`: `formatCode` (digits-only, pad/truncate to 6, "123 456"), `canConfirm(phase)` (only RequestReceived), `tickCountdown(secondsLeft)` (≤0 ⇒ Expired), `phaseStatusLabel`, `initialsFor`, `transportLabel`.
- **Presentation:** full-size scrim `Box` (colors.scrim token) + centered card column; card swallows clicks; `BackHandler` gated on visibility; scrim/back = dismiss (cancel-safe).
- **Countdown:** Canvas-drawn hairline track + `accentPrimary` progress bar; polite liveRegion seconds label.
- **Buttons:** custom pills — Accept `accentPrimary`/`textOnAccent`, Decline `backgroundSurfaceStrong`/`textPrimary`; 48dp min target; `Role.Button`.

## Why it was chosen

Numeric comparison is the strongest low-friction consent pattern for two screen-equipped phones (the Flash case), and every element renders through Flash-owned primitives (`FlashText`, `FlashIcons`, tokens) — no Material visible components, no new dependencies, logic fully unit-testable.

## Visual specification

| Element | Token / value |
|---|---|
| Scrim | `colors.scrim`, container padding `space20` |
| Card | `backgroundSurface`, border hairline `borderSubtle`, radius24, padding `space24` |
| Avatar | `FlashAvatar` seeded by peer name, `avatarXl` (64dp) |
| Peer name / subtitle | `headingMedium` `textPrimary`; "wants to pair" `captionDefault` `textSecondary` |
| Transport line | glyph `iconSm` `textTertiary` + "via LAN/Wi-Fi Direct/Relay" `metadataDefault` |
| Code tiles | radius16, `backgroundSurfaceSubtle` + hairline `borderSubtle`; `numericEmphasis` scaled to 30sp/36sp, letterSpacing 2sp, `FontFamily.Monospace`, `accentPrimary` |
| Countdown | Canvas 4dp (`space4`) bar; track `backgroundSurfaceStrong`, progress `accentPrimary` |
| Seconds label | `metadataEmphasis` `textSecondary`, polite live region |
| Accept pill | `accentPrimary` bg, `textOnAccent` `bodyEmphasis`, radiusFull, ≥48dp height |
| Decline pill | `backgroundSurfaceStrong` bg, `textPrimary` `bodyEmphasis` |
| Paired medallion | `avatarXl` circle `statusOnline.copy(alpha=0.14f)` + `FlashIcons.Verified` `textSuccess` |
| Declined medallion | circle `textError.copy(alpha=0.12f)` + `FlashIcons.Failed` `textError`; headline "Request declined" |
| Expired medallion | circle `backgroundSurfaceStrong` + `FlashIcons.Clock` `textSecondary` (environmental severity) |

## Interaction specification

- RequestReceived: Accept → `AwaitingPeerConfirmation`; Decline/scrim/back → dismissed (`Declined` is engine-reported when *peer* declines). Countdown ticks via `tickCountdown`; at 0 → `Expired`.
- AwaitingPeerConfirmation: buttons removed (decision made locally); pulsing dot + "Waiting for {peer} to confirm…".
- Paired: auto-dismiss note (engine will close); Declined/Expired show Close pill + retry hint pointing at Nearby Devices (§22 flow).

## Animation specification

- Pulsing dot: infinite alpha 1↔0.25 ping-pong at `motion.slowMillis` half-period; static under reduce-motion.
- No entrance choreography yet (overlay appears with parent layout); candidates documented under Future improvements.

## Gesture specification

- Tap scrim = dismiss/cancel. Hardware back = dismiss/cancel. No swipe affordances (card is modal-consent, not a sheet).

## Accessibility requirements

- Identity block merged semantics: "{name} wants to pair over {transport}. Verify the code matches on both devices."
- Code row merged semantics: "Verification code {123 456}. Confirm it matches the other device."
- Buttons: `Role.Button` + explicit descriptions ("Accept pairing request", "Decline pairing request").
- Seconds label: `liveRegion = Polite` announcing "{n} seconds remaining" without stealing focus.

## Responsive behavior

- Card fills width minus `space20` margins; centered vertically. Fine on phones/tablets; a fixed max-width cap can be added if tablet QA finds it too wide.

## Dark-mode behavior

All colors are semantic tokens; dark theme inherits (`FlashColors.dark()`). Code tiles/countdown use accent + surface tokens that flip correctly. Dark preview included.

## Performance considerations

Static composition except the pulsing dot (`InfiniteTransition` on one float) and countdown recomposition driven externally by the tick owner; Canvas draws two rounded rects per frame of change. No allocations in draw.

## Implementation notes

Files: `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashPairingFlow.kt`, `ui/chat/src/test/java/com/transfer/flash/ui/chat/FlashPairingLogicTest.kt`. Dependencies added: **none**. Engine integration point: caller owns ticking (`secondsLeft`) and phase transitions; `tickCountdown` documents the contract. Build/device verification deferred to lead engineer (no Gradle run this session).

## Testing checklist

- [x] Unit tests written: formatCode (split/strip/pad), canConfirm matrix, tickCountdown expiry, status labels (distinct/non-blank), initialsFor, transportLabel
- [ ] Lead engineer: module build + unit tests green
- [ ] Compose previews render (request/awaiting/paired/declined/expired/dark)
- [ ] Physical device: two-phone demo state walkthrough, back/scrim dismissal, TalkBack announcement order

## Known limitations

- Demo state machine only — no real crypto, no persistence, no engine callbacks.
- Expiry applies to the whole request window; per-side timeouts (peer-decision window) are engine concerns.
- No haptics yet (add on Accept once component is wired to engine events).

## Future improvements

- QR-code alternative comparison path for noisy rooms (Signal pattern).
- Trust-state surfacing after pairing (verified badge in chat header).
- Entrance scale/fade choreography via `FlashMotion` once overlay lifecycle is engine-driven.

## What makes this Flash?

The pairing moment speaks Flash's P2P language — transport glyph ("via Wi-Fi Direct"), seeded avatar palette consistent with every other surface, severity-distinct outcomes that treat a peer timeout as normal mesh weather rather than an error — while the consent ritual itself follows the security-industry numeric-comparison standard rendered entirely in Flash Pulse tokens. No stock dialog window, no Material chrome, no cloned system pairing screen.
