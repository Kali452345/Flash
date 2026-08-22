# Flash Android App — Presentation Briefing

**Prepared for:** Tomorrow’s presentation  
**Prepared by:** Manus AI  
**Project reviewed:** `E:\Flash`  
**Review scope:** `logs/`, `docs/` (the repository contains `docs`, not a separate `ddocs` folder), the original blueprint, and the relevant Android/Kotlin source files.

> **Most important presentation rule:** Clearly separate what is implemented today from what is part of the long-term architecture. Flash currently contains a working modular Android showcase, a LAN discovery and persistent TCP-session path, and an experimental WebSocket file-transfer path. Several ambitious features—Wi-Fi Direct, TLS-secured production transfers, Room persistence, pause/resume, cryptographic file hashing, background transfers, and real network-backed chat—are planned or represented by interfaces, but are not yet complete in the current app.

## 1. The One-Sentence Explanation

**Flash is a native Android/Kotlin peer-to-peer communication and file-transfer application designed to move files directly between nearby devices over a local network, while keeping discovery, transport, transfer logic, and the Compose user interface separated into reusable libraries.** The long-term product supports both ordinary LAN/Wi-Fi and Wi-Fi Direct; the current runnable showcase demonstrates LAN discovery and connection probing plus an experimental WebSocket-based multi-peer file-transfer flow. [1] [2]

## 2. What the App Is Intended to Solve

The product is designed for direct device-to-device transfer without requiring an internet connection or a cloud server. In LAN mode, both phones are on the same Wi-Fi network, such as a home router, office network, or hotspot. In the future Wi-Fi Direct mode, the phones can form a direct peer-to-peer connection even when there is no suitable shared LAN. The central architectural principle is that the transfer engine should not need to know whether the peer was found through LAN discovery or Wi-Fi Direct. [2]

The project is therefore more than a single file-picker screen. It is being developed as a small platform: reusable headless networking and transfer libraries are separated from reusable Compose UI libraries, and the `:app` module acts as the runnable showcase and composition root. [1] [3]

## 3. Current Status: What You Can Safely Claim

The following table is the most important fact-checking section for the presentation.

| Area | Current verified state | Safe presentation wording |
|---|---|---|
| Android application | Implemented as a runnable showcase app in `:app` | “The app module wires the Flash libraries together and provides the demonstration screens.” |
| Modular architecture | Implemented across ten Gradle modules with one-way dependencies | “Flash is organized as publishable core and UI libraries rather than one monolithic module.” |
| LAN discovery | Implemented with Android NSD/mDNS and `_flash-transfer._tcp.` service advertisement | “Devices can advertise and discover Flash endpoints on the same LAN.” |
| LAN connection | Implemented as a TCP reachability probe followed by a persistent session and heartbeat | “The LAN MVP verifies that the advertised endpoint is reachable and maintains a live session.” |
| LAN file transfer | **Not implemented in the current LAN protocol path** | Do not say that the main LAN path already transfers files. |
| WebSocket discovery | Implemented experimentally with a separate `_flashws._tcp.` service | “The experimental transfer track uses a separate WebSocket service.” |
| WebSocket file transfer | Implemented experimentally | “The side track can send and receive files between paired peers using ordered WebSocket frames.” |
| Multi-peer mesh | Implemented in code; three-device physical validation is still pending | “The design supports a full mesh in which each device acts as both server and client.” |
| Received-file handling | Implemented for the WebSocket track | Received files are written to the app’s internal `filesDir/ws-received/` directory with duplicate-name protection. |
| Open, export, and share | Implemented for completed WebSocket transfers | “Received files can be opened, exported through the Storage Access Framework, or shared through Android intents.” |
| Chat interface | Polished Compose UI implemented | “The chat interface demonstrates the Flash design system and interactions.” |
| Real network-backed chat | **Not implemented** | The current chat repository is `SampleFlashChatRepository`; messages are local in-memory demo state. |
| TLS encryption | **Not implemented in the current WebSocket transfer path** | Current experimental transfers use cleartext `ws://` on a trusted LAN. |
| Wi-Fi Direct | Architectural target, not current runnable functionality | Say “planned” or “future transport,” not “currently supported.” |
| Room/SQLite persistence | Architectural target, not currently configured as the active database | Current identity and trust data use `SharedPreferences`; received files use app-internal storage. |
| Pause/resume/recovery | API and roadmap concepts exist, but the current WebSocket transfer does not implement resume | Do not claim interrupted transfers can resume. |
| Cryptographic file hashing | Planned; current WebSocket path checks byte count only | Do not claim hash-based integrity verification is already active. |
| Background transfers | Planned foreground-service design; not currently implemented | Do not claim reliable background transfer support yet. |
| Testing | Full build and unit-test suite passed on 2026-08-20 | “The codebase has automated unit coverage and a successful multi-module build; physical network experiments are still incomplete.” |

