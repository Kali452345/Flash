# Android Platform Notes

## 2026-08-18 - Pixel 7 local-network permission issue

### Android version / API level
- Project compile SDK: 37
- Project target SDK changed from 37 to 36 for the LAN MVP.

### APIs / permissions involved
- `ACCESS_LOCAL_NETWORK`
- `INTERNET`
- `NsdManager`

### Official documentation source
- Android local network permission: https://developer.android.com/privacy-and-security/local-network-permission
- Android 17 behavior changes: https://developer.android.com/about/versions/17/behavior-changes-17
- Android `NsdManager` reference: https://developer.android.com/reference/android/net/nsd/NsdManager

### Project implication
Android documentation says `ACCESS_LOCAL_NETWORK` is required only for apps targeting Android 17 / SDK 37 or higher. For apps targeting SDK 36 or lower, local network access is implicitly granted through `INTERNET`, and apps should not declare or request `ACCESS_LOCAL_NETWORK`.

During Pixel 7 testing, the app logged repeated system-server errors:

```text
Operation not found: uid=10315 pkg=com.transfer.flash(null) op=ACCESS_LOCAL_NETWORK
```

The Pixel also showed a system-mediated local-network device prompt that was not part of this app's UI. For the current LAN MVP, the app now targets SDK 36 and no longer declares `ACCESS_LOCAL_NETWORK`. When the app returns to target SDK 37, it needs a deliberate runtime permission or system-device-picker flow.

## 2026-08-18 - LAN NSD kickoff

### Android version / API level
- Project compile SDK: 37
- Project target SDK: 36
- Project min SDK: 24

### APIs / permissions involved
- `NsdManager`
- `NsdServiceInfo`
- `WifiManager.MulticastLock`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_MULTICAST_STATE`

### Official documentation source
- Android `NsdManager` reference: https://developer.android.com/reference/android/net/nsd/NsdManager
- Android NSD guide: https://developer.android.com/develop/connectivity/wifi/use-nsd

### Project implication
- NSD registration and discovery are asynchronous and must be stopped when the app no longer needs them.
- `NsdManager.resolveService` remains usable for the minSdk 24 MVP path, but current documentation deprecates it on newer API levels in favor of service-info callbacks that keep service addresses current.
- mDNS reception over Wi-Fi can require a multicast lock on older devices / extension levels. The LAN MVP acquires a non-reference-counted multicast lock only while LAN discovery is running.
- API 37 targets need to account for local network restrictions. The MVP currently avoids that path by targeting SDK 36 until the local-network runtime permission/user flow is implemented.

### Current project implication
The first LAN implementation uses classic NSD discovery and per-service resolution to keep compatibility down to API 24. A later reliability pass should introduce the newer `registerServiceInfoCallback` path for API levels/extensions where it is available.
