# Android LAN + Wi-Fi Direct File Transfer App

## Project Plan and Technical Specification

**Development environment:** Android Studio  
**Language:** Kotlin  
**UI:** Jetpack Compose  
**Primary transports:** LAN/Wi-Fi + Wi-Fi Direct (P2P)  
**Initial transfer protocol:** TCP over an encrypted TLS channel  
**Persistence:** Room/SQLite  
**Background transfers:** Foreground Service, with current Android restrictions handled explicitly  

---

## 1. Project Goal

Build a native Android file-transfer application that can move files directly between nearby Android devices without depending on the internet or a cloud server.

The application will support two transport paths:

1. **LAN/Wi-Fi:** both devices are connected to the same local network, such as a home/office router, another phone's hotspot, or another compatible local network.
2. **Wi-Fi Direct:** devices establish a Wi-Fi P2P connection directly when there is no suitable shared LAN.

The most important architectural rule is:

> **Discovery and network transport must be replaceable without rewriting the file-transfer engine.**

Therefore, LAN and Wi-Fi Direct will ultimately feed the same transfer protocol and transfer engine.

---

## 2. What Was Verified Against Current Android Documentation

This specification was checked against current Android Developers documentation during August 2026.

### 2.1 Kotlin + Android Studio + Jetpack Compose

Android officially recommends Kotlin for Android development, and current Android Studio provides first-class Kotlin and Jetpack Compose support. Android Studio's Compose project template creates a Kotlin-based Compose application directly.

Sources:

- [Kotlin and Android — Android Developers](https://developer.android.com/kotlin)
- [Jetpack Compose setup — Android Developers](https://developer.android.com/develop/ui/compose/setup)

### 2.2 LAN discovery

Android provides Network Service Discovery through `NsdManager` and `NsdServiceInfo`. This is appropriate for discovering application services on a local IP network. We should advertise a dedicated service such as `_xfer._tcp` and resolve its hostname/port before connecting.

Android's newer APIs can also associate NSD discovery with a particular `Network`, which is useful when a device has multiple network interfaces.

Sources:

- [NsdServiceInfo — Android Developers](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo)
- [NsdManager / network service discovery — Android Developers](https://developer.android.com/reference/android/net/nsd/NsdManager)
- [Network connectivity state — Android Developers](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)

### 2.3 Wi-Fi Direct

Android's `WifiP2pManager` supports peer discovery and P2P connection setup. Android also supports pre-association service discovery with Bonjour/DNS-SD through the Wi-Fi P2P APIs, which is useful for advertising only devices that expose our transfer service.

Wi-Fi Direct does not require an internet connection or a conventional Wi-Fi hotspot. After the P2P group is formed, the application can use normal Java/Kotlin sockets over the resulting local IP connection.

Sources:

- [Create P2P connections with Wi-Fi Direct — Android Developers](https://developer.android.com/develop/connectivity/wifi/wifi-direct)
- [WifiP2pManager API — Android Developers](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager)
- [Wi-Fi Direct service discovery — Android Developers](https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct)
- [Wi-Fi P2P NSD APIs — Android Developers](https://developer.android.com/reference/android/net/wifi/p2p/nsd/package-summary)

### 2.4 Permissions for Wi-Fi Direct

For apps targeting Android 13/API 33 or newer, Wi-Fi Direct requires `NEARBY_WIFI_DEVICES`. Android's documentation also describes `ACCESS_FINE_LOCATION` requirements depending on SDK level and whether the app derives location information from Wi-Fi APIs.

Some Wi-Fi P2P operations also depend on Location Mode being enabled on the device. The implementation must handle these permission/state checks instead of assuming discovery will always work.

Do not copy an old Android tutorial's permission set blindly. Permissions should be implemented according to the target SDK and current Android documentation.

### 2.5 Background file transfers

A long transfer may need a foreground service. Android 14/API 34+ requires foreground-service types to be declared. File/data transfer maps naturally to the `dataSync` foreground-service type.

However, Android 15/API 35+ introduces an important restriction: `dataSync` foreground services have a combined **6-hour total runtime within a 24-hour period**, with the timer reset when the user brings the app to the foreground. The service must also implement `Service.onTimeout()` correctly for affected versions.

Therefore, the app must not assume a foreground service can run forever.

Sources:

- [Foreground service types — Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 15 behavior changes: dataSync timeout — Android Developers](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Foreground-service background-start restrictions — Android Developers](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

### 2.6 File selection and storage

The application should use Android's system file-selection/storage APIs rather than assuming unrestricted access to arbitrary filesystem paths.

For general files/documents, `ACTION_OPEN_DOCUMENT` / Storage Access Framework is appropriate. For photos/videos, the Android Photo Picker can avoid broad media permissions. For files the app itself creates, `MediaStore` or app-specific storage can be used where appropriate.

Sources:

- [Common intents / ACTION_OPEN_DOCUMENT — Android Developers](https://developer.android.com/guide/components/intents-common)
- [Access media files from shared storage — Android Developers](https://developer.android.com/training/data-storage/shared/media)
- [Minimize permission requests — Android Developers](https://developer.android.com/privacy-and-security/minimize-permission-requests)

### 2.7 Encryption

Android provides `SSLSocket` and `SSLServerSocket` for TLS-protected socket connections. The transfer protocol can therefore run over TCP while the payload is protected by TLS.

Sources:

- [SSLSocket — Android Developers](https://developer.android.com/reference/javax/net/ssl/SSLSocket)
- [SSLServerSocket — Android Developers](https://developer.android.com/reference/javax/net/ssl/SSLServerSocket)

---

## 3. High-Level Architecture

```text
                        Android Transfer App
                                |
                +---------------+---------------+
                |                               |
         Discovery Layer                 Transfer Layer
                |                               |
        +-------+-------+               +-------+-------+
        |               |               |               |
       LAN          Wi-Fi Direct     Connection      File Engine
        |               |             Manager          |
       NSD        WifiP2pManager          |        +----+----+
        |               |                 |        |         |
        +-------+-------+                 +--- TCP/TLS   Storage
                |                                      |
                +------------- Device -----------------+
                              Pairing
```

### Key principle

The transfer engine should not know whether the connection came from LAN or Wi-Fi Direct.

It should receive an abstract connection such as:

```text
Connection
    ├── host/address
    ├── port
    ├── transport type
    ├── capabilities
    ├── estimated throughput
    └── secure channel
```

---

## 4. Transport Abstraction

Create a common interface conceptually similar to:

```text
Transport
  ├── discover()
  ├── connect(device)
  ├── disconnect()
  ├── getAddress()
  ├── getType()
  └── getCapabilities()
```

Implementations:

```text
LanTransport
WifiDirectTransport
```

Later, more transports could be added without rewriting the transfer engine:

```text
BluetoothTransport       (future, not MVP)
UsbTransport             (future)
InternetRelayTransport   (future)
```

---

# 5. LAN Mode

## 5.1 Topology

Example:

```text
Phone A 192.168.1.20
       |
       | Wi-Fi
       v
   Wi-Fi Router
       ^
       | Wi-Fi
       |
Phone B 192.168.1.21
```

The router provides the local network, but the actual file transfer is:

```text
Phone A <----------------------> Phone B
                TCP/TLS
```

No internet connection should be required.

## 5.2 LAN discovery

Use Android NSD (`NsdManager`) to advertise and discover the transfer service.

Example service concept:

```text
_xfer._tcp
```

Advertised metadata should be minimal and non-sensitive:

```text
device_id
friendly_name
protocol_version
listen_port
capabilities
availability
```

Example:

```json
{
  "device_id": "generated-app-instance-id",
  "name": "Kali Phone",
  "port": 45821,
  "version": 1,
  "capabilities": [
    "resume",
    "multi-file",
    "folders",
    "hash-verification"
  ]
}
```

Do not use a hardware identifier such as IMEI as the app's identity. Android's privacy guidance recommends an app-scoped identifier or an identifier generated by the app.

## 5.3 Network awareness

Use `ConnectivityManager` / `Network` information so the app understands which local networks exist. This matters because a phone may have more than one network/interface available.

The implementation should avoid assuming the first Wi-Fi address is always the correct endpoint.

---

# 6. Wi-Fi Direct Mode

## 6.1 Main APIs

Primary Android APIs:

```text
WifiP2pManager
WifiP2pManager.Channel
WifiP2pConfig
WifiP2pInfo
WifiP2pDevice
WifiP2pServiceInfo
WifiP2pServiceRequest
```

## 6.2 Wi-Fi Direct flow

```text
Initialize WifiP2pManager
        |
        v
Register Wi-Fi P2P listeners
        |
        v
Advertise transfer service
        |
        v
Discover services/peers
        |
        v
User selects peer
        |
        v
Request connection
        |
        v
P2P group formed
        |
        v
Determine group-owner address / peer address
        |
        v
Open TCP/TLS connection
        |
        v
Use normal transfer engine
```

## 6.3 Wi-Fi Direct service discovery

Prefer service discovery over showing every nearby Wi-Fi Direct peer as though it were necessarily running our app.

The app can advertise a Bonjour/DNS-SD service describing the transfer service, then discover matching services before initiating a connection.

## 6.4 Permissions and device state

The implementation must check:

- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_STATE`
- `INTERNET`
- `NEARBY_WIFI_DEVICES` on current Android targets where required
- `ACCESS_FINE_LOCATION` where required by the target SDK/use case
- Location Mode state where the platform requires it for the requested P2P operation

These checks should be encapsulated in `PermissionManager` and `WifiDirectManager`, not scattered throughout Compose screens.

---

# 7. Connection Layer

Create a common connection abstraction:

```text
Connection
    |
    +-- TcpConnection
            |
            +-- TlsConnection
```

The discovery method decides the address and port. The connection layer then establishes a secure socket.

Conceptual flow:

```text
Discovery
   |
   +--> 192.168.1.20:45821
   |
   +--> 192.168.49.1:45821
              |
              v
         TCP connection
              |
              v
         TLS handshake
              |
              v
       Protocol handshake
              |
              v
        Transfer session
```

---

# 8. Pairing and Authentication

Discovery does not equal trust.

A device being visible must not automatically grant permission to send or receive files.

## 8.1 Initial pairing

Example:

```text
Phone A:

Connect to "Kali Phone"?

Verification code:
482 917

[Accept]  [Reject]
```

Phone B displays the same code.

After both users accept:

```text
Phone A <==== trusted connection ====> Phone B
```

## 8.2 Device identity

Generate an app-scoped device ID on first launch and store it locally.

Example:

```text
UUID/randomUUID()
```

The identity should not depend on an IMEI, serial number, or other restricted hardware identifier.

## 8.3 Trusted-device database

Store only what is necessary:

```text
device_id
friendly_name
public-key/certificate identity if used
first_seen
last_seen
trusted
```

---

# 9. Secure Transport

Use TLS over TCP for the first production-capable implementation.

Conceptually:

```text
Application Protocol
        |
       TLS
        |
       TCP
        |
 LAN or Wi-Fi Direct
```

TLS protects confidentiality and integrity while also providing a path for peer authentication.

The pairing system should be designed to prevent silent man-in-the-middle acceptance. A practical approach is to combine:

1. a user-visible verification code during first pairing;
2. a locally generated app identity;
3. persistent trust after successful verification.

Do not invent a custom cryptographic protocol for the MVP.

---

# 10. Transfer Protocol

Create our own small application-level protocol on top of TLS.

## 10.1 Session flow

```text
CONNECT
   |
   v
HANDSHAKE
   |
   v
AUTHENTICATE / PAIR
   |
   v
TRANSFER_REQUEST
   |
   v
FILE_METADATA
   |
   v
CHUNK_DATA
   |
   v
ACK / RESUME INFORMATION
   |
   v
TRANSFER_COMPLETE
   |
   v
VERIFICATION
   |
   v
SESSION_CLOSE
```

## 10.2 Message types

Minimum initial protocol:

```text
HELLO
HELLO_ACK
PAIR_REQUEST
PAIR_ACCEPT
TRANSFER_REQUEST
TRANSFER_ACCEPT
FILE_START
CHUNK
CHUNK_ACK
FILE_COMPLETE
TRANSFER_COMPLETE
ERROR
CANCEL
PAUSE
RESUME
```

Protocol messages should have an explicit version so future versions can evolve without breaking old clients.

---

# 11. Chunking

Large files should never be treated as one giant uninterruptible write.

Example:

```text
5 GB file

+----------+----------+----------+-----+
| Chunk 0  | Chunk 1  | Chunk 2  | ... |
+----------+----------+----------+-----+
```

Initial target chunk size:

```text
4 MB - 16 MB
```

This is a benchmark starting range, not a fixed claim that one size is always optimal.

The final size should be selected after testing on actual Android devices and different network types.

Each chunk should have:

```text
file_id
chunk_index
offset
length
hash
```

---

# 12. Resume Support

A transfer interrupted at 7.2 GB of 8 GB should resume rather than restart.

Example:

```text
8.0 GB total
7.2 GB already received

Connection lost
       |
       v
Reconnect
       |
       v
Exchange resume state
       |
       v
Continue from next missing chunk
```

Store resume state in Room.

Suggested fields:

```text
transfer_id
file_id
file_name
file_size
chunk_size
completed_bytes
completed_chunks
source_hash
status
created_at
updated_at
```

For stronger resume integrity, track completed chunk indexes rather than only a single byte count when transfer order can become non-sequential.

---

# 13. Integrity Verification

Use BLAKE3 for file/chunk integrity verification if the selected Android library is stable and maintained for the project.

At minimum:

```text
Sender file hash
        |
        v
     BLAKE3
        |
        v
Receiver file hash
        |
        v
Compare
```

Result:

```text
MATCH   -> verified
MISMATCH -> transfer failed / corrupted data
```

For resumed transfers, chunk-level hashes are useful for validating previously completed chunks.

---

# 14. Parallelism and Performance

Do not assume that more sockets automatically means faster transfers.

The first implementation should use a single reliable stream. After the baseline is stable, benchmark controlled concurrency.

Possible strategy:

```text
Concurrency = 1
       |
   benchmark
       |
Concurrency = 2
       |
   benchmark
       |
Concurrency = 4
       |
   benchmark
       |
Select best safe configuration
```

Measure:

- MB/s
- CPU usage
- memory usage
- storage read speed
- storage write speed
- battery drain
- temperature/thermal throttling
- packet retransmissions/errors
- transfer completion time

Do not report a network speed that is not actually measured.

---

# 15. Automatic LAN vs Wi-Fi Direct Selection

The app should make transport choice mostly invisible to the user.

If a pair of devices can reach each other through both paths, represent them as candidate connections:

```text
LAN
Wi-Fi Direct
```

Then compare practical connectivity instead of assuming Wi-Fi Direct is always faster.

Conceptual selection:

```text
             Device discovery
                    |
          +---------+---------+
          |                   |
        LAN              Wi-Fi Direct
          |                   |
          +---------+---------+
                    |
             connectivity test
                    |
             optional short
             throughput test
                    |
                    v
             selected transport
                    |
                    v
             Transfer Engine
```

Important: the MVP does not need a complex automatic benchmark. Start with deterministic priority:

```text
1. LAN if reachable and healthy
2. Wi-Fi Direct otherwise
```

Then add adaptive benchmarking after reliable transfers exist.

---

# 16. File Selection

The sender should support:

- individual files
- multiple files
- folders, where platform APIs and selected implementation permit it
- photos
- videos
- music
- documents
- arbitrary file types

Use Android's system pickers instead of asking for broad filesystem access when unnecessary.

For general-purpose files/documents:

```text
ACTION_OPEN_DOCUMENT
```

For media where appropriate:

```text
Android Photo Picker / MediaStore
```

Persist URI permissions when a long-running transfer needs continued access to user-selected documents.

---

# 17. Receive Destination

The receiver should let the user select or configure a destination.

Possible default categories:

```text
Download/
Movies/
Pictures/
Music/
Documents/
```

Do not hard-code raw filesystem paths as the only mechanism because modern Android uses scoped storage and document/provider-based access patterns.

For general files, Storage Access Framework can allow the user to choose where the files should be saved.

---

# 18. Room Database

Use Room for local persistent transfer state.

Suggested entities:

```text
DeviceEntity
TransferEntity
FileTransferEntity
ChunkEntity
TrustedDeviceEntity
AppSettingsEntity
```

## Example relationships

```text
Transfer
   |
   +---- FileTransfer
              |
              +---- Chunk
```

## Transfer status enum

```text
QUEUED
CONNECTING
TRANSFERRING
PAUSED
INTERRUPTED
VERIFYING
COMPLETED
FAILED
CANCELLED
```

---

# 19. Background Transfer Design

Use a foreground service for long-running active transfers when necessary.

Suggested architecture:

```text
Compose UI
    |
    v
TransferViewModel
    |
    v
TransferRepository
    |
    v
TransferService
    |
    v
TransferManager
    |
    +---- ConnectionManager
    +---- ChunkManager
    +---- Room
    +---- File I/O
```

## Android 14+

Declare the appropriate foreground-service type and permission.

For file/data transfer, `dataSync` is the relevant Android foreground-service type.

## Android 15+

Handle the 6-hour/24-hour `dataSync` service limit. Implement `Service.onTimeout()` and design the transfer system so the user can reopen the app and continue work rather than treating a long-running service as immortal.

Start the active transfer service from a user-visible action whenever possible, because Android also restricts background foreground-service starts.

---

# 20. Notifications

During an active transfer:

```text
Sending Ubuntu.iso
3.82 GB / 5.00 GB
76%
48.2 MB/s
ETA 25 sec

[Pause]
[Cancel]
```

The notification should be updated without overwhelming the system with excessive updates.

When complete:

```text
Transfer complete
Ubuntu.iso
5.00 GB
Verified
```

---

# 21. UI Design

Use Jetpack Compose and Material 3.

## Home screen

```text
+--------------------------------+
|          Transfer              |
|                                |
|        +-----------+           |
|        | Send Files|           |
|        +-----------+           |
|                                |
|        +-----------+           |
|        |  Receive  |           |
|        +-----------+           |
|                                |
| Nearby Devices                 |
|                                |
| [Phone A]  LAN                 |
| [Phone B]  Wi-Fi Direct        |
+--------------------------------+
```

## Device screen

Show:

```text
Device name
Connection type
Approximate link information
Trust state
Connect button
```

## File selection screen

```text
Recent
Images
Videos
Documents
Audio
Other files
```

## Transfer screen

```text
Sending 23 files

██████████████░░░░

1.82 GB / 2.40 GB
18 / 23 files
72 MB/s
ETA 9 sec

[Pause]    [Cancel]
```

## History screen

```text
Completed
Failed
Interrupted
Cancelled
```

---

# 22. Proposed Android Studio Project Structure

```text
android-transfer/
│
├── app/
│   ├── src/main/java/com/example/transfer/
│   │   │
│   │   ├── MainActivity.kt
│   │   │
│   │   ├── ui/
│   │   │   ├── navigation/
│   │   │   ├── home/
│   │   │   ├── devices/
│   │   │   ├── files/
│   │   │   ├── transfer/
│   │   │   └── history/
│   │   │
│   │   ├── discovery/
│   │   │   ├── LanDiscovery.kt
│   │   │   ├── WifiDirectDiscovery.kt
│   │   │   └── DeviceDiscoveryManager.kt
│   │   │
│   │   ├── network/
│   │   │   ├── Connection.kt
│   │   │   ├── TcpConnection.kt
│   │   │   ├── TlsConnection.kt
│   │   │   └── ConnectionManager.kt
│   │   │
│   │   ├── transfer/
│   │   │   ├── TransferManager.kt
│   │   │   ├── TransferSession.kt
│   │   │   ├── ChunkManager.kt
│   │   │   ├── ResumeManager.kt
│   │   │   └── IntegrityVerifier.kt
│   │   │
│   │   ├── database/
│   │   │   ├── AppDatabase.kt
│   │   │   ├── DeviceDao.kt
│   │   │   ├── TransferDao.kt
│   │   │   ├── DeviceEntity.kt
│   │   │   ├── TransferEntity.kt
│   │   │   └── FileTransferEntity.kt
│   │   │
│   │   ├── service/
│   │   │   └── TransferService.kt
│   │   │
│   │   ├── security/
│   │   │   ├── PairingManager.kt
│   │   │   ├── IdentityManager.kt
│   │   │   └── TlsManager.kt
│   │   │
│   │   ├── storage/
│   │   │   └── StorageManager.kt
│   │   │
│   │   └── model/
│   │       ├── Device.kt
│   │       ├── Transfer.kt
│   │       └── TransferFile.kt
│   │
│   └── src/main/res/
│       ├── drawable/
│       ├── mipmap/
│       ├── values/
│       └── xml/
│
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

Package name should be chosen before implementation and then kept consistent.

---

# 23. Recommended Android Studio Starting Point

Create a new Android Studio project using:

```text
Template: Empty Activity
Language: Kotlin
UI: Jetpack Compose
```

Android's official Compose setup currently documents this template and recommends a minimum API of 21 for a basic Compose application. For this project, however, the final minimum SDK should be chosen based on the Android versions we actually want to support after checking the networking APIs and device test matrix.

Do not unnecessarily pin old dependency versions copied from old tutorials. Use the versions generated/recommended by the current Android Studio project template and official Android documentation.

---

# 24. Manifest / Permission Plan

The exact final manifest should be generated for the selected compile/target SDK, but the project is expected to need networking and Wi-Fi-related declarations including concepts like:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
```

Location permission handling may also be required depending on the SDK/device behavior and the exact Wi-Fi P2P APIs being used.

For long-running transfer service support on modern Android, foreground service permissions/types also need to be declared according to the selected target SDK.

Do not simply paste this section into `AndroidManifest.xml` without checking the SDK-specific requirements during implementation.

---

# 25. Initial MVP Scope

The first working MVP should be intentionally small.

## MVP-1: LAN file transfer

Requirements:

- [ ] Android Studio project created with Kotlin + Compose
- [ ] App launches on two physical Android phones
- [ ] Device gets app-scoped random ID
- [ ] Local device name configured
- [ ] TCP server can listen on a dynamically selected port
- [ ] LAN service advertised using NSD
- [ ] LAN service discovered from another phone
- [ ] Device list shown in UI
- [ ] User can select one device
- [ ] User can accept/reject connection
- [ ] Basic TCP/TLS handshake works
- [ ] One file can be selected
- [ ] One file can be transferred
- [ ] Receiver can save the file
- [ ] Transfer progress is visible
- [ ] Transfer speed is visible
- [ ] Final file hash matches sender

Do not add Wi-Fi Direct before this path is stable.

---

# 26. MVP-2: Transfer Reliability

- [ ] Multiple files
- [ ] Queue
- [ ] Pause
- [ ] Cancel
- [ ] Resume
- [ ] Room database
- [ ] Interrupted-transfer recovery
- [ ] Hash verification
- [ ] Transfer history
- [ ] Foreground notification
- [ ] Proper lifecycle handling

---

# 27. MVP-3: Wi-Fi Direct

- [ ] Initialize `WifiP2pManager`
- [ ] Handle P2P state broadcasts/callbacks
- [ ] Request required permissions
- [ ] Handle Location Mode requirements where applicable
- [ ] Discover nearby peers
- [ ] Advertise transfer service
- [ ] Discover transfer service
- [ ] Establish P2P connection
- [ ] Determine usable local IP
- [ ] Connect to the same TCP/TLS server used by LAN
- [ ] Transfer the same file protocol
- [ ] Disconnect/cleanup P2P connection correctly

The transfer engine must remain unchanged.

---

# 28. MVP-4: Automatic Transport Selection

- [ ] Detect LAN candidate
- [ ] Detect Wi-Fi Direct candidate
- [ ] De-duplicate same physical device discovered through both paths
- [ ] Rank candidates
- [ ] Prefer healthy LAN connection initially
- [ ] Fall back to Wi-Fi Direct
- [ ] Add optional throughput testing
- [ ] Record observed performance
- [ ] Select the best transport in later iterations

---

# 29. Testing Strategy

Testing should use real physical phones, not only emulators, because Wi-Fi P2P and network behavior are hardware/vendor dependent.

## Minimum device matrix

Test at least:

```text
Android phone A
Android phone B
```

Then expand to several manufacturers and Android releases.

Useful combinations:

```text
Same Wi-Fi router
Phone hotspot
No shared network
Wi-Fi Direct
Wi-Fi Direct + mobile internet simultaneously
Weak Wi-Fi signal
Network interruption during transfer
Screen turned off during transfer
App moved to background
Device locked
Large file > 4 GB
Many small files
Files containing Unicode names
Existing destination filename collision
```

---

# 30. Performance Test Plan

Use known files:

```text
100 MB
1 GB
4 GB
8 GB+
```

For each test record:

```text
Transport
Device pair
File size
Total time
Average MB/s
Peak MB/s
CPU usage
RAM usage
Battery change
Temperature/thermal state
```

Example result table:

| Test | Transport | Size | Time | Avg MB/s | Result |
|---|---|---:|---:|---:|---|
| A | LAN | 1 GB | TBD | TBD | TBD |
| B | Wi-Fi Direct | 1 GB | TBD | TBD | TBD |
| C | LAN | 4 GB | TBD | TBD | TBD |
| D | Wi-Fi Direct | 4 GB | TBD | TBD | TBD |

Do not use theoretical Wi-Fi link rates as transfer speed claims. Measure real application throughput.

---

# 31. Failure Handling

Every layer needs explicit failure states.

Examples:

```text
No Wi-Fi
No LAN
NSD registration failed
NSD discovery failed
Wi-Fi Direct unavailable
Wi-Fi Direct permission denied
Location Mode disabled when required
Peer disconnected
TLS handshake failure
Authentication rejected
Receiver storage unavailable
Permission revoked
Out of space
File changed during transfer
Hash mismatch
Transfer cancelled
Transfer service stopped
```

User-facing messages should explain what happened without exposing unnecessary internal stack traces.

Example:

```text
Unable to connect to Samsung A15.
The device may have gone offline.

[Try Again]
```

---

# 32. Security Requirements

- [ ] Never trust a device solely because it is discoverable
- [ ] Use TLS for transfer payloads
- [ ] Verify new peers with a user-visible pairing mechanism
- [ ] Store only necessary trusted-device information
- [ ] Never use IMEI as the application identity
- [ ] Validate every protocol field
- [ ] Limit maximum message sizes
- [ ] Prevent directory traversal when receiving file names
- [ ] Sanitize/normalize received file paths
- [ ] Never allow a remote peer to choose arbitrary absolute paths
- [ ] Require explicit user consent before receiving files unless the user has enabled a trusted-device mode
- [ ] Delete incomplete temporary files on permanent cancellation where appropriate

---

# 33. Receiving Files Safely

Never directly concatenate an incoming filename with a filesystem directory.

Unsafe concept:

```text
/path/from/user + "/" + remoteFilename
```

Instead:

```text
remote metadata
      |
 validate
      |
 normalize
      |
 strip illegal path components
      |
 resolve destination through Android storage APIs
      |
 write file
```

The protocol should transfer a filename, not an arbitrary filesystem path.

---

# 34. Future Rust Integration

A Rust core remains an option because we previously experimented with Rust for high-throughput transfer systems.

However, do not begin with JNI/UniFFI complexity.

Recommended progression:

```text
Phase 1
Kotlin transfer engine

        |
        | benchmark
        v

Phase 2
Optimize Kotlin/I/O/network code

        |
        | benchmark again
        v

Phase 3
Only if justified:
move performance-critical core to Rust
```

The protocol should be language-neutral from the beginning so that a future Rust core can implement the same wire protocol.

---

# 35. Future Cross-Platform Architecture

Long term:

```text
              Shared Transfer Protocol
                       |
         +-------------+-------------+
         |             |             |
      Android       Windows        Linux
         |             |             |
      Kotlin          Rust           Rust
```

This could eventually allow the Android app to transfer files directly to the Windows Rust application using the same protocol.

---

# 36. What We Should NOT Build Initially

Do not add these to the first milestone:

- Bluetooth transfer
- cloud servers
- user accounts
- internet relay
- WebRTC
- custom cryptography
- complicated multi-path networking
- AI features
- media compression
- automatic file deduplication
- peer-to-peer routing between more than two phones
- a custom filesystem

Every extra subsystem increases debugging complexity.

---

# 37. Proposed Development Order

```text
STEP 1
Create Android Studio Kotlin + Compose project
        |
STEP 2
Build TCP server/client test
        |
STEP 3
Build LAN NSD discovery
        |
STEP 4
Connect two phones over LAN
        |
STEP 5
Implement TLS
        |
STEP 6
Implement one-file transfer
        |
STEP 7
Add progress/speed/ETA
        |
STEP 8
Add chunking
        |
STEP 9
Add Room persistence
        |
STEP 10
Add pause/resume/recovery
        |
STEP 11
Add BLAKE3 verification
        |
STEP 12
Add foreground transfer service
        |
STEP 13
Add Wi-Fi Direct discovery
        |
STEP 14
Connect Wi-Fi Direct to the existing transport abstraction
        |
STEP 15
Add automatic transport selection
        |
STEP 16
Benchmark and optimize
```

---

# 38. First Coding Milestone

The first coding milestone is intentionally narrow:

> **Two physical Android phones on the same Wi-Fi network can discover each other, connect securely, send a 100 MB test file, display live progress/speed, and verify that the receiver's BLAKE3 hash matches the sender's.**

Acceptance criteria:

```text
Phone A
   |
   | NSD discovery
   v
Phone B
   |
   | TLS connection
   v
Transfer request
   |
   | 100 MB file
   v
Receiver
   |
   | BLAKE3 verification
   v
SUCCESS
```

Once this works repeatedly, begin MVP-2 reliability features.

---

# 39. Recommended Initial Deliverables

At the start of implementation, create these files first:

```text
MainActivity.kt
DeviceDiscoveryManager.kt
LanDiscovery.kt
Connection.kt
TcpConnection.kt
TlsConnection.kt
TransferManager.kt
StorageManager.kt
```

Then add the database and service only after the first LAN transfer path is proven.

---

# 40. Engineering Principles

### Principle 1 — Transport independence

The transfer engine must not care whether the peer came from LAN or Wi-Fi Direct.

### Principle 2 — Measure before optimizing

No assumptions about Wi-Fi Direct or LAN throughput should become permanent design decisions before benchmarking.

### Principle 3 — Reliability before speed

A transfer that completes incorrectly at 200 MB/s is worse than a reliable transfer at 50 MB/s.

### Principle 4 — Use Android APIs for Android-specific constraints

Use Android's official networking, permission, storage, lifecycle, and service APIs instead of fighting the platform.

### Principle 5 — Keep the protocol simple

A small, versioned protocol will be easier to debug and later port to Rust/Windows/Linux.

### Principle 6 — Test hardware early

Wi-Fi Direct behavior can vary between phones and vendors. Physical-device testing is mandatory.

---

# 41. Key Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Wi-Fi Direct vendor differences | High | Test multiple real devices |
| Android permission changes | High | Keep permissions SDK-aware |
| Background execution restrictions | High | Use correct FGS design and Android-specific lifecycle handling |
| Storage access restrictions | Medium | Use SAF/MediaStore appropriately |
| Slow flash storage | Medium | Benchmark storage independently |
| Thermal throttling | Medium | Long-duration performance tests |
| Router/AP bottleneck | Medium | Compare LAN on multiple APs and hotspots |
| Connection interruption | High | Chunking + Room resume state |
| Protocol bugs | High | Versioned messages + integration tests |
| TLS/pairing mistakes | High | Prefer standard TLS and simple authenticated pairing |

---

# 42. Definition of Done for Version 1

Version 1 is complete when the following are all true:

- [ ] Android Studio project builds cleanly
- [ ] Two physical Android devices can discover one another over LAN
- [ ] Two physical Android devices can discover one another over Wi-Fi Direct
- [ ] Both paths use the same transfer engine
- [ ] TLS protects the transfer connection
- [ ] User pairing/acceptance works
- [ ] Single files transfer successfully
- [ ] Multiple files transfer successfully
- [ ] Large files transfer successfully
- [ ] Transfers can be paused/cancelled
- [ ] Interrupted transfers can resume
- [ ] Hash verification detects corruption
- [ ] Transfer status survives app recreation
- [ ] Notifications report active transfer state
- [ ] The app handles modern foreground-service restrictions correctly
- [ ] The app respects Android storage/security rules
- [ ] LAN and Wi-Fi Direct throughput have been measured on real devices
- [ ] Major failure cases have user-friendly handling

---

# 43. Official Android References

- Android Kotlin: https://developer.android.com/kotlin
- Jetpack Compose setup: https://developer.android.com/develop/ui/compose/setup
- Network service discovery / NSD: https://developer.android.com/reference/android/net/nsd/NsdManager
- NSD service information: https://developer.android.com/reference/android/net/nsd/NsdServiceInfo
- Network state: https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- Wi-Fi Direct: https://developer.android.com/develop/connectivity/wifi/wifi-direct
- Wi-Fi Direct service discovery: https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct
- WifiP2pManager: https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager
- Wi-Fi P2P NSD APIs: https://developer.android.com/reference/android/net/wifi/p2p/nsd/package-summary
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Background FGS start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 15 behavior changes: https://developer.android.com/about/versions/15/behavior-changes-15
- Storage/media: https://developer.android.com/training/data-storage/shared/media
- Common intents / documents: https://developer.android.com/guide/components/intents-common
- Permission minimization: https://developer.android.com/privacy-and-security/minimize-permission-requests
- TLS SSLSocket: https://developer.android.com/reference/javax/net/ssl/SSLSocket
- TLS SSLServerSocket: https://developer.android.com/reference/javax/net/ssl/SSLServerSocket

---

# 44. Final Architecture Summary

```text
                         ANDROID TRANSFER APP

                              Compose UI
                                  |
                           ViewModels / State
                                  |
                           Transfer Repository
                                  |
                         +--------+--------+
                         |                 |
                  Discovery Manager   Transfer Manager
                         |                 |
              +----------+----------+     +----------+----------+
              |                     |                |          |
          LAN / NSD          Wi-Fi Direct       Chunking      Room
              |                     |                |
              +----------+----------+                |
                         |                            |
                  Connection Manager                 |
                         |                            |
                     TCP + TLS                      |
                         |                            |
                  Transfer Protocol                 |
                         |                            |
                    File Storage <------------------+
```

The core idea is simple:

**LAN and Wi-Fi Direct are two ways of finding/reaching the peer. The transfer engine is one shared system.**

That design gives us a clean path from a simple Android Studio MVP to a high-performance, resumable, encrypted Android-to-Android transfer application, and later to a shared protocol that can interoperate with a Rust Windows client.