The progress log records **62 passing library unit tests** across the modular migration quality gate and a successful `testDebugUnitTest assembleDebug` run. The latest handoff records **357 Gradle tasks** passing on 2026-08-20. However, the experiment log currently says that no physical-device networking experiments have been recorded, and the WebSocket milestone specifically says that three-device mesh pairing and a real file send between phones still need on-device verification. [4] [5] [6]

## 4. Architecture at a Glance

The architecture is a library-first design. The application module is the host and composition root. UI modules depend on domain repositories and models; core modules contain the networking, discovery, identity, protocol, and transfer contracts without Compose dependencies.

```text
                         :app
              Showcase application / composition root
                              |
        ------------------------------------------------
        |                     |                        |
     :ui:chat            :ui:transfer              :core:* APIs
  Chat list, bubbles,   Peer picker, transfer      Networking, discovery,
  composer, reactions  screen, progress cards     transfer, messaging
        |                     |                        |
     :ui:theme          Flash domain models      -------------------------
  Colors, typography,   and repository contracts  |          |            |
  motion, icons, shapes                         NSD      TCP / WS      Security
                                                   |          |            |
                                              Local LAN    Sockets   Identity/trust
```

The documented dependency direction is intentionally one-way. The core libraries are headless and reusable, while the UI libraries do not reach directly into sockets or Android discovery managers. Instead, UI code is intended to consume repository abstractions such as `FlashChatRepository` and `FlashTransferRepository`. This reduces accidental coupling and makes it possible to reuse the networking engine without importing Compose. [1] [3]

### Module Responsibilities

| Module | Main responsibility | Current presentation description |
|---|---|---|
| `:app` | Runnable APK and composition root | Starts the top-level Compose app, creates controllers/repositories, and routes between screens. |
| `:core:common` | Shared models, result types, protocol framing, annotations | Defines common concepts such as devices, transport types, errors, and escaped text fields. |
| `:core:security` | Device identity and trust-store abstractions | Currently provides Android `SharedPreferences` implementations for device identity and trusted-peer records. |
| `:core:discovery` | Discovery abstraction and Android NSD implementation | Wraps `NsdManager`, service registration, service resolution, multicast locking, and stale-callback protection. |
| `:core:network` | Network/session contracts and socket engines | Contains the persistent LAN TCP session and the experimental RFC 6455 WebSocket implementation. |
| `:core:transfer` | Transfer models, repository contract, and WebSocket control-message protocol | Defines transfer states and the `FLASH_FILE_*` message serialization used by the experimental path; the general production transfer engine is not complete. |
| `:core:messaging` | Chat repository contract, message models, and grouping utilities | Provides the backend boundary for chat and currently includes a sample in-memory repository. |
| `:ui:theme` | Flash Pulse design system | Provides colors, typography, spacing, dimensions, motion, icons, avatars, and custom bubble geometry. |
| `:ui:chat` | Chat UI components | Provides the inbox, conversation screen, header, message list, bubbles, composer, reactions, replies, selection mode, and file-message cards. |
| `:ui:transfer` | Transfer UI components | Provides the experimental WebSocket transfer screen, peer rows, progress cards, and Open/Export/Share actions. |

Each library is configured as an Android library with Maven publishing support and the group `com.transfer.flash`; the progress log records the migration as complete and reports zero circular dependencies. [4]

## 5. Technology Stack

