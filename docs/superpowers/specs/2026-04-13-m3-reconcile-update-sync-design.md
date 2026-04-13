# M3 Reconciliation, In-App Update & Background Sync — Design Spec

**Date:** 2026-04-13
**Branch:** `feature/m3-multi-wallet`
**Issues:** #78 (Background Sync), #79 (Reconcile v1.4.1 with M3)

---

## 1. Issue #79 — Reconcile v1.4.1 Bugfixes with M3

### Goal

Merge `main` (which includes v1.4.1 bugfixes) into `feature/m3-multi-wallet` and ensure all wallet-scoped equivalents carry the same fixes. Prevents re-introducing bugs when M3 re-merges to main.

### Steps

1. `git merge main` into `feature/m3-multi-wallet`, resolve conflicts.
2. Walk each checklist item and verify/port the fix into wallet-scoped code.

### Checklist

| # | Bugfix | M3 Location | Action |
|---|--------|-------------|--------|
| 1 | Atomic write (`commit()` pattern) | `KeyManager.storeKeysForWallet()` | Apply single `commit()` call instead of multiple `apply()` calls |
| 2 | Copy backup flag during migration | `WalletMigrationHelper` | Add `KEY_MNEMONIC_BACKED_UP` to the list of keys copied during migration |
| 3 | Onboarding gate for multi-wallet | Navigation start destination logic | Check active wallet backup status via `WalletRepository` |
| 4 | Mark mnemonic as backed up on import | `KeyManager` / `AddWalletScreen` import flow | Set `KEY_MNEMONIC_BACKED_UP = true` when importing via seed phrase |
| 5 | StrongBox fallback | `getWalletPrefs(walletId)` | Wrap `EncryptedSharedPreferences` creation with StrongBox try/catch fallback |
| 6 | Theme preference | `WalletPreferences` / `Theme.kt` | Verify carries forward cleanly (non-wallet-scoped) |
| 7 | ProcessPhoenix restart | Network switch logic | Verify carries forward cleanly |
| 8 | No settings icon on Home | `HomeScreen.kt` | Verify M3 does not re-add the settings icon |
| 9 | Version >= 1.5.0 / code >= 6 | `build.gradle.kts` | Already set on M3 branch — verify |

### Verification

- Run `./gradlew testDebugUnitTest` after all fixes applied.
- Manual walkthrough: import wallet flow, network switch, theme toggle.

---

## 2. In-App Update System (GitHub Releases)

### Goal

On app launch, check GitHub Releases for a newer version. If found, show a dialog prompting the user to download and install the update. For sideloaded users only (not on Play Store).

### Architecture

```
App Launch (HomeViewModel init)
  -> UpdateChecker.checkForUpdate()
    -> GET https://api.github.com/repos/RaheemJnr/pocket-node/releases/latest
    -> Parse tag_name, compare to BuildConfig.VERSION_NAME
    -> If newer: emit UpdateState.Available(release)
      -> HomeScreen shows UpdateDialog
        -> User taps "Update Now"
          -> UpdateDownloader.download(apkUrl)
            -> DownloadManager handles download + notification
              -> BroadcastReceiver on ACTION_DOWNLOAD_COMPLETE
                -> FileProvider URI -> ACTION_INSTALL_PACKAGE intent
```

### New Files

| File | Purpose |
|------|---------|
| `data/update/UpdateChecker.kt` | `@Singleton` — fetches latest GitHub release, compares versions, exposes `StateFlow<UpdateState>` |
| `data/update/UpdateDownloader.kt` | Wraps `DownloadManager` for APK download, registers `BroadcastReceiver` for completion |
| `data/update/UpdateModels.kt` | `GitHubRelease` data class (`@Serializable`), `UpdateState` sealed class (`Idle`, `Available`, `Downloading`, `ReadyToInstall`, `Error`) |
| `ui/components/UpdateDialog.kt` | Material 3 `AlertDialog` — shows version, release notes summary, file size, "Update Now" / "Later" buttons |

### Modified Files

| File | Change |
|------|--------|
| `di/AppModule.kt` | Add `@Provides` for `UpdateChecker`, `UpdateDownloader` |
| `ui/screens/home/HomeViewModel.kt` | Call `updateChecker.checkForUpdate()` on init, expose `updateState` |
| `ui/screens/home/HomeScreen.kt` | Observe `updateState`, show `UpdateDialog` when `Available` |
| `AndroidManifest.xml` | Add `REQUEST_INSTALL_PACKAGES` permission |
| `proguard-rules.pro` | Keep rule for `UpdateModels` if needed |

### Version Comparison

Simple semver comparison: split tag (e.g., `v1.5.0` -> `[1, 5, 0]`) and compare component-by-component against `BuildConfig.VERSION_NAME`. No pre-release/metadata parsing needed.

### Permissions

- `REQUEST_INSTALL_PACKAGES` — required for `ACTION_INSTALL_PACKAGE` intent.
- `INTERNET` — already declared.

### FileProvider

Add a `file_provider_paths.xml` resource (if not already present) to expose the download cache directory:

```xml
<paths>
    <external-cache-path name="apk_downloads" path="." />
</paths>
```

