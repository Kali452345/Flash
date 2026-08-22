# Flash Public API Specification

**Author:** Lead Android Software Architect & Migration Engineer  
**Date:** 2026-08-20  
**Version:** `1.0.0-draft`  
**Status:** SPECIFICATION

---

## 1. Design Rules & Stability Annotations

All public interfaces and data classes in Flash libraries must adhere to the following principles:

1. **Information Hiding:** No internal socket, stream, codec, or platform implementation classes are exposed.
2. **Immutability:** All public data models are strictly immutable (`val` properties only).
3. **Reactive State:** State is exposed via `kotlinx.coroutines.flow.StateFlow` and `Flow`.
4. **Result Monads:** Fallible operations return `FlashResult<T>` or throw well-typed `FlashException`.
5. **Stability Annotations:**
   - `@FlashInternalApi`: Public across Gradle modules for internal wiring, but forbidden for third-party consumers.
   - `@FlashExperimentalApi`: Public preview API subject to evolution before 1.0.0 final.

---

## 2. Core Common Module (`:core:common`)

### `FlashDevice`
- **Module:** `:core:common`
- **Stability:** Stable
- **Purpose:** Represents an identified peer device discovered on the network.
- **Definition:**
  ```kotlin
  data class FlashDevice(
      val id: FlashDeviceId,
      val friendlyName: String,
      val transportType: FlashTransportType,
      val presence: FlashPeerPresence = FlashPeerPresence.Online,
      val protocolVersion: Int = 1,
  )
  ```

### `FlashDeviceId`
- **Module:** `:core:common`
- **Stability:** Stable
- **Purpose:** Value class representing a unique device identifier (UUID or cryptographic public key hash).
- **Definition:**
  ```kotlin
  @JvmInline
  value class FlashDeviceId(val value: String)
  ```

### `FlashTransportType`
- **Module:** `:core:common`
- **Stability:** Stable
- **Purpose:** Identifies the active communication medium.
- **Definition:**
  ```kotlin
  enum class FlashTransportType {
      LAN,
      WIFI_DIRECT,
      WEBSOCKET,
      RELAY,
      MESH,
      UNKNOWN,
  }
  ```

### `FlashPeerPresence`
- **Module:** `:core:common`
- **Stability:** Stable
- **Purpose:** Live online/typing/offline status of a peer.
- **Definition:**
  ```kotlin
  enum class FlashPeerPresence {
      Online,
      Offline,
      Typing,
      Connecting,
  }
  ```

### `FlashResult<T>` & `FlashError`
- **Module:** `:core:common`
- **Stability:** Stable
- **Purpose:** Monadic result encapsulation for all fallible asynchronous platform operations.
- **Definition:**
  ```kotlin
  sealed interface FlashResult<out T> {
      data class Success<T>(val value: T) : FlashResult<T>
      data class Failure(val error: FlashError) : FlashResult<Nothing>
  }

  sealed interface FlashError {
      data class NetworkUnavailable(val message: String? = null) : FlashError
      data class PeerUnavailable(val deviceId: String, val message: String? = null) : FlashError
      data class ConnectionTimeout(val timeoutMs: Long) : FlashError
      data class ProtocolMismatch(val expected: Int, val actual: Int) : FlashError
      data class TransferFailed(val transferId: String, val reason: String) : FlashError
      data class VerificationFailed(val expectedHash: String, val actualHash: String) : FlashError
      data class StorageError(val message: String, val cause: Throwable? = null) : FlashError
      data class Cancelled(val reason: String? = null) : FlashError
  }
  ```

---

## 3. Core Discovery Module (`:core:discovery`)

### `FlashDiscovery`
- **Module:** `:core:discovery`
- **Stability:** Stable
- **Purpose:** Headless device discovery contract. Controls advertising and scanning over LAN mDNS/NSD and future Wi-Fi Direct.
- **Definition:**
  ```kotlin
  interface FlashDiscovery {
      val state: StateFlow<FlashDiscoveryState>
      val discoveredDevices: StateFlow<List<FlashDevice>>

      suspend fun startDiscovery(): FlashResult<Unit>
      suspend fun stopDiscovery(): FlashResult<Unit>
      suspend fun startAdvertising(listenPort: Int): FlashResult<Unit>
      suspend fun stopAdvertising(): FlashResult<Unit>
      suspend fun probeEndpoint(host: String, port: Int): FlashResult<FlashDevice>
  }

  data class FlashDiscoveryState(
      val isDiscovering: Boolean = false,
      val isAdvertising: Boolean = false,
      val advertisedPort: Int = 0,
      val statusMessage: String = "Idle",
  )
  ```
- **Lifecycle & Threading:** Suspend functions execute on `Dispatchers.IO`. Flow emissions are thread-safe and state-retaining.

---

## 4. Core Network Module (`:core:network`)

### `FlashNetwork`
- **Module:** `:core:network`
- **Stability:** Stable
- **Purpose:** High-level network engine for initiating, accepting, and managing peer communication sessions.
- **Definition:**
  ```kotlin
  interface FlashNetwork {
      val networkState: StateFlow<FlashNetworkState>
      val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>>

      suspend fun start(): FlashResult<Int>
      suspend fun stop(): FlashResult<Unit>
      suspend fun connect(device: FlashDevice): FlashResult<FlashSession>
      suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession>
      suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit>
  }

  data class FlashNetworkState(
      val isRunning: Boolean = false,
      val localPort: Int = 0,
      val localAddresses: List<String> = emptyList(),
      val activePeerCount: Int = 0,
  )
  ```