| Technology | Version or implementation | Why it is used |
|---|---|---|
| Android | Native Android application, minimum SDK 24 | Provides access to local networking, NSD, storage intents, lifecycle, and device APIs. |
| Language | Kotlin `2.2.10` | Main implementation language for Android, domain models, networking, and Compose UI. |
| Build system | Gradle with Android Gradle Plugin `9.3.1` | Builds the application and independent Android library modules. |
| Compile/target SDK | Compile SDK `37`; target SDK `36` | SDK 37 is used to compile against current APIs, while target SDK 36 is intentional for the current LAN MVP permission behavior. |
| Java compatibility | Java 11 source and target compatibility | Required by the Android modules’ build configuration. |
| UI toolkit | Jetpack Compose with Compose BOM `2025.12.00` | Enables declarative, state-driven Android UI. |
| Material layer | Material 3 and adaptive navigation suite | Provides infrastructure components such as buttons, cards, scaffolds, and navigation support. The visible brand styling is Flash-owned. |
| Reactive state | Kotlin Coroutines and `StateFlow` | Controllers and repositories publish state that Compose collects and renders. |
| AndroidX | Activity Compose `1.8.0`, Lifecycle Runtime KTX `2.6.1`, Core KTX `1.10.1` | Integrates Compose with activity/lifecycle behavior and Android Kotlin extensions. |
| LAN discovery | `NsdManager`, `NsdServiceInfo`, DNS-SD/mDNS | Advertises and resolves Flash services on the local network. |
| LAN transport | Java/Kotlin TCP sockets | Provides the current persistent LAN session and the socket substrate for the WebSocket path. |
| WebSocket transport | Hand-rolled minimal RFC 6455 codec | Avoids adding a new dependency and supports both client and server behavior on every device. |
| File selection | Storage Access Framework `ACTION_OPEN_DOCUMENT` | Lets the user choose files through Android’s system picker without assuming raw filesystem access. |
| File sharing | `FileProvider` and Android intents | Provides safe content URIs for opening and sharing received files. |
| Local persistence | Android `SharedPreferences` and app-internal files | Persists identity/trust records and stores received WebSocket files. Room is planned, not active in the current implementation. |
| Testing | JUnit, AndroidX test, Espresso, Compose UI test dependencies | Supports unit and Android UI testing. |
| Dependency injection | Constructor injection and composition-root wiring | The project deliberately avoids Dagger, Hilt, and Koin in the core architecture. |

The exact app configuration is in `app/build.gradle.kts` and the central version catalog is in `gradle/libs.versions.toml`. [7] [8]

## 6. How the Current LAN Flow Works

### Step 1: The user starts LAN mode

`MainActivity` creates a `LanController`, collects its `LanUiState`, and exposes the LAN home screen. When the user taps **Start LAN**, the controller starts a `LanProbeServer`, starts LAN discovery, enumerates local IPv4 addresses, and publishes the selected listening port and status to the UI. [9] [10]

The probe server prefers TCP port **45821**. If that port is occupied, it falls back to a dynamic port and advertises the actual selected port. The stable preferred port was introduced to reduce failures caused by stale NSD records pointing to an old random port. [11]

### Step 2: The device advertises itself

The discovery implementation uses Android `NsdManager` with the LAN service type:

```text
_flash-transfer._tcp.
```

The service advertises the device ID, friendly name, protocol version, and capabilities as DNS-SD attributes. The implementation also uses a Wi-Fi multicast lock while discovery or advertising is active, serializes service resolution through `NsdResolveQueue`, and uses a generation counter to ignore callbacks from an old discovery session after the user stops or restarts LAN mode. [12]

### Step 3: A nearby device is resolved

When NSD reports a service, the app resolves it to a host address and port, converts it to a Flash endpoint/device model, ignores the local device’s own advertisement, and updates the list of nearby devices. The app supports both automatic discovery and manual IP/port entry. [12] [13]

### Step 4: The app connects using the LAN network

The connection probe uses Android `ConnectivityManager` to select a Wi-Fi/Ethernet `Network` and creates the socket through that network’s `socketFactory`. This is important because a phone may have mobile data, VPN, hotspot, and Wi-Fi active at the same time; a normal unbound socket can choose the wrong local interface. The project logs document that this routing issue caused timeouts during early testing. [14]

### Step 5: The peers exchange a hello message

The TCP session uses a simple versioned line protocol. The client sends:

```text
FLASH_HELLO version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
```

The server responds with:

```text
FLASH_OK version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
```

The shared `FlashTextFraming` utility escapes `%`, spaces, and `=` so names and IDs cannot break the field format. After the handshake, the app maps the peer into a `FlashDevice` and reports a connected state. [15] [16]

### Step 6: The session remains alive

The current `LanSession` starts an I/O read loop and a heartbeat loop on `Dispatchers.IO`. Every three seconds it sends `FLASH_PING`; the peer answers with `FLASH_PONG`. A disconnect message or socket failure closes the session and updates the UI state. [16]

> **Critical limitation:** The current LAN protocol document explicitly says that this initial LAN milestone does **not** transfer files. It verifies discovery, endpoint reachability, protocol compatibility, session persistence, and heartbeat behavior. The production file-transfer protocol is planned to run over a secure transport later. [11]

