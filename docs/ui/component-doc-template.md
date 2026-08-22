# Component Research & Design Document — Template

**Status:** NOT STARTED | IN RESEARCH | DESIGNED | IMPLEMENTED | VERIFIED | ACCEPTED

**Component ID:** UI-XXX  
**Last updated:** YYYY-MM-DD  
**Owner phase:**

---

## Component

Name of the component (e.g. `FlashComposer`).

## Purpose

What user problem this component solves and where it appears in Flash.

## Research sources

List every source consulted (official docs, apps studied, OSS repos, design references).

## Existing approaches studied

At least three approaches where practical. Summarize each.

## What worked

Patterns worth adopting (with attribution where relevant — do not copy proprietary UI code).

## What did not work

Patterns rejected and why.

## Chosen approach

The selected design direction for Flash.

## Why it was chosen

Decision rationale. Link to ADR if architectural.

## Visual specification

Colors, typography, spacing, shapes, sizes, states (reference `FlashTheme` tokens only).

## Interaction specification

Tap, long-press, swipe, keyboard, focus, selection, disabled behavior.

## Animation specification

Triggers, initial/target states, duration/spring, easing, interruptibility. Reference `FlashMotion` tokens.

## Gesture specification

Thresholds, conflicts, RTL, edge cases.

## Accessibility requirements

TalkBack, large text, contrast, reduced motion, keyboard navigation.

## Responsive behavior

Phone, large phone, tablet, foldable, landscape, desktop window.

## Dark-mode behavior

Deliberate dark palette — not simple color inversion.

## Performance considerations

Recomposition, lazy list impact, memory, animation cost.

## Implementation notes

Target files/packages. Dependencies (if any) with license note.

## Testing checklist

- [ ] Compose preview
- [ ] Physical device
- [ ] Dark mode
- [ ] Large font / display size
- [ ] RTL (if applicable)
- [ ] Reduced motion
- [ ] Performance spot-check

## Known limitations

Current gaps accepted for this phase.

## Future improvements

Follow-up work after acceptance.

## What makes this Flash?

One paragraph: originality vs generic Material or clone apps.