### `FlashSession`
- **Module:** `:core:network`
- **Stability:** Stable
- **Purpose:** Represents an active, authenticated, duplex connection with a specific peer device.
- **Definition:**
  ```kotlin
  interface FlashSession {
      val peer: FlashDevice
      val connectionState: StateFlow<FlashConnectionState>
      val transportType: FlashTransportType

      suspend fun send(message: ByteArray): FlashResult<Unit>
      suspend fun disconnect(reason: String = "Normal disconnect")
  }

  enum class FlashConnectionState {
      Connecting,
      Connected,
      Disconnecting,
      Disconnected,
      Failed,
  }
  ```

---

## 5. Core Transfer Module (`:core:transfer`)

### `FlashTransferRepository`
- **Module:** `:core:transfer`
- **Stability:** Stable
- **Purpose:** Orchestrates high-speed chunked file transfers, resume, checksum verification, and progress notifications.
- **Definition:**
  ```kotlin
  interface FlashTransferRepository {
      val activeTransfers: StateFlow<List<FlashTransfer>>

      suspend fun sendFile(
          targetDevice: FlashDevice,
          fileUri: String,
          displayName: String,
          fileSize: Long,
      ): FlashResult<FlashTransferId>

      suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit>
      suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit>
      suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit>
  }

  data class FlashTransfer(
      val id: FlashTransferId,
      val peerName: String,
      val fileName: String,
      val direction: FlashTransferDirection,
      val bytesDone: Long,
      val bytesTotal: Long,
      val state: FlashTransferState,
      val speedBytesPerSec: Long = 0L,
      val etaSeconds: Long = 0L,
      val errorMessage: String? = null,
  )

  @JvmInline
  value class FlashTransferId(val value: String)

  enum class FlashTransferDirection { Sending, Receiving }

  enum class FlashTransferState {
      Offered,
      Queued,
      Transferring,
      Paused,
      Verifying,
      Completed,
      Failed,
      Cancelled,
  }
  ```

---

## 6. Core Messaging Module (`:core:messaging`)

### `FlashChatRepository`
- **Module:** `:core:messaging`
- **Stability:** Stable
- **Purpose:** Provides conversation list, message history, message sending, and typing state. Backend-agnostic.
- **Definition:**
  ```kotlin
  interface FlashChatRepository {
      val conversations: StateFlow<List<FlashConversation>>
      val activeConversation: StateFlow<FlashConversationDetail?>

      fun openConversation(conversationId: FlashConversationId)
      fun closeConversation()
      suspend fun sendMessage(conversationId: FlashConversationId, text: String): FlashResult<FlashMessage>
      suspend fun archiveConversation(conversationId: FlashConversationId): FlashResult<Unit>
      fun setTyping(conversationId: FlashConversationId, isTyping: Boolean)
  }

  data class FlashMessage(
      val id: FlashMessageId,
      val conversationId: FlashConversationId,
      val senderId: FlashDeviceId,
      val senderName: String,
      val text: String,
      val timestamp: Long,
      val isMine: Boolean,
      val status: FlashMessageStatus = FlashMessageStatus.Sent,
      val attachments: List<FlashAttachment> = emptyList(),
  )

  @JvmInline
  value class FlashMessageId(val value: String)

  @JvmInline
  value class FlashConversationId(val value: String)

  enum class FlashMessageStatus {
      Pending,
      Sent,
      Delivered,
      Read,
      Failed,
  }
  ```

---

## 7. UI Theme Module (`:ui:theme`)

### Design System & Theme Components
- **Module:** `:ui:theme`
- **Stability:** Stable
- **Purpose:** Provides the official Flash Pulse visual identity, tokens, typography, shapes, elevation, spacing, motion, icons, and base surface primitives.
- **Key Types:**
  - `FlashTheme(darkTheme: Boolean, dynamicAccent: Boolean, content: @Composable () -> Unit)`
  - `FlashColors` (Pulse teal, graphite neutrals, layered void & surface0–3)
  - `FlashTypography` (Title, Headline, Body, Label)
  - `FlashShapes` (Concave pulse tail shape `FlashBubbleShape`, rounded tokens)
  - `FlashMotion` (Entrance springs, crossfades, reduce-motion probe)
  - `FlashIcons` (Registry for 35+ custom Flash vector drawables)
  - `FlashAvatar(initials: String, presence: FlashPeerPresence)`
  - `FlashSurface`, `FlashIcon`

---

## 8. UI Chat Module (`:ui:chat`)

### Standalone Chat Composables
- **Module:** `:ui:chat`
- **Stability:** Stable
- **Purpose:** Pluggable, high-performance messaging UI components driven by `FlashChatRepository`.
- **Key Composables:**
  - `FlashChatListScreen(state, onConversationClick, onLanClick, ...)`
  - `FlashConversationScreen(state, onBack, onSendText, onAttachmentClick)`
  - `FlashChatHeader(state, onBackClick, onActionClick)`
  - `FlashMessageList(messages, onMessageLongClick)`
  - `FlashMessageBubble(message, onBubbleClick)`
  - `FlashComposer(onSend, onAttach, onTyping)`