## 7. How the Experimental WebSocket Transfer Flow Works

The WebSocket track is intentionally separate from the main LAN/TCP protocol. It was added as an owner-requested side track and should be described as **experimental**, not as the finished production architecture. [17]

### Device roles and discovery

Every device starts a WebSocket server and can also create outbound WebSocket client connections. The server prefers port **45822**, with dynamic fallback if necessary. WebSocket-capable devices advertise through a separate NSD service type:

```text
_flashws._tcp.
```

The `WsTransferManager` starts the server and WebSocket discovery when it is created. The transfer screen can also show the device’s local IP/port, discovered devices, manual connection controls, paired peers, and transfer history. [17] [18]

### RFC 6455 upgrade and peer hello

The client performs the standard WebSocket HTTP upgrade using `Sec-WebSocket-Key`, `Sec-WebSocket-Accept`, and protocol version 13. After the upgrade, each side sends:

```text
FLASH_WS_HELLO version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
```

The codec masks client-to-server frames, leaves server-to-client frames unmasked as required by RFC 6455, supports text and binary frames, handles ping/pong/close control frames, and reassembles fragmented incoming messages. It intentionally does not implement extensions or TLS. [19]

### Multi-peer behavior

Peers are keyed by their stable device ID. If both directions of a connection exist, the outbound connection is preferred for sending and the inbound connection remains as a fallback. This allows a three-device full mesh: each phone can run a server, connect to the other phones as a client, and maintain peer entries for each connection.

A successful hello exchange is treated as the current experimental pairing event. The peer ID and friendly name are stored in the trust store, which currently uses Android `SharedPreferences`. If a previously paired device is rediscovered, the manager can attempt to reconnect automatically. This is a remembered relationship, not yet cryptographic authentication. [20] [21]

### File send sequence

The sender uses Android’s `ACTION_OPEN_DOCUMENT` picker. The selected content URI is queried for a display name and, when available, a file size. The manager generates a short transfer ID and sends this sequence on one WebSocket connection:

```text
FLASH_FILE_START version=1 transferId=<id> name=<escaped-file-name> size=<bytes-or--1>
<raw binary file bytes, sent in 64 KiB frames>
FLASH_FILE_END version=1 transferId=<id> bytes=<bytes-sent>
```

The implementation permits one active transfer per connection to preserve a simple ordered-stream model. The sender reads the selected URI through Android’s `ContentResolver`, sends 64 KiB chunks, updates a reactive progress item, and waits for the receiver’s acknowledgment. [17] [22]

### File receive sequence

The receiver creates a destination in:

```text
<app internal filesDir>/ws-received/
```

It sanitizes path separators, creates the directory when necessary, and adds `(1)`, `(2)`, and so on if a file with the same name already exists. It writes the binary frames to disk, counts the received bytes, and compares the count with the expected size. It then sends:

```text
FLASH_FILE_ACK version=1 transferId=<id> received=<bytes-received> ok=<true|false>
```

A successful count match marks the transfer complete. The implementation does not currently calculate a cryptographic hash. [17] [22]

### Opening, exporting, and sharing a received file

Completed transfers are restored into the transfer list by scanning `ws-received/` when the manager starts. The transfer UI marks them **READY** and offers three actions:

| Action | Android mechanism |
|---|---|
| Open | Resolves the local file, determines a MIME type, and launches a content URI through an `ACTION_VIEW` intent. |
| Export | Uses `ActivityResultContracts.CreateDocument` and streams the file to the user-selected destination through the Storage Access Framework. |
| Share | Uses an `ACTION_SEND` intent with a content URI and `ClipData` permissions. |

The app declares a non-exported AndroidX `FileProvider` with temporary URI permissions so other applications can open or receive the file safely. [5] [23]

## 8. How the Chat UI Works Today

The chat interface is a separate Compose product surface. The `:ui:chat` module includes the chat-list screen, conversation screen, header, reverse-layout message list, custom message bubbles, adaptive composer, attachment sheet, replies, reactions, selection mode, delivery-status glyphs, typing indicators, and file-message cards. The visual system is called **Flash Pulse** and uses custom teal/graphite design tokens, custom vector icons, motion specifications, and a Flash-owned concave bubble shape rather than a generic rounded rectangle. [3] [4]

