# File Message Card — UI-016

**Status:** IMPLEMENTED  
**Component ID:** UI-016  
**Last updated:** 2026-08-20  
**Owner phase:** Premium Chat UI  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

---

## Component

`FlashFileMessageCard` (in-bubble document and file transfer container), `FlashFileIconBadge` (color-coded file extension container with circular progress ring), and `FlashFileTransferProgress` (transfer speed, ETA, and byte formatting).

## Purpose

The file message card provides rich in-stream rendering for document and file transfers. Unlike ordinary messaging apps where files are hosted on cloud servers, Flash files are transferred peer-to-peer over local LAN or high-speed Wi-Fi Direct:
1. **Color-coded file type badge** (PDF, ZIP, APK, Code, Audio, Video, Document, Generic).
2. **Integrated circular progress ring** showing real-time transfer progress with center download/pause/cancel toggle.
3. **Live speed and ETA readout** (e.g. `24.5 MB • 18.2 MB/s • 2s left`) during active LAN transfers.
4. **1-tap open / save action** integrating with Android Storage Access Framework (SAF) and `FileProvider`.
5. **High-contrast surface styling** matching incoming and outgoing bubble palettes.

---

## Research sources

1. **Telegram Android / iOS** — Leading 48dp circular button with progress ring, file extension subtitle, and file name truncation.
2. **Signal Android / iOS** — Compact file card with colored document glyph, file size, and inline save button.
3. **WhatsApp Android / iOS** — Document preview with extension badge and page count.
4. **Discord Mobile** — File attachment card with download icon and file size.
5. **Flash Shared Transfer Engine (`core:transfer`)** — High-speed chunked streaming with real-time byte progress, throughput metrics, and resume support.

---

## Existing approaches studied

### Approach A: Leading Circular Action Icon + Double-Line Text (Telegram)
- 48dp circular icon on the left (showing extension or download progress ring) + file name and file size / speed on the right.
- **Strength:** Extremely clean, compact, fits seamlessly inside standard message bubbles; clear state transitions.
- **Weakness:** File extension text in icon must be concise (max 4 chars).

### Approach B: Full-Width Card with Big Thumbnail (WhatsApp / Slack)
- Large rectangular card previewing document header with bottom info bar.
- **Strength:** High visual presence.
- **Weakness:** Takes up too much vertical space when transferring multiple files.

### Approach C: Plain Text Link with Attachment Icon (Generic Chat)
- Plain text line: "📎 report.pdf (2.4 MB)".
- **Strength:** Minimalist.
- **Weakness:** Lacks tactile feedback, progress rings, and file type recognition.

---

## What worked

| Pattern | Source | Why it works |
|---|---|---|
| Leading 48dp Circular Action Badge | Telegram | Unifies icon, extension badge, and circular progress ring into one interactive touch target |
| Color-Coded File Extension Tinting | Flash Design System | Instant recognition of PDF (Red), ZIP (Amber), Code (Blue), Audio (Violet), Video (Pink) |
| Live P2P Speed & ETA Metrics | Flash Transfer Engine | Critical for high-speed local Wi-Fi Direct transfers (e.g. 50 MB/s transfers) |
| In-Bubble Surface Inset | Flash Bubble System | 8dp rounded inner surface with `FlashDimensions.borderHairline` provides contrast inside both incoming and outgoing bubbles |
| 1-Tap Open Intent Dispatch | Android SAF | Tapping card after download immediately launches system viewer via `FileProvider` |

## What did not work

| Pattern | Source | Why rejected |
|---|---|---|
| Auto-downloading multi-gigabyte files | Cloud apps | On P2P transfers, receiving large files requires explicit acceptance/tap |
| Full PDF multi-page canvas renderers inside bubbles | WhatsApp | Heavy memory overhead; slow list flings |

---

## Chosen approach

**Flash Tactile File Card (`FlashFileMessageCard`):**

1. **Badge & Progress Container (`FlashFileIconBadge`)**:
   - Size: $48\text{dp} \times 48\text{dp}$ circle with $1\text{dp}$ subtle border.
   - States:
     - `Idle / Ready`: Shows 3–4 letter uppercase extension badge (e.g. "PDF", "ZIP", "APK") or file category icon.
     - `Transferring`: `CircularProgressIndicator` ($2.5\text{dp}$ stroke in `accentPrimary`) with center Pause / Stop icon.
     - `NotDownloaded`: Download icon (`FlashIcons.Download`) with subtle pulsing container.
     - `Failed`: Error icon with red tint.
