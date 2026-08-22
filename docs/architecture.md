# Architecture

## Modular Library Architecture (ADR-008)

Flash is architected as a suite of decoupled, standalone Android/Kotlin libraries under `com.transfer.flash:*`, with `:app` serving as the showcase application. This enables third parties and separate apps to use the core networking/transfer engines and custom UI components independently or together.

Detailed plan: [`docs/architecture-modular-libraries-plan.md`](architecture-modular-libraries-plan.md)

### Layered Topology

```text
Host App:
  :app (Showcase / Demo)

UI Component Libraries:
  :ui:chat      (Bubbles, Composer, List, Header, Action Sheets)
  :ui:transfer  (Peer Picker, Transfer Sheets, Progress Cards)
  :ui:theme     (FlashTheme, Design Tokens, Motion, Custom Icons, Avatars)

Core Engine Libraries (Headless / Zero-UI):
  :core:transfer   (SAF File Streaming, Chunking, Reassembly, Checksums)
  :core:network    (Persistent TCP Sessions, RFC 6455 WebSockets, Mesh Routing)
  :core:discovery  (Auto NSD / mDNS, Manual IP Probing, Wi-Fi Direct Abstraction)
  :core:common     (Identity, Shared Models, Framing Protocols, Annotations)
```

## Architectural Invariants

1. **Headless Core:** `:core:*` has zero Jetpack Compose or UI dependencies.
2. **Backend-Agnostic UI:** `:ui:chat` depends on repository abstractions (`FlashChatRepository`), not low-level sockets.
3. **Standalone Publishability:** Every library module configures `maven-publish` to generate AARs, POMs, sources, and docs.
4. **Transport Abstraction:** Transfer engines operate over an abstract connection layer regardless of whether the transport is LAN TCP, WebSocket mesh, or Wi-Fi Direct.