The current showcase wires the UI to `SampleFlashChatRepository`. This repository holds sample conversations in `MutableStateFlow`, opens conversations from an in-memory map, appends outgoing text locally, updates the preview row, and supports demo actions such as archive and selection. The current `sendText` method does not send a message over LAN or WebSocket. The attachment picker method is intentionally a placeholder because the transfer layer is expected to own file selection. [24]

Therefore, the correct description is:

> **The chat UI is implemented and interactive, but its current data source is a sample in-memory repository rather than a real peer-to-peer messaging backend.**

This is not a contradiction in the architecture. The UI is deliberately written against the `FlashChatRepository` abstraction so a real network-backed implementation can replace the sample repository later without rewriting the Compose screens. [3] [24]

## 9. Identity, Persistence, Permissions, and Security

### Device identity

On first use, the security module generates a UUID and stores it in `SharedPreferences` under the Flash identity preferences. It also stores a friendly name, defaulting to a value based on the Android device model, such as `Flash Pixel`. The app-level `AppIdentity` is a compatibility wrapper over this modular identity store. [25] [26]

### Current persistence

The current implementation uses two simple persistence mechanisms:

| Data | Current storage |
|---|---|
| Local device ID and friendly name | Android `SharedPreferences` in `flash_identity`. |
| Remembered WebSocket peer relationships | Android `SharedPreferences` in `flash_ws_pairing`, keyed by `paired_<deviceId>`. |
| Received WebSocket files | App-internal `filesDir/ws-received/`. |
| Current chat messages and UI state | In-memory sample repository. |
| Production transfer history, chunks, resume state, and message database | Not implemented; Room/SQLite is part of the planned architecture. |

### Current Android permissions

The manifest currently declares `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, and `CHANGE_WIFI_MULTICAST_STATE`. It does not declare `ACCESS_LOCAL_NETWORK`. The project compiles against SDK 37 but targets SDK 36 because early SDK 37 testing exposed the newer Android local-network permission flow before the application had implemented the corresponding runtime/system-mediated UX. The repository documents that this decision must be revisited before a release targeting SDK 37 or higher. [7] [27]

### Current encryption status

The long-term blueprint specifies TCP over TLS, but the current experimental WebSocket track deliberately uses cleartext `ws://` on a trusted LAN. The project documentation lists the missing security features explicitly: no TLS, no trust-verification UX, no resume, no hash verification, and no app-level heartbeat on the WebSocket path. The current hello exchange identifies peers and stores a remembered pairing record, but it does not prove cryptographic identity. [11] [17] [19]

The honest answer to “Is the current transfer encrypted?” is therefore:

> **Not yet on the experimental WebSocket path. The production design calls for TLS, but the current side track is a trusted-LAN prototype using cleartext WebSockets.**

## 10. What Is Planned but Not Yet Complete

The original blueprint and target architecture describe a larger production system. These items should be presented as the roadmap rather than current features:

| Planned capability | Intended implementation |
|---|---|
| Wi-Fi Direct fallback | `WifiP2pManager`, Wi-Fi P2P service discovery, group negotiation, and a transport adapter behind the same abstractions. |
| Secure transfer | TLS sockets and a trust/pairing experience with real verification. |
| Unified production transfer protocol | `HELLO`, pairing, transfer request/accept, file start, chunk acknowledgments, completion, cancel, pause, resume, and disconnect messages. |
| Large-file reliability | Chunk tracking, reassembly, retry, resume after interruption, and transfer recovery. |
| Integrity verification | SHA-256 or BLAKE3-style hashing in the planned transfer engine; current WebSocket code only checks byte counts. |
| Persistent state | Room/SQLite entities for transfers, files, chunks, trusted devices, and messages. |
| Background transfers | A `dataSync` foreground service designed around modern Android restrictions. |
| Automatic transport selection | Prefer LAN when available and fall back to Wi-Fi Direct or another future transport according to policy and link quality. |
| Real P2P chat | A network-backed implementation of `FlashChatRepository` replacing the sample repository. |

The transfer repository interface already exposes concepts such as `pauseTransfer`, `resumeTransfer`, and `cancelTransfer`, and the transfer state model includes `Paused`, `Verifying`, `Completed`, `Failed`, and `Cancelled`. These are **domain/API preparation**, not proof that the current runnable transfer path already implements all of those behaviors. [28] [29]

## 11. Testing and Reliability Facts

The project has meaningful automated verification, but it is important to describe the scope accurately.

