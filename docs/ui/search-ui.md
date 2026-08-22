# Component Research & Design Document — Search UI

**Status:** DESIGNED → **IMPLEMENTED** (UI-023 in-chat search, 2026-08-21; UI-024 chat-list search, 2026-08-22)
**Component ID:** UI-023 (in-chat search) / UI-024 (global & chat-list search — IMPLEMENTED)
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

**UI-023:** `FlashChatSearchBar` + `FlashChatSearchMath` — in-conversation search: a custom search bar replaces the chat header while active, with live match counting (`3 / 7`), previous/next navigation, and substring highlighting inside matching bubbles.

**UI-024:** `FlashChatListSearchBar` + `FlashChatListSearchMath` + `FlashRecentSearchChips` — global / chat-list search: the chat-list top bar swaps into a search field that live-filters conversations by title OR message preview, with a live result count and a recent-searches chip row shown while the query is empty. See the dedicated UI-024 section below.

---

# UI-024 — Global / Chat-List Search

## Component

`FlashChatListSearchBar` + `FlashChatListSearchMath` + `FlashRecentSearchChips` in `ui/chat/.../FlashChatListSearch.kt`, wired into `FlashChatListScreen.kt`.

## Purpose

Finding one conversation among many requires more than scrolling. When search is activated from the chat-list top bar, the bar swaps into a query field and the list live-filters to conversations whose **title or latest-message preview** match, preserving list order. While the query is empty, a recent-searches chip row makes the "empty" state immediately useful. Scope is deliberately tight: conversations only (preview text exists in the model); no full-text message-body indexing.

Status: DESIGNED → **IMPLEMENTED** (2026-08-22).

## Research sources

Online research (consulted 2026-08-22):