2. **Metadata Layout**:
   - Top line: File name in `typography.bodyDefault` (Bold/Medium), truncated at end.
   - Bottom line: Size (e.g. `45.2 MB`) $\bullet$ Status or Speed (`12.4 MB/s • 3s`) in `typography.captionDefault`.
3. **Color Coding**:
   - PDF: `Color(0xFFE63946)` (Red)
   - Archives (`zip`, `rar`, `7z`, `tar`, `gz`): `Color(0xFFF77F00)` (Amber)
   - Code (`kt`, `java`, `py`, `js`, `json`, `html`, `rs`, `cpp`): `Color(0xFF4361EE)` (Blue)
   - Audio (`mp3`, `wav`, `flac`, `m4a`, `ogg`): `Color(0xFF7209B7)` (Violet)
   - Video (`mp4`, `mkv`, `mov`, `avi`): `Color(0xFFD81159)` (Pink)
   - Images (`png`, `jpg`, `jpeg`, `webp`, `svg`): `Color(0xFF00B4D8)` (Cyan)
   - Documents (`doc`, `docx`, `txt`, `md`, `pdf`): `Color(0xFF3F37C9)` (Indigo)
   - Generic / Other: `colors.textSecondary`

---

## Visual specification

| Property | Value |
|---|---|
| Container shape | Rounded rectangle ($12\text{dp}$ radius, `FlashShapes.attachment`) |
| Container background | Outgoing: `colors.chatBgAttachmentOutgoing` / Incoming: `colors.chatBgAttachmentIncoming` |
| Container border | `FlashDimensions.borderHairline`, `colors.borderSubtle` |
| Container padding | Horizontal $12\text{dp}$, Vertical $10\text{dp}$ |
| Badge size | $48\text{dp} \times 48\text{dp}$ |
| Progress ring stroke | $2.5\text{dp}$, `colors.accentPrimary` |
| Max width | $280\text{dp}$ |

---

## Interaction specification

| State | Tap on Badge | Tap on Card Body |
|---|---|---|
| `NotDownloaded` | Starts P2P download | Starts P2P download |
| `Transferring` | Pauses / cancels transfer | Shows transfer diagnostics / details |
| `Downloaded` | Opens file viewer intent | Opens file viewer intent |
| `Failed` | Retries transfer | Retries transfer |

---

## Animation specification

| Animation | Spec |
|---|---|
| Progress value update | `animateFloatAsState(targetValue = progress, animationSpec = tween(150))` |
| State transition | `AnimatedContent` with `motion.statusCrossfade()` |
| Press feedback | Card scales to 0.98x with `springSnappySpec()` |

---

## Accessibility requirements

- **TalkBack Announcements**:
  - Idle/Downloaded: `contentDescription = "$fileName, $fileSize, file attachment. Double-tap to open."`
  - Transferring: `contentDescription = "Transferring $fileName, $percentPercent complete, $speedMbps megabytes per second."`
  - Failed: `contentDescription = "Failed transfer $fileName. Double-tap to retry."`

---

## Implementation notes

### New Files in `:ui:chat` (`com.transfer.flash.ui.chat`)
- `FlashFileMessageCard.kt` — composable containing `FlashFileMessageCard`, `FlashFileIconBadge`, and file extension resolver.

### Modified Files in `:core:messaging`
- `FlashMessagingModels.kt` — define `FlashFileAttachmentUi(name, sizeBytes, mimeType, extension, transferState)`.

### Modified Files in `:ui:chat`
- `FlashMessageBubble.kt` — render `FlashFileMessageCard` when message has file attachments.

---

## Testing checklist

- [ ] Compose preview — file cards for PDF, ZIP, APK, Code, Audio, and Video in Light & Dark modes
- [ ] Transfer progress preview — 0%, 45%, 100%, and Failed states
- [ ] Extension badge color mapping unit tests
- [ ] File size formatter unit tests (Bytes, KB, MB, GB)
- [ ] TalkBack accessibility label tests

---

## What makes this Flash?

Flash's file message card reflects the app's core mission: frictionless, blazingly fast peer-to-peer transfers. Vibrant color-coded extension badges, animated circular progress rings, and real-time LAN throughput readouts make transferring gigabyte-sized files feel tactile, transparent, and electric.