The modular migration quality gate reports passing unit tests in the core and UI libraries, including tests for text framing, LAN probe messages, WebSocket RFC 6455 behavior, transfer message parsing, message grouping, theme tokens, delivery states, file-card logic, and transfer models. The latest logged build command was:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon --no-configuration-cache
```

The build completed successfully on 2026-08-20. The logs report 62 library unit tests passing during the migration quality gate and, in the latest handoff, 357 Gradle tasks passing. [4] [5]

The repository also records several resolved engineering problems:

| Problem | Resolution |
|---|---|
| NSD resolver stack overflow | Renamed a shadowing callback parameter so the resolver calls the intended callback instead of recursively calling itself. |
| Wrong network interface for LAN socket | Bound the socket through the selected Wi-Fi/Ethernet `Network.socketFactory`. |
| Stale random LAN port after restart | Prefer stable port 45821 and advertise the actual fallback port if needed. |
| NSD callbacks arriving after stop | Added serialized resolution and generation-based stale-callback protection. |
| Compose system-bar overlap | Added status-bar, navigation-bar, and IME insets to the relevant Compose surfaces. |

Some networking problems remain marked **OPEN** in the error log because they were not physically retested after the code fix. The project’s `experiments.md` currently says that no physical-device networking experiments have been recorded. Therefore, avoid claiming measured throughput, universal device compatibility, or production-grade reliability. [6] [30]

## 12. Likely Questions and Strong Answers

### What is Flash in simple terms?

Flash is a local, peer-to-peer Android communication and file-transfer platform. Its goal is to let nearby devices discover one another and exchange files directly, without depending on a cloud server. The current showcase demonstrates the architecture and two networking stages: a LAN connection/session MVP and an experimental WebSocket file-transfer path.

### Does it require the internet?

The intended design does not require internet access. LAN mode requires both devices to be reachable on the same local network. Wi-Fi Direct is planned for cases where there is no suitable shared LAN. In the current implementation, the Android manifest requests network access but the application is not designed around a cloud backend.

### How do two devices find each other?

They use Android Network Service Discovery, or NSD, which is Android’s DNS-SD/mDNS service-discovery mechanism. One device advertises a Flash service with its device ID, name, protocol version, capabilities, port, and service type. The other device discovers and resolves that service to an IP address and port.

### Why use both TCP and WebSockets?

They serve different development milestones. The main LAN path uses a small TCP protocol to prove discovery, reachability, persistent sessions, and heartbeats before introducing full transfer complexity. The WebSocket path is a separate experimental side track that adds actual file transfer and multi-peer behavior. It should not be described as the final unified protocol.

### Is the LAN path already transferring files?

No. The current LAN path is a persistent connection and reachability probe. File transfer exists in the experimental WebSocket path, not in the current main LAN/TCP protocol.

### Is the file transfer encrypted?

The current WebSocket side track is cleartext `ws://` and is intended only for a trusted LAN prototype. TLS is part of the production design but is not currently active in that path.

### Is pairing secure?

The current pairing mechanism remembers a peer’s device ID and friendly name in `SharedPreferences` after the hello exchange. That is useful for recognition and automatic reconnect, but it is not cryptographic authentication. A future production pairing flow must add trust verification and secure key/session handling.

### Does Wi-Fi Direct work today?

Not in the current runnable implementation. Wi-Fi Direct is a planned transport and the architecture is designed so it can implement the discovery and transport abstractions without changing the chat or transfer UI.

### Does the chat screen send messages to another phone?

Not yet. The UI is implemented and interactive, but the current `SampleFlashChatRepository` stores sample conversations and locally appends demo messages. A real network-backed chat repository is a future integration point.

### Where are received files stored?

For the experimental WebSocket path, received files are stored in the app’s internal `filesDir/ws-received/` folder. The app can then open, export, or share them. File names are deduplicated if the same name already exists.

### How does the receiver know the file is complete?

The sender transmits a `FLASH_FILE_START` message, ordered binary data frames, and a `FLASH_FILE_END` message. The receiver counts the bytes it wrote and compares that count with the expected file size. It sends `FLASH_FILE_ACK` with `ok=true` only when the count matches. Hash verification is planned but is not currently performed.

### Can a transfer resume after a connection failure?

Not in the current WebSocket implementation. The current code marks an interrupted transfer as failed. Resume and chunk-level recovery exist in the planned production protocol and public transfer model, but they are not finished in the runnable prototype.

### Can one device send to several devices?

The experimental WebSocket screen supports sending to one paired peer or to all connected peers. Every device runs a server and can open client connections, which is why a three-device full mesh is possible in the design. The repository still lists real three-device physical testing as pending.