Register in `AndroidManifest.xml`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_provider_paths" />
</provider>
```

### Edge Cases

| Case | Behavior |
|------|----------|
| No network | Silent fail — no dialog shown |
| GitHub API rate limited (60/hr unauthenticated) | Silent fail |
| Download fails | System notification shows failure, user can retry |
| No APK asset in release | Skip update prompt |
| User taps "Later" | Dismisses for current session only |
| User already on latest | No dialog |
| Parse error on version tag | Skip update prompt |

### Dependencies

None new. Uses:
- `kotlinx.serialization` (existing) for GitHub API JSON parsing
- `android.app.DownloadManager` (system)
- `androidx.core.content.FileProvider` (existing transitive dep)

---

## 3. Issue #78 — Background Sync via Foreground Service

### Goal

Keep the CKB light client syncing when the app is backgrounded or swiped away. Show a persistent notification with sync progress and ETA. User can toggle in Settings (default: on).

### Architecture

```
GatewayRepository (node init completes)
  -> if backgroundSyncEnabled:
    -> startService(SyncForegroundService)
      -> startForeground() with notification
      -> Poll getSyncProgress() every 5s
      -> SyncProgressTracker calculates % and ETA
      -> SyncNotificationManager updates notification
      -> At tip: reduce polling to 30s, update notification to "synced"
      -> On new blocks: resume 5s polling

Settings toggle
  -> WalletPreferences.backgroundSyncEnabled
  -> ON: starts service if node is running
  -> OFF: stops service immediately
```

### New Files

| File | Purpose |
|------|---------|
| `data/sync/SyncForegroundService.kt` | Android `Service` — `START_STICKY`, foreground with `dataSync` type, polls progress, manages lifecycle |
| `data/sync/SyncNotificationManager.kt` | Creates notification channel, builds/updates notification content |
| `data/sync/SyncProgressTracker.kt` | Sliding window (10 samples) of block heights + timestamps, calculates blocks/sec, percentage, ETA |

### Modified Files

| File | Change |
|------|--------|
| `AndroidManifest.xml` | Add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` permissions; declare `SyncForegroundService` |
| `data/wallet/WalletPreferences.kt` | Add `backgroundSyncEnabled` property (default: `true`) |
| `data/gateway/GatewayRepository.kt` | Start service after node init if enabled; expose sync progress data for service to poll |
| `ui/screens/settings/SettingsScreen.kt` (or wherever settings live) | Add "Background sync" toggle |
| `ui/screens/home/HomeScreen.kt` | Request `POST_NOTIFICATIONS` permission on API 33+ (if not already) |

### Service Lifecycle

| Event | Behavior |
|-------|----------|
| Node init completes | Start service (if enabled) |
| App backgrounded | Service keeps running, notification visible |
| App swiped away | `START_STICKY` — Android restarts service, re-attaches to JNI client |
| Sync reaches tip | Notification: "CKB synced — up to date", polling slows to 30s |
| New blocks detected | Resume 5s polling, update notification |
| User disables in Settings | `stopService()` immediately |
| User enables in Settings | `startService()` if node is running |
| System kills process (low memory) | `START_STICKY` restarts, service calls `GatewayRepository` to re-init |
| Wallet deleted | Stop service |

### Notification

- **Channel:** "Sync Status" (`sync_status`), importance `LOW` (no sound/vibration)
- **While syncing:** "Syncing CKB... 45% — ~12 min remaining" with `setProgress(100, 45, false)`
- **At tip:** "CKB synced — up to date" with no progress bar
- **Tap action:** `PendingIntent` to open `MainActivity`
- **Not dismissible** while service is running (foreground notification)

### ETA Calculation (SyncProgressTracker)

```
Sliding window: last 10 (blockHeight, timestamp) samples

blocksPerSecond = (latestHeight - oldestSampleHeight) / (latestTime - oldestSampleTime)
remainingBlocks = tipHeight - currentHeight
etaSeconds = remainingBlocks / blocksPerSecond
percentage = (currentHeight - startHeight) / (tipHeight - startHeight) * 100
```

Handles edge cases:
- `blocksPerSecond <= 0` → show "Calculating..." instead of ETA
- `tipHeight == currentHeight` → synced, no ETA needed
- Fewer than 2 samples → show "Starting sync..."

### Permissions

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

`POST_NOTIFICATIONS` requires runtime request on API 33+. Show rationale: "Pocket Node needs notification permission to show sync progress in the background."

### Dependencies

None new. Uses Android's built-in `Service`, `NotificationManager`, `NotificationCompat`.

### Key Consideration

The JNI light client runs in the app process, not bound to the service. The service's role is to keep the process alive via `startForeground()`. If the process is killed and restarted by `START_STICKY`, the service must call back into `GatewayRepository` to re-initialize the light client. `GatewayRepository` already handles idempotent init.

---

## Implementation Order

1. **Issue #79 first** — merge main into M3, reconcile bugfixes. This is the foundation.
2. **Issue #78 second** — background sync service. This is a core UX improvement.
3. **In-app update last** — independent feature, no dependency on the others.

---

## Out of Scope

- Play Store update mechanism
- Delta/patch updates
- Auto-install without user confirmation
- Background sync across device reboots (would need `RECEIVE_BOOT_COMPLETED`)
- Update rollback