- WhatsApp "Recent Searches" on Android — search history listed under a "Recent Searches" heading above the keyboard when tapping the chats-tab search icon; stored and processed entirely on-device, removable per entry ([shiftdelete.net coverage of WA 2.26.3.80](https://en.shiftdelete.net/whatsapp-recent-searches-feature-rolls-out-on-android/)).
- WhatsApp chat filters / Lists — filter row above the conversation list for narrowing by category ([WABetaInfo 2.24.6.16](https://wabetainfo.com/whatsapp-beta-for-android-2-24-6-16-whats-new/), [Android Police](https://www.androidpolice.com/whatsapp-chat-filters-beta/), [WhatsApp Blog: Custom Lists](https://blog.whatsapp.com/focus-on-what-matters-with-custom-lists?lang=en)).
- Telegram upgraded global search — results viewable as a compact grouped list; tag chips under the search bar act as one-tap filters ([telegram.org blog](https://telegram.org/blog/new-saved-messages-and-9-more)); OSS Telegram-client architecture separates local (chat-list) from message search sections ([monogram DeepWiki](https://deepwiki.com/monogram-android/monogram/4.2-chat-list-screen)).
- Slack search — recent searches via clock icon next to the field; suggested results while typing before submitting; result-type grouping ([Slack Help](https://slack.com/help/articles/202528808-Search-in-Slack), [Slack Engineering](https://slack.engineering/search-at-slack/)).
- Discord search case study — key usability findings: filters/options disappearing once typing starts hurts learnability; blank no-results states need explanatory help text; recents before typing reduce time-to-result ([Emily Gueldner case study](https://www.emilygueldner.com/work/discord-search)).
- SaaS search / command-palette patterns — instant typeahead on every keystroke; deliberate empty state offering recents/suggestions instead of blank; Escape-to-dismiss grammar ([saasui.design](https://www.saasui.design/blog/saas-search-command-palette-ux-patterns)).

Prior art inside this repo:

- UI-023 `FlashChatSearchBar` — bar-swap grammar, foundation `BasicTextField` pill, close glyph, polite-live-region counter.
- Material 3 `SearchBar` component — **rejected as visible UI** (§34); only its interaction grammar informed the design.

## Existing approaches studied

1. **Separate full-screen search page** (Signal-style) — heavy context switch for filtering an already-visible list. Rejected.
2. **Desktop command-palette overlay** (Slack ⌘K) — keyboard-first grammar that doesn't translate to mobile P2P use; duplicates navigation surface. Rejected.
3. **Grouped results panel with sections** (chats vs. messages headers — WhatsApp/Telegram/Slack) — valuable at full-text scale, but Flash's model carries only title + preview per conversation, so grouping would add chrome without content. Deferred until message-body indexing exists.
4. **Inline top-bar swap + live flat filter + recents chips while empty** (WhatsApp recents × Slack suggestions × palette empty-state discipline) — **Selected.**

### What worked / did not work

- Worked: live filtering on every keystroke (no submit step); keeping original pinned/sort order so muscle memory still applies; recents visible *before* typing rather than hidden behind a history icon; on-device-only search history (matches Flash's local-first promise and WhatsApp's privacy stance).
- Did not work (in studied apps): options/filters vanishing once typing starts (Discord complaint) → our chip row simply hides when the query is non-blank but returns instantly on clear; relevance re-sorting that breaks list-position memory → rejected in favor of order preservation.

## Chosen approach & why

The screen owns all search state (`isSearching`, `searchQuery`, recent queries as in-memory `List<String>`). When `isSearching`, `FlashChatListSearchBar` replaces `FlashChatListTopBar` in the existing top-bar slot; items are filtered through pure `FlashChatListSearchMath.filterChats` (title OR previewText, case-insensitive, original order preserved) and rendered by the unchanged `FlashChatListRow`. A null result count hides the counter (query empty); zero renders "No matches"; otherwise "n chats" in a polite live region. Recent-search chips appear as the first lazy item only while the query is blank. All matching/counting logic is pure Kotlin; zero new dependencies.

### Behavior specification

| Input | Behavior |
|---|---|
| Search icon (top bar) | Bar swaps into search field; caller focuses input |
| Close glyph | Exit search, clear query state |
| Query text | Live filter on title OR preview; count label updates |
| Query cleared | Full list restored; recents chip row reappears |
| Recents chip tap | Query set to that string (caller responsibility via callback) |
| Clear (chips header) | Caller clears stored recent queries |

### Visual specification

| Element | Token / value |
|---|---|
| Bar container | `backgroundSurface`, `statusBarsPadding`, horizontal `space4` (identical skeleton to UI-023 bar) |
| Field pill | `composerInput` shape, `composerInputBackground`, hairline `borderSubtle`, `bodyDefault` text, `accentPrimary` cursor |
| Placeholder | "Search chats", `textTertiary` |
| Count label | `metadataDefault`, `textSecondary`; zero-match state uses "No matches" in `textTertiary`; polite live region |
| Chips header | "Recent" `metadataDefault` `textTertiary`; "Clear" action `metadataEmphasis` `accentPrimary` |
| Chip | `FlashShapes.chip`, `backgroundSurfaceSubtle`, hairline `borderSubtle`, `captionDefault` `textSecondary` |

### Interaction / animation / accessibility

- Bar swap inherits the host's transition grammar (same slot as the top bar; reduce-motion collapses per `FlashMotion`).
- Counter is a TalkBack polite live region; field pill exposes merged description "Searching <query>, n chats".
- Chips expose Role.Button semantics with explicit "Search for <q>" descriptions; Clear exposes "Clear recent searches".
- 48dp touch targets for close; chips wrap-free single-line horizontal scroll; large fonts degrade gracefully (single-line ellipsis).

## Performance considerations

- Filtering is O(chats × preview length) per keystroke over an in-memory list, memoized via `remember(items, searchQuery)` — trivial at current scales. Revisit with UI-042 if lists grow into thousands or full-text indexing lands.
- Chips row is a plain horizontally-scrolling Row (≤6 entries after `recentSearches` normalization) — no lazy machinery needed.

## Implementation notes

Files:
- `ui/chat/.../FlashChatListSearch.kt` (new): `FlashChatListSearchMath` (`filterChats`, `isSearchActive`, `normalizeQuery`, `resultCountLabel`, `recentSearches`), `FlashChatListSearchBar`, `FlashRecentSearchChips`, previews (light/dark/empty-with-recents/filtered).
- `ui/chat/.../FlashChatListScreen.kt`: added optional params `isSearching/searchQuery/onSearchQueryChanged/onCloseSearch/recentSearches/onRecentSearchClick/onClearRecentSearches` (all defaulted — existing callers compile unchanged); conditional top-bar swap; `displayItems` filtering; recents item; divider correctness with the extra item.
- `ui/chat/src/test/.../FlashChatListSearchLogicTest.kt`: pure-logic tests (title/preview matching, order preservation, blank-query passthrough, label singular/plural, recents dedup/trim/cap).

Dependencies: **none added**.

## Testing checklist

- [x] Unit tests green: filtering (case-insensitive title+preview, order preserved, blank query), search-active gate, count labels, recents dedupe/trim/cap
- [ ] Compose previews render: light, dark, empty query + chips, filtered results
- [ ] Physical device: enter/exit search from top bar, type query, tap chip, clear recents
- [ ] Dark mode + large font spot-check
- [ ] TalkBack: counter announcements, chip descriptions

## Known limitations

- Searches titles and latest-preview text only — no message-body index (model constraint).
- Recent searches are caller-owned and in-memory; not persisted across process death yet.
- No grouped "chats vs messages" result sections (deferred with approach 3).

## Future improvements

- Persisted recent searches (DataStore) once pairing/state storage lands.
- Result grouping + jump-into-conversation-with-highlight by composing with UI-023's pipeline.
- Unread/group/media quick filters (WhatsApp Lists pattern) if list sizes warrant.

## What makes this Flash?

A search surface that behaves like a first-class citizen of a local-first P2P messenger rather than a borrowed cloud-search shell: everything — matching, counting, even the search history — stays on-device; the visual language is the same Flash pill/chip/glyph system as UI-023 and the composer, with zero Material chrome; and the empty state is treated as a feature (one-tap recents) instead of dead space, without imitating any single app's layout.

## Purpose

Finding one message inside a long P2P thread by scrolling is impractical. In-chat search gives instant, local-only lookup: type a fragment, see how many messages match, step through them newest-first with jump-and-highlight. All matching happens on-device — consistent with Flash's local-first promise.

## Research sources

- Telegram Android: header swaps into a search field; counter `n of m`; up/down chevrons cycle results; matched text highlighted inside bubbles.
- WhatsApp Android: magnifier in header; result list panel; jump scrolls + flashes the row.
- Signal Android: full-screen search overlay; per-message highlight.
- Material 3 `SearchBar` component — **rejected as visible UI** (§34): only its interaction grammar (field + results) informs the design.

## Existing approaches studied

1. Separate search *screen* (Signal) — heavy context switch for short threads. Rejected.
2. Results *panel/list* under the bar (WhatsApp) — useful at global scale, overkill per-conversation. Deferred to UI-024.
3. **Header-swap inline search with counter + stepper + in-bubble highlight** (Telegram) — **Selected.**

## What worked / did not work

- Worked: newest-first result ordering; wrap-around stepping; highlighting every occurrence inside the active message.
- Did not work: searching attachment names only (confusing); case-sensitive matching; auto-opening the keyboard without user tap.

## Chosen approach & why

The conversation screen holds `isSearchActive`, `query`, and `activeResultIndex`. A `FlashChatSearchBar` replaces the header via the existing `AnimatedContent` swap (same slot as the selection toolbar). Matches are recomputed from the in-memory message list on each query change (pure `FlashChatSearchMath`), and each result activation jumps using the existing scroll+highlight pipeline from UI-010/UI-021. Zero new dependencies; all matching/counting logic pure Kotlin.

### Behavior specification

| Input | Behavior |
|---|---|
| Search icon (header, all conversations) | Header crossfades into search bar, focus requested |
| Close icon | Exit search, clear query + highlights |
| Query text | Live recompute; matches newest-first; active = first result |
| Next / Previous chevron | Cycle active result with wrap-around; jump + pulse highlight |
| Keyboard IME action Search | Advance to next result |

### Visual specification

| Element | Token / value |
|---|---|
| Bar container | `backgroundSurface`, height `headerHeight`, statusBarsPadding, hairline divider |
| Field pill | `composerInput` shape, `composerInputBackground`, hairline border, magnifier-less (close leads), `bodyDefault` text |
| Counter | `numericEmphasis` (tabular), `metadataDefault` size, `textSecondary`; hidden until ≥1 match |
| Stepper chevrons | Flash-owned back glyph rotated ∓90° (up/down), 48dp targets, `textSecondary` |
| Highlight in bubble | `accentPrimary @ 35%` background span behind each match occurrence; active-result occurrences use `accentPrimary @ 60%` |

### Interaction / animation / accessibility

- Header ↔ search bar swap uses `motion.statusCrossfade()`; reduce-motion collapses.
- Field: Role.Button semantics not needed (editable); merged bar description announces "Searching <query>, n of m".
- Bubbles expose no extra semantics for highlight (visual aid only).
- Large fonts: bar wraps to two rows gracefully (field weight(1f)).

## Performance considerations

- Matching is O(messages × text length) per query keystroke over an in-memory list — trivial at current scales; debounced by Compose's natural frame batching. Revisit with UI-042 if threads grow past thousands.
- Highlight spans built once per (text, query) pair via `remember`.

## Implementation notes

Files:
- `ui/theme/.../FlashText.kt`: added `AnnotatedString` overload (foundation `BasicText` — ADR-009 compliant).
- `ui/chat/.../FlashChatSearchBar.kt` (new): `FlashChatSearchMath`, `FlashChatSearchBar`, `flashHighlightedText`.
- `ui/chat/.../FlashChatHeader.kt`: Search action enabled for ALL conversations (was group-only).
- `ui/chat/.../FlashConversationScreen.kt`: search state, header swap, result jumping, highlight threading through `FlashMessageList` → `FlashMessageBubble`.
- `ui/chat/src/test/.../FlashChatSearchLogicTest.kt`.

Dependencies: **none added**.

## Known limitations

- Matches message text only (no attachment filenames yet).
- No results list panel (that's UI-024 territory).
- Match ranges computed per displayed message; very long threads recompute per keystroke (fine in-memory).

## Future improvements

- Attachment-name + sender-name matching.
- Result list panel shared with UI-024 global search.
- Debounce + search-history chips.

## Testing checklist

- [x] Unit tests green: match ranges (case-insensitive, non-overlapping), result cycling wrap-around, counter label, empty-query behavior
- [ ] Physical device: enter/exit search, type query, step results, highlight visibility both directions + dark mode