### Why is the project divided into so many modules?

The separation enforces boundaries. A developer who only needs a headless transfer engine should not have to import Compose. A developer who only wants the Flash chat UI should not be forced to use low-level sockets. Independent modules are also easier to test, publish, replace, and extend to future transports.

### Why avoid Hilt, Dagger, or Koin?

The architecture uses constructor injection and a small composition root instead of a heavy DI runtime. This keeps the libraries easier to embed in third-party applications, reduces transitive dependencies, and keeps build complexity lower.

### What Android versions are supported?

The app’s configured minimum SDK is 24. It compiles against SDK 37 and currently targets SDK 36. The target SDK choice is deliberate: the project postponed the newer SDK 37 local-network permission flow while the LAN MVP is being stabilized.

### What is the biggest current limitation?

The biggest limitation is the gap between the ambitious production blueprint and the currently verified implementation. The architecture and contracts are in place, but the main LAN path does not yet transfer files, the WebSocket path is experimental and not physically validated in the repository logs, and the polished chat UI is still backed by sample data.

### Is it production-ready?

The honest answer is **not yet**. It is a substantial, modular prototype/showcase with automated tests and several working local-network behaviors. Production readiness still requires device-matrix testing, TLS, cryptographic pairing, hash verification, resume/recovery, persistence, background execution, Wi-Fi Direct, and a real network-backed messaging layer.

## 13. A Short Presentation Script

> Flash is a native Android application written in Kotlin with Jetpack Compose. Its purpose is direct peer-to-peer communication and file transfer between nearby devices without relying on the internet or cloud storage. The project is organized as reusable libraries: discovery, networking, transfer, security, messaging, and UI are separate modules, while the app module is mainly the showcase and composition root.
>
> In the current LAN MVP, a phone advertises a Flash service using Android NSD/mDNS. Another phone discovers the service, resolves its IP address and port, and opens a TCP connection. The devices exchange versioned hello messages and maintain the session with ping/pong heartbeats. That LAN path currently proves discovery and connectivity; it does not yet perform full file transfer.
>
> The project also contains an experimental WebSocket transfer track. Every device runs both a WebSocket server and client, so multiple phones can form a mesh. After a hello exchange, a selected file is sent as a start message, 64 KiB binary frames, and an end message. The receiver saves it into internal app storage, verifies the byte count, sends an acknowledgment, and exposes Open, Export, and Share actions.
>
> The user interface is a custom Flash Pulse Compose design system with a chat list, conversation screen, custom bubbles, reactions, replies, typing indicators, delivery states, and file cards. At the moment, the chat UI uses an in-memory sample repository, so the real network-backed chat engine is still future work. The major next steps are TLS, secure pairing, Wi-Fi Direct, persistent transfer state, pause/resume, hash verification, background transfer support, and physical-device validation.

## 14. Words to Use and Words to Avoid

| Prefer saying | Avoid saying unless the feature is completed later |
|---|---|
| “LAN discovery and persistent TCP-session MVP” | “Complete LAN file transfer” |
| “Experimental WebSocket transfer track” | “The final production protocol” |
| “Supports a multi-peer mesh in code” | “Three-device mesh is fully validated” |
| “Byte-count verification” | “Cryptographic integrity verification” |
| “Remembered pairing record” | “Secure cryptographic pairing” |
| “Sample in-memory chat repository” | “Real-time P2P chat” |
| “Wi-Fi Direct-ready architecture” | “Wi-Fi Direct currently works” |
| “Planned TLS security” | “Encrypted transfer” |
| “Successful automated build and unit tests” | “Fully device-tested and production-ready” |

## 15. Final Takeaway

Flash’s strongest presentation story is its **architecture and honest incremental development**. The project has separated reusable Android libraries from the showcase app, implemented a real NSD-based LAN discovery/session foundation, built an experimental multi-peer WebSocket transfer path, and created a sophisticated Compose UI. The correct technical narrative is not that every long-term feature is finished; it is that the project has established the foundations needed to add secure, resumable, transport-independent file transfer and real P2P messaging without rewriting the UI or the entire platform.

## References

[1]: `docs/architecture.md` — Modular Library Architecture and architectural invariants.

[2]: `Project Goal and Blueprint/android-lan-wifi-direct-transfer-app-plan.md` — Original product goal, transport strategy, and technical specification.

[3]: `docs/architecture/target-architecture.md` — Target module topology, dependency graph, module registry, and transport abstraction.

[4]: `logs/progress.md` — Date-stamped implementation history, modular migration, UI work, WebSocket work, and build verification.

[5]: `logs/handoff.md` — Latest project handoff, current state, implemented features, and latest successful build.

[6]: `logs/experiments.md` — Current physical-device experiment status.

[7]: `app/build.gradle.kts` — Application SDK configuration and module dependencies.

[8]: `gradle/libs.versions.toml` — Exact Android Gradle Plugin, Kotlin, Compose, AndroidX, JUnit, and Espresso versions.

[9]: `app/src/main/java/com/transfer/flash/MainActivity.kt` — Showcase navigation, LAN home screen, chat screen, and WebSocket screen wiring.

[10]: `app/src/main/java/com/transfer/flash/lan/LanController.kt` — LAN lifecycle, discovery callbacks, manual connection, TCP sessions, and UI state.

[11]: `docs/protocol.md` — Current LAN session protocol and experimental WebSocket transfer protocol.

[12]: `core/discovery/src/main/java/com/transfer/flash/core/discovery/nsd/NsdFlashDiscovery.kt` — NSD/mDNS advertisement, discovery, resolution, service types, multicast locks, and stale-callback protection.

[13]: `app/src/main/java/com/transfer/flash/discovery/LanDiscovery.kt` — Application adapter from the modular discovery library to the LAN showcase flow.

[14]: `logs/errors.md` — LAN network-routing issue, stable-port issue, and their recorded fixes/statuses.

[15]: `core/common/src/main/java/com/transfer/flash/core/common/protocol/FlashTextFraming.kt` — Shared protocol field encoding and escaping.

[16]: `core/network/src/main/java/com/transfer/flash/core/network/tcp/LanSession.kt` — Persistent TCP session, hello-derived peer model, heartbeat, and disconnection behavior.

[17]: `app/src/main/java/com/transfer/flash/wstransfer/WsTransferManager.kt` — Experimental WebSocket multi-peer manager, pairing, file send/receive, storage, acknowledgment, and progress.

[18]: `ui/transfer/src/main/java/com/transfer/flash/ui/transfer/WsTransferScreen.kt` — Experimental transfer UI, file picker, peer actions, transfer history, and Open/Export/Share controls.

[19]: `core/network/src/main/java/com/transfer/flash/core/network/ws/WebSocketCodec.kt` — Minimal RFC 6455 implementation and explicit scope limitations.

[20]: `app/src/main/java/com/transfer/flash/wstransfer/WsPairingStore.kt` — Experimental pairing adapter.

[21]: `core/security/src/main/java/com/transfer/flash/core/security/trust/AndroidPreferencesTrustStore.kt` — Current persisted peer-trust implementation.

[22]: `core/transfer/src/main/java/com/transfer/flash/core/transfer/protocol/WsTransferMessages.kt` — WebSocket control-message encoding and parsing.

[23]: `logs/progress.md` — FileProvider, received-file resolution, SAF export, MIME mapping, and sharing milestone.

[24]: `core/messaging/src/main/java/com/transfer/flash/core/messaging/FlashChatRepository.kt` — Chat repository contract and current in-memory sample implementation.

[25]: `app/src/main/java/com/transfer/flash/identity/AppIdentity.kt` — Application identity adapter.

[26]: `core/security/src/main/java/com/transfer/flash/core/security/identity/AndroidPreferencesIdentityStore.kt` — UUID and friendly-name persistence.

[27]: `docs/android-platform-notes.md` — SDK 36/37 local-network permission decision and NSD platform notes.

[28]: `core/transfer/src/main/java/com/transfer/flash/core/transfer/FlashTransferRepository.kt` — Public transfer API including pause, resume, and cancel contracts.

[29]: `core/transfer/src/main/java/com/transfer/flash/core/transfer/model/FlashTransfer.kt` — Transfer states such as Paused, Verifying, Completed, Failed, and Cancelled.

[30]: `logs/errors.md` — Resolved and open errors, including physical retest status.

[31]: https://developer.android.com/reference/android/net/nsd/NsdManager — Official Android NSD reference.

[32]: https://developer.android.com/develop/connectivity/wifi/wifi-direct — Official Android Wi-Fi Direct documentation.

[33]: https://developer.android.com/guide/components/intents-common — Official Android common intents and document-picker guidance.

[34]: https://developer.android.com/develop/background-work/services/fgs/service-types — Official Android foreground-service type guidance.
