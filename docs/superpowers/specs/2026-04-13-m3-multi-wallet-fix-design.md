# M3 Multi-Wallet Fix & UX Overhaul — Design Spec

**Date**: 2026-04-13
**Branch**: `feature/m3-multi-wallet`
**Status**: Draft

## Problem Statement

The M3 multi-wallet feature was originally merged (PR #76, commit `0bcae9a`) then reverted (`6a6185d`). When the feature branch was rebuilt, critical data isolation code, wallet switching logic, and several complete features were lost. The current state has:

1. **Data corruption risk** — DAO queries ignore `walletId`, CacheManager doesn't thread `walletId`, `BalanceCacheEntity` PK can't store per-wallet balances
2. **Broken wallet switching** — WalletManagerViewModel doesn't reinitialize the light client, global KeyManager used instead of wallet-scoped
3. **Missing spec features** — Paging 3, CSV export, WAL mode, VACUUM, DatabaseMaintenanceUtil, HD sub-accounts, wallet-scoped preferences
4. **UX gaps** — no mnemonic backup for new wallets, no loading states, no visual wallet identity, no per-wallet sync status, dropdown-based switcher instead of proper bottom sheet

## Approach

**Hybrid (Approach C)**: Restore the data layer from the original M3 merge commit `0bcae9a` (surgically merged with post-M3 security code). Redesign the UI layer with an imToken-style hierarchical bottom sheet pattern and new UX improvements.

Two phases:
- **Phase 1**: Restore lost M3 functionality + fix critical bugs (data foundation)
- **Phase 2**: UX improvements (imToken-style UI, sync strategy settings, wallet identity, loading states)

## Decisions Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Sync strategy | All-wallets sync (default), with Active Only and Balanced as options | Light client overhead for 3 scripts is ~5-10% more CPU/bandwidth; headers are shared |
| Wallet sync cap | Soft cap at 3 wallets | 4+ wallets allowed but warned they only sync when active |
| Sync strategy selection | User-configurable in Settings | Gives user control over battery/freshness tradeoff |
| Mnemonic backup for new wallets | Show + acknowledge (no quiz) | User proved understanding during onboarding; forced verification on every wallet is patronizing |
| Wallet visual identity | Avatar initials + auto-assigned color | Industry standard (MetaMask, Trust Wallet, Rabby); `colorIndex` field on WalletEntity |
| Wallet switcher UI | imToken-style ModalBottomSheet | Hierarchical grouping (parent wallet → sub-accounts), more spacious than dropdown |
| 4th+ wallet behavior | Soft cap — allow creation, warn about sync limitation | Doesn't punish users who want more wallets |
| Restoration approach | Restore data layer from 0bcae9a, reuse existing UI features from 0bcae9a where applicable, build new UI for imToken-style flow | Maximum code reuse for proven data layer; fresh design for improved UX |

---

## Phase 1: Restore & Fix

### 1A. Data Layer Restoration

All restored from commit `0bcae9a`, surgically merged with post-M3 code.

#### Entities

**TransactionEntity** — add composite index, add `walletId` param to `fromTransactionRecord()`:
```kotlin
@Entity(
    tableName = "transactions",
    indices = [Index("walletId", "network", "timestamp")]
)
```

**BalanceCacheEntity** — change PK from `network` to composite `(walletId, network)`:
```kotlin
@Entity(
    tableName = "balance_cache",
    primaryKeys = ["walletId", "network"]
)
data class BalanceCacheEntity(
    val walletId: String,
    val network: String,
    // ... balance fields
)
```

**DaoCellEntity** — add composite index `(walletId, network)`:
```kotlin
@Entity(
    tableName = "dao_cells",
    indices = [Index("walletId", "network")]
)
```

#### DAOs — Wallet-Scoped Queries

**TransactionDao** — add 5 queries:
- `getByWalletAndNetwork(walletId, network, limit)` — filtered by wallet+network
- `getAllByWalletAndNetwork(walletId, network)` — no limit, for CSV export
- `getPendingByWallet(walletId, network)` — pending transactions by wallet
- `deleteByWalletAndNetwork(walletId, network)` — wallet-scoped delete
- `getTransactionsPaged(walletId, network)` — returns `PagingSource<Int, TransactionEntity>`

**BalanceCacheDao** — add 2 queries:
- `getByWalletAndNetwork(walletId, network)` — returns `BalanceCacheEntity?`
- `deleteByWalletAndNetwork(walletId, network)`

**DaoCellDao** — add 3 queries:
- `getActiveByWalletAndNetwork(walletId, network)`
- `getCompletedByWalletAndNetwork(walletId, network)`
- `deleteByWalletAndNetwork(walletId, network)`

**WalletDao** — restore Flow-based API from original M3:
- `getActiveWallet(): Flow<WalletEntity?>` (replaces `getActive()`)
- `getAllWallets(): Flow<List<WalletEntity>>` (replaces `getAllFlow()`)
- `getSubAccounts(parentId): Flow<List<WalletEntity>>` — for hierarchical grouping
- `rename(walletId, name)` (replaces `updateName()`)
- `deactivateAll()` scoped to `WHERE isActive = 1` (more efficient)

#### Entity Factory Methods — walletId Threading

All entity factory/`from()` methods must accept and propagate `walletId`:

- `BalanceCacheEntity.from(response, network, walletId)` — currently defaults to `""`, must accept `walletId` parameter
- `TransactionEntity.fromTransactionRecord(record, network, walletId)` — restore from `0bcae9a`
- `DaoCellEntity` constructor calls in `DaoSyncManager.insertPendingDeposit()` — must include `walletId` in entity construction

`CacheManager` methods pass `walletId` through to these factory methods at every call site.

#### Migration Strategy

**CRITICAL**: `MIGRATION_2_3` already exists on the current branch and may have already run on user devices. The current migration adds `walletId` columns via ALTER TABLE but does NOT recreate `balance_cache` with composite PK. This creates a schema mismatch with the `BalanceCacheEntity` annotation (`primaryKeys = ["walletId", "network"]`).

**Two migration paths must be handled:**

**Path A — Fresh install or upgrade from v1 (never ran v2→v3):**
- `MIGRATION_2_3` runs with the corrected logic from `0bcae9a`: creates `wallets` table, ALTER TABLE for `transactions` and `dao_cells`, **recreates** `balance_cache` with composite PK

**Path B — Existing v3 users (already ran the broken v2→v3):**
- These users have `balance_cache` with `network` as sole PK and `walletId` as a regular column
- A new `MIGRATION_3_4` fixes this:

```sql
-- Recreate balance_cache with composite PK
CREATE TABLE balance_cache_new (
    walletId TEXT NOT NULL DEFAULT '',
    network TEXT NOT NULL,
    totalCapacity TEXT NOT NULL DEFAULT '0',
    availableBalance TEXT NOT NULL DEFAULT '0',
    occupiedCapacity TEXT NOT NULL DEFAULT '0',
    daoDeposit TEXT NOT NULL DEFAULT '0',
    daoCompensation TEXT NOT NULL DEFAULT '0',
    lastUpdated INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (walletId, network)
);
INSERT INTO balance_cache_new SELECT walletId, network, totalCapacity, availableBalance, occupiedCapacity, daoDeposit, daoCompensation, lastUpdated FROM balance_cache;
DROP TABLE balance_cache;
ALTER TABLE balance_cache_new RENAME TO balance_cache;

-- Add Phase 2 columns to wallets
ALTER TABLE wallets ADD COLUMN lastActiveAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE wallets ADD COLUMN colorIndex INTEGER NOT NULL DEFAULT 0;
```

**Database version**: Bump to v4. AppModule adds both `MIGRATION_2_3` (corrected) and `MIGRATION_3_4` to the builder. Room handles routing: fresh v2 users get v2→v3→v4, existing v3 users get v3→v4.

**Corrected MIGRATION_2_3** (from `0bcae9a`):
- Creates `wallets` table
- ALTER TABLE for `transactions` and `dao_cells` to add `walletId` column + indices
- **Recreates** `balance_cache` table with composite PK `(walletId, network)` (SQLite can't ALTER PKs — must create new table, copy data, drop old, rename)

#### CacheManager — Restore `walletId` Threading

Add `walletId: String` parameter to all 5 public methods:
- `getCachedBalance(network, walletId)`
- `cacheBalance(response, network, walletId)`
- `cacheTransactions(records, network, walletId)`
- `insertPendingTransaction(txHash, network, walletId)`
- `getPendingNotIn(network, excludeHashes, walletId)`

Each method calls the wallet-scoped DAO query internally.

#### DaoSyncManager — Restore `walletId` Threading

Add `walletId: String` parameter to 3 methods:
- `getActiveDeposits(network, walletId)`
- `getCompletedDeposits(network, walletId)`
- `insertPendingDeposit(txHash, capacity, network, walletId)`

#### WalletPreferences — Restore Wallet Scoping

Restore from original M3, merged with post-M3 theme/background-sync code:

- Add `walletNetworkKey(walletId, network, key)` helper for scoped pref keys
- Add `walletId: String?` parameter to: `getSyncMode()`, `setSyncMode()`, `getCustomBlockHeight()`, `setCustomBlockHeight()`, `hasCompletedInitialSync()`, `setInitialSyncCompleted()`, `getLastSyncedBlock()`, `setLastSyncedBlock()`
- Add `getActiveWalletId()` / `setActiveWalletId()`
- **Keep** post-M3 additions: `ThemeMode`, `themeModeFlow`, background sync toggle

#### WalletRepository — Restore + Keep Post-M3

Restore from original M3:
- Add `MnemonicManager` + `WalletPreferences` constructor deps
- Restore `createSubAccount()` for HD sub-accounts (derives key at `m/44'/309'/N'/0/0`)
- Restore `switchActiveWallet()` calling `walletPreferences.setActiveWalletId()`
- **Keep** HEAD's `deleteWallet()` calling `keyManager.deleteWalletKeys()` (cleans up KeyBackupManager backup files)

#### WalletMigrationHelper — Restore Backfill

Restore from original M3:
- Add `WalletPreferences` + `AppDatabase` constructor deps
- Restore `walletPreferences.setActiveWalletId(walletId)`
- **Backfill SQL** — after creating the WalletEntity, run raw SQL to associate existing cached data with the migrated wallet:

```sql
UPDATE transactions SET walletId = ? WHERE walletId = ''
UPDATE balance_cache SET walletId = ? WHERE walletId = ''
UPDATE dao_cells SET walletId = ? WHERE walletId = ''
```

Where `?` is the newly generated `walletId`. This is critical: without backfill, wallet-scoped queries (`WHERE walletId = :walletId`) return zero rows for the migrated wallet, causing empty balance/transaction history after upgrade.

The `AppDatabase` dep is needed to get `database.openHelper.writableDatabase` for raw SQL execution.

#### AppModule — Merge

- Add WAL journal mode: `.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)`
- Update `provideWalletMigrationHelper()` with 4 params
- Update `provideWalletRepository()` with 4 params
- **Keep** post-M3: `provideKeyBackupManager()`

#### Build Dependencies — Restore Paging 3

Add to `libs.versions.toml`:
```toml
paging = "3.3.6"

room-paging = { group = "androidx.room", name = "room-paging", version.ref = "room" }
androidx-paging-runtime = { group = "androidx.paging", name = "paging-runtime", version.ref = "paging" }
androidx-paging-compose = { group = "androidx.paging", name = "paging-compose", version.ref = "paging" }
```

Add to `build.gradle.kts`:
```kotlin
implementation(libs.room.paging)
implementation(libs.androidx.paging.runtime)
implementation(libs.androidx.paging.compose)
```

---

### 1B. Wallet Switching & Multi-Script Registration

#### GatewayRepository — Restore + Enhance

Restore from original M3:
- `activeWalletId: String` field, initialized from `walletPreferences.getActiveWalletId()`
- Thread `walletId = activeWalletId` through all 6 CacheManager/DaoSyncManager call sites
- Run `walletMigrationHelper.migrateIfNeeded()` in init before `initializeNode()`

**Fix from original M3**: `onActiveWalletChanged()` now reads from wallet-scoped prefs (not global KeyManager):

```kotlin
suspend fun onActiveWalletChanged(wallet: WalletEntity) {
    activeWalletId = wallet.walletId
    // Load from wallet-scoped prefs (fixes original M3 bug)
    val privateKey = keyManager.getPrivateKeyForWallet(wallet.walletId)
    val info = keyManager.deriveWalletInfo(privateKey)
    _walletInfo.value = info
    _isRegistered.value = false

    when (walletPreferences.getSyncStrategy()) {
        ALL_WALLETS -> registerAllWalletScripts()
        ACTIVE_ONLY -> registerAccount(
            syncMode = walletPreferences.getSyncMode(walletId = wallet.walletId)
        )
        BALANCED -> {
            registerAccount(...)
            scheduleInactiveSync()
        }
    }

    // Emit cached data immediately
    cacheManager.getCachedBalance(currentNetwork.name, walletId = activeWalletId)?.let {
        _balance.value = it
    }
}
```

#### Multi-Script Registration (new)

**Important**: `nativeSetScripts()` with the set-all command **replaces the entire script set**. This means:
- `ALL_WALLETS` path calls `registerAllWalletScripts()` which registers all 3 wallets' scripts in one call
- `ACTIVE_ONLY` path calls `registerAccount()` for only the active wallet — this deliberately **deregisters** all other wallets' scripts
- `BALANCED` path registers the active wallet immediately, then a background loop periodically adds/removes inactive wallet scripts

Register all wallets' lock scripts simultaneously via `nativeSetScripts()`:

```kotlin
suspend fun registerAllWalletScripts() {
    val wallets = walletDao.getAll()
        .sortedByDescending { it.lastActiveAt }
        .take(3)  // sync cap
    val scripts = wallets.map { wallet ->
        val key = keyManager.getPrivateKeyForWallet(wallet.walletId)
        // derive lock script: compressed pubkey -> blake2b -> args
        buildLockScriptJson(deriveArgs(key))
    }
    lightClientNative.nativeSetScripts(json.encodeToString(scripts))
}
```

#### Sync Strategy Enum (new)

```kotlin
enum class SyncStrategy { ACTIVE_ONLY, ALL_WALLETS, BALANCED }
```

Stored in `WalletPreferences`. Default: `ALL_WALLETS`.

Behavior per strategy:

| Strategy | Active wallet | Inactive wallets | Switch experience |
|----------|--------------|-----------------|-------------------|
| Active Only | Real-time (5s poll) | No sync | Delay while catching up |
| All Wallets | Real-time (5s poll) | Real-time (same) | Instant — data fresh |
| Balanced | Real-time (5s poll) | Every 15 min | Small delay, usually <15 min behind |

#### Soft Cap Logic (new)

On wallet creation, if `walletDao.count() >= 3` **before** creating the new wallet (i.e., the user already has 3 wallets and is about to create a 4th) and strategy is `ALL_WALLETS`:
- Show dialog: "You have 3+ wallets. Only 3 can sync simultaneously. This wallet will sync when active."
- The 3 wallets that get always-on sync are the 3 most recently active (by `lastActiveAt` timestamp)

#### Wire Through All ViewModels

- `WalletManagerViewModel.switchWallet()` → calls `gatewayRepository.onActiveWalletChanged(wallet)` (fixes current bug)
- `AddWalletViewModel` → calls `gatewayRepository.onActiveWalletChanged(wallet)` after create/import
- `HomeViewModel.switchWallet()` → calls `gatewayRepository.onActiveWalletChanged(wallet)` (replaces `initializeWallet()`)

---

### 1C. Feature Restoration

#### DatabaseMaintenanceUtil.kt — Restore as-is from `0bcae9a`

```kotlin
object DatabaseMaintenanceUtil {
    fun vacuum(db: SupportSQLiteDatabase)
    fun getDatabaseSizeBytes(db: SupportSQLiteDatabase): Long
}
```

- Called after `WalletRepository.deleteWallet()`
- Database size displayed in NodeStatus screen

#### TransactionExporter.kt — Restore as-is from `0bcae9a`

- Input: `List<TransactionEntity>`, network
- Output: CSV string (Date, TX Hash, Direction, Amount CKB, Fee, Status, Block Number)
- Formats shannons to CKB (8 decimal places)

#### CSV Export UI — Restore from `0bcae9a`

- `ActivityViewModel`: restore `TransactionDao` + `WalletPreferences` deps, `exportEvent` SharedFlow, `exportTransactions()`
- `ActivityScreen`: restore SAF `rememberLauncherForActivityResult`, export icon button, `LaunchedEffect` collecting `exportEvent`

#### Paging 3 — Restore from `0bcae9a`

- `TransactionDao.getTransactionsPaged(walletId, network)` returns `PagingSource<Int, TransactionEntity>`
- Wire into HomeViewModel with `Pager(PagingConfig(pageSize = 30, prefetchDistance = 10))`
- Replace flat transaction list with `LazyPagingItems` for infinite scroll

#### WalletEntity Addition (new)

Add two columns for Phase 2 support:

```kotlin
val lastActiveAt: Long = 0L       // updated on every wallet switch, for sync cap priority
val colorIndex: Int = 0            // 0-7, auto-assigned from preset palette
```

These are added via `MIGRATION_3_4` (see Migration Strategy section above — the ALTER TABLE statements are included alongside the `balance_cache` fixup).

#### Test Files — Restore from `0bcae9a`

**New files to create** (do not exist on HEAD):
1. `TransactionDaoWalletTest.kt` — wallet-scoped transaction query tests
2. `WalletDaoTest.kt` — CRUD, Flow queries, sub-account tests
3. `WalletEntityTest.kt` — entity construction tests
4. `TransactionExporterTest.kt` — CSV formatting tests
5. `WalletMigrationHelperTest.kt` — migration idempotency, mnemonic/raw_key paths
6. `KeyManagerMultiWalletTest.kt` — wallet-scoped ESP key isolation
7. `WalletPreferencesMultiWalletTest.kt` — wallet-scoped preference tests
8. `WalletRepositoryTest.kt` — end-to-end create/switch/delete/import/sub-account

**Existing files to merge** (already on HEAD, need M3 additions):
9. `MigrationTest.kt` — add v3 schema assertions for wallets table, composite PK, and v3→v4 fixup migration test
10. `BalanceCacheDaoTest.kt` — add wallet-scoped query tests alongside existing non-scoped tests

**Additional test to add:**
11. Migration integration test: v2 DB with real data → v3 migration → verify wallet-scoped queries return expected data (validates the backfill SQL)

Adapt all restored tests to current API where post-M3 changes differ (e.g., `deleteWalletKeys` vs `deleteKeysForWallet`).

---

### 1D. UI Wiring Fixes

#### WalletManagerViewModel — Restore from `0bcae9a`

- Add `GatewayRepository` constructor dep
- `switchWallet()` calls `walletRepository.switchActiveWallet()` then `gatewayRepository.onActiveWalletChanged(wallet)`

#### AddWalletScreen — Restore sub-account UI from `0bcae9a`

- 4th option card: "HD Sub-Account"
- `SubAccountForm` composable: parent wallet dropdown + name field + create button

#### AddWalletViewModel — Restore from `0bcae9a` + fix

- Add `GatewayRepository` constructor dep
- Restore `parentWallets`, `selectedParentId` in UiState
- Restore `selectParent()`, `createSubAccount()`
- All create/import methods call `gatewayRepository.onActiveWalletChanged(wallet)`

#### WalletDetailViewModel — Restore from `0bcae9a`

- Add `WalletDao` dep
- `hasMnemonic()` checks `parentWalletId == null` (sub-accounts shouldn't show parent's mnemonic)
- `cancelEditing()` resets `editName`

#### HomeViewModel Wallet Switch — Adapt

- `switchWallet()` calls `walletRepository.switchActiveWallet()` then `gatewayRepository.onActiveWalletChanged(wallet)`
- Remove redundant `initializeWallet()` — `onActiveWalletChanged()` handles everything
- Cached balance emitted immediately via wallet-scoped queries

#### WalletSwitcherDropdown — Restore as extracted component

- `ui/components/WalletSwitcherDropdown.kt` from `0bcae9a`
- Used temporarily until Phase 2 replaces it with `AccountSelectorSheet`

#### NavGraph — Careful merge

- **Keep** all post-M3 routes: Recovery, SecurityChecklist, MnemonicVerify
- **Keep** post-M3 navigation (re-auth flow, lifecycle-aware refresh)
- Ensure WalletManager/WalletDetail/AddWallet routes pass correct deps

---

## Phase 2: UX Improvements

### 2A. Sync Strategy Settings

New setting in the Settings tab:

```
Wallet Sync Strategy
├── Active Only      — "Only syncs the wallet you're using"
├── All Wallets      — "Keeps all wallets synced (up to 3)" [default]
└── Balanced         — "Active wallet real-time, others every 15 min"
```

Implementation:
- `SyncStrategy` enum in `WalletPreferences`
- Settings screen gets a "Sync Strategy" row opening a radio-button dialog
- `GatewayRepository.registerAllWalletScripts()` checks strategy
- Balanced: 15-min rotation loop through inactive wallets. Runs in `GatewayRepository.scope` (Dispatchers.IO). If the app process dies, the loop restarts on next app launch in `init`. For persistent background sync, the existing `SyncForegroundService` handles keeping the process alive — the Balanced loop piggybacks on that. **Known limitation**: `SyncForegroundService` is not yet wallet-aware; this is tracked for a future update.

#### Per-Wallet Sync Status

Each wallet card/row shows sync indicator:
- Green dot + "Synced" — current with chain tip
- Yellow dot + "Syncing..." — actively catching up
- Gray dot + "Paused" — not currently syncing (Active Only strategy, inactive wallet)

Tracked via per-wallet `lastSyncedBlock` in `WalletPreferences` (restored in Phase 1).

---

### 2B. imToken-Style Wallet UI

Replaces the dropdown-based wallet switcher with a hierarchical bottom sheet pattern.

#### Navigation Flow

```
Home (tap wallet name)
  └─> AccountSelectorSheet (bottom sheet)
        ├─ Tap account → switch wallet, dismiss sheet
        └─ Tap "Manage"
              └─> ManageWalletsScreen (wallet group cards)
                    ├─ Tap wallet group → WalletSettingsScreen
                    │     ├─ Wallet info (name, source, derivation, backup status)
                    │     ├─ View seed phrase (auth-gated)
                    │     ├─ Accounts list with "Add account" (derives next HD sub-account)
                    │     └─ Remove wallet
                    ├─ Tap "Add" on group → derives next sub-account inline
                    └─ Tap "+ Add Wallet" → AddWalletScreen (new/import)
```

#### Screen 1: Home Screen Top Bar

```
[P] wallet                    ● Synced
    Account 01  ▾
```

- Avatar initial + color (from `colorIndex`) replaces the wallet icon
- "wallet" label above, active account name below with chevron
- Sync status chip on the right
- Tapping opens `AccountSelectorSheet` (ModalBottomSheet)

#### Screen 2: AccountSelectorSheet (ModalBottomSheet)

```
─────────────────────────────
  Select account        Manage
─────────────────────────────
  📁 Primary Wallet          ▲
  ┌──────────────────────────┐
  │ [P] Account 01           │
  │     ckb1qzda...7x9f3     │
  │                1,245 CKB ✓│
  └──────────────────────────┘
  ┌──────────────────────────┐
  │ [P] Account 02           │
  │     ckb1qrfm...3k2p      │
  │                  350 CKB │
  └──────────────────────────┘

  📁 Trading                  ▲
  ┌──────────────────────────┐
  │ [T] Account 01           │
  │     ckb1qp5u...9m7w      │
  │                  500 CKB │
  └──────────────────────────┘
```

- Wallets grouped by parent (wallets where `parentWalletId == null`)
- Sub-accounts nested under each group (wallets where `parentWalletId == parent's walletId`)
- Each account shows: avatar, name, truncated address, balance, sync dot
- Active account highlighted with colored border + checkmark
- Groups are collapsible (accordion)
- "Manage" link top right navigates to ManageWalletsScreen

#### Screen 3: ManageWalletsScreen

```
  ← Manage Wallets
─────────────────────────────
  ┌──────────────────────────┐
  │ 📁 Primary Wallet        │
  │    2 accounts         ›  │
  │    [P] [P]          Add  │
  └──────────────────────────┘
  ┌──────────────────────────┐
  │ 📁 Trading               │
  │    1 account          ›  │
  │    [T]              Add  │
  └──────────────────────────┘

       [+ Add Wallet]
```

- Wallet groups as cards (not individual accounts)
- Shows account count + small avatar dots
- "Add" link derives next HD sub-account (disabled for raw_key wallets)
- Chevron navigates to WalletSettingsScreen
- "+ Add Wallet" button creates a new independent wallet

#### Screen 4: WalletSettingsScreen (replaces WalletDetailScreen)

```
  ← Wallet Settings    Remove
─────────────────────────────
         📁
    Primary Wallet ✏️
─────────────────────────────
  Source         Seed Phrase
  Derivation     m/44'/309'/0'/0/0
─────────────────────────────
  Backup wallet    Completed ›
  View seed phrase           ›
─────────────────────────────
  Accounts        Add account

  ≡ [P] Account 01           ›
       ckb1qzda...7x9f3

  ≡ [P] Account 02           ›
       ckb1qrfm...3k2p
```

- Wallet-level settings: name (editable), source type, derivation path, backup status, view seed phrase
- Accounts section at bottom: lists all sub-accounts, "Add account" derives next HD index
- Drag handles (≡) for reordering (future)
- Tapping an account row goes to AccountDetailScreen (address display, rename, per-account address QR)
- "Remove" in top right with safety checks (see 2D)

#### Data Model

No new entities needed. The hierarchy uses existing `WalletEntity` fields:
- **Wallet group** = `WalletEntity` where `parentWalletId == null`
- **Sub-account** = `WalletEntity` where `parentWalletId == <parent's walletId>`
- `WalletDao.getSubAccounts(parentId)` already exists in original M3

#### New Composables

| File | Replaces | Purpose |
|------|----------|---------|
| `ui/components/AccountSelectorSheet.kt` | `WalletSwitcherDropdown.kt` | Bottom sheet with hierarchical wallet/account list |
| `ui/screens/wallet/WalletSettingsScreen.kt` | `WalletDetailScreen.kt` | Wallet detail + sub-account management |
| `ui/screens/wallet/WalletSettingsViewModel.kt` | `WalletDetailViewModel.kt` | State for wallet settings |

`WalletManagerScreen.kt` stays but is reworked to show wallet groups instead of flat list.

---

### 2C. Wallet Visual Identity

#### Avatar Initials + Color

Each wallet gets auto-assigned a color from a preset palette of 8:

```kotlin
val WALLET_COLORS = listOf(
    0xFF6366F1,  // Indigo
    0xFFF59E0B,  // Amber
    0xFF10B981,  // Emerald
    0xFFEF4444,  // Red
    0xFF0EA5E9,  // Sky
    0xFF8B5CF6,  // Violet
    0xFFEC4899,  // Pink
    0xFF14B8A6,  // Teal
)
```

- `colorIndex: Int` on `WalletEntity` (added in Phase 1C via `MIGRATION_3_4`)
- Auto-assigned at creation: `colorIndex = walletDao.count() % 8`
- User can change color in WalletSettingsScreen (future — not in initial implementation)
- Avatar composable: colored circle with first letter of wallet name, used everywhere (top bar, bottom sheet, manage screen, settings)

#### WalletAvatar Composable

```kotlin
@Composable
fun WalletAvatar(
    name: String,
    colorIndex: Int,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
)
```

Displays `name.firstOrNull()?.uppercase() ?: "?"` as the initial (handles empty name edge case).

Reused across: HomeScreen top bar, AccountSelectorSheet, ManageWalletsScreen, WalletSettingsScreen.

---

### 2D. Mnemonic Backup + Loading States + Delete Flow

#### Mnemonic Backup for New Wallets

After creating a new wallet via AddWalletScreen:
1. Navigate to `MnemonicBackupScreen` with `simplified = true` flag
2. Shows 12/24-word mnemonic grid with `FLAG_SECURE`
3. Single "I've saved my seed phrase" button (no 3-word quiz)
4. Tapping calls `keyManager.setMnemonicBackedUpForWallet(walletId, true)`, pops to Main
5. Pressing back without confirming: wallet created but `mnemonicBackedUp = false` — existing backup reminder banner handles the nudge

#### Loading States During Wallet Switch

1. **Immediate**: Show cached balance + transactions from Room (wallet-scoped after Phase 1)
2. **Overlay**: `LinearProgressIndicator` at top of screen — "Switching to [name]..."
3. **Background**: `onActiveWalletChanged()` re-registers scripts, fetches fresh data
4. **Complete**: Progress bar disappears, fresh data replaces cached

```kotlin
// HomeViewModel
fun switchWallet(walletId: String) {
    viewModelScope.launch {
        _uiState.update { it.copy(isSwitchingWallet = true) }
        walletRepository.switchActiveWallet(walletId)
        repository.onActiveWalletChanged(wallet)
        _uiState.update { it.copy(isSwitchingWallet = false) }
    }
}
```

#### Delete Wallet Flow

| Scenario | Behavior |
|----------|----------|
| Delete non-active wallet | Confirm dialog → delete → VACUUM |
| Delete active wallet | Dialog: "Switch to another wallet first" with "Switch Wallet" button |
| Delete last wallet | Blocked: "This is your only wallet. Create another before deleting." |
| After any deletion | Run `DatabaseMaintenanceUtil.vacuum()`, update script registration |

---

## Files Summary

### Phase 1: Files to Create (from `0bcae9a`)

| File | Source |
|------|--------|
| `data/database/DatabaseMaintenanceUtil.kt` | Restore from `0bcae9a` |
| `data/export/TransactionExporter.kt` | Restore from `0bcae9a` |
| `ui/components/WalletSwitcherDropdown.kt` | Restore from `0bcae9a` (temporary, replaced in Phase 2) |
| 8 new test files | Restore from `0bcae9a`, adapt to current API |
| 2 existing test files | Merge M3 additions into `MigrationTest.kt`, `BalanceCacheDaoTest.kt` |
| 1 new integration test | Migration path test: v2 → v3 → v4 with real data |

### Phase 1: Files to Modify (merge M3 into current)

| File | Changes |
|------|---------|
| `TransactionEntity.kt` | Composite index, `walletId` in factory method |
| `BalanceCacheEntity.kt` | Composite PK `(walletId, network)` |
| `DaoCellEntity.kt` | Composite index |
| `WalletEntity.kt` | Add `lastActiveAt`, `colorIndex` columns |
| `TransactionDao.kt` | 5 wallet-scoped queries + PagingSource |
| `BalanceCacheDao.kt` | 2 wallet-scoped queries |
| `DaoCellDao.kt` | 3 wallet-scoped queries |
| `WalletDao.kt` | Flow-based API, `getSubAccounts()`, `rename()` |
| `Migrations.kt` | Fix MIGRATION_2_3 (recreate balance_cache with composite PK), add MIGRATION_3_4 (balance_cache fixup for existing v3 users + `lastActiveAt`/`colorIndex` columns) |
| `CacheManager.kt` | `walletId` param on 5 methods |
| `DaoSyncManager.kt` | `walletId` param on 3 methods |
| `GatewayRepository.kt` | `activeWalletId`, `onActiveWalletChanged()`, multi-script registration, sync strategy |
| `WalletPreferences.kt` | Wallet-scoped prefs, `SyncStrategy` enum |
| `WalletRepository.kt` | `createSubAccount()`, `MnemonicManager` dep |
| `WalletMigrationHelper.kt` | Backfill SQL, `WalletPreferences` dep |
| `KeyManager.kt` | Nullable `getPrivateKeyForWallet()` return |
| `AppModule.kt` | WAL mode, updated providers |
| `build.gradle.kts` | Paging 3 deps |
| `libs.versions.toml` | Paging version + libraries |
| `HomeViewModel.kt` | Wallet switch via `onActiveWalletChanged()` |
| `HomeScreen.kt` | Use extracted WalletSwitcherDropdown |
| `ActivityScreen.kt` | CSV export UI |
| `ActivityViewModel.kt` | Export logic |
| `WalletManagerViewModel.kt` | `GatewayRepository` dep, `onActiveWalletChanged()` |
| `AddWalletScreen.kt` | Sub-account option |
| `AddWalletViewModel.kt` | `GatewayRepository` dep, sub-account logic |
| `WalletDetailViewModel.kt` | `WalletDao` dep, `parentWalletId` check |
| `NavGraph.kt` | Keep post-M3 routes, ensure wallet routes correct |

### Phase 2: Files to Create (new)

| File | Purpose |
|------|---------|
| `ui/components/AccountSelectorSheet.kt` | imToken-style bottom sheet with hierarchical wallet/account list |
| `ui/components/WalletAvatar.kt` | Reusable avatar composable (initial + color) |
| `ui/screens/wallet/WalletSettingsScreen.kt` | Wallet detail + sub-account management (replaces WalletDetailScreen) |
| `ui/screens/wallet/WalletSettingsViewModel.kt` | State for wallet settings |

### Phase 2: Files to Modify

| File | Changes |
|------|---------|
| `HomeScreen.kt` | Replace dropdown with `AccountSelectorSheet`, avatar in top bar, loading state |
| `HomeViewModel.kt` | `isSwitchingWallet` state, sync status per wallet |
| `WalletManagerScreen.kt` | Rework to show wallet groups (not flat list) |
| `WalletManagerViewModel.kt` | Group wallets by parent |
| `AddWalletScreen.kt` | Mnemonic backup navigation after creation |
| `MnemonicBackupScreen.kt` | Add `simplified` flag (skip quiz) |
| `NavGraph.kt` | Replace WalletDetail route with WalletSettings route |
| `SettingsScreen.kt` | Sync Strategy row + dialog |
| `NodeStatusScreen.kt` | Database size display |

---

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Two migration paths (fresh v2→v3→v4 vs existing v3→v4) | Dedicated integration test for each path with real data; MIGRATION_3_4 is idempotent (recreate balance_cache is safe even if PK is already correct) |
| WalletMigrationHelper backfill SQL on empty walletId | Run only when `walletDao.count() == 0` (first migration); backfill uses `WHERE walletId = ''` which is safe if no empty IDs exist |
| Multi-script registration latency | Cache-first display; scripts registered in background |
| HD sub-account address verification | Compare with Neuron wallet for same mnemonic at different account indices |
| Balanced sync strategy timing | 15-min interval is conservative; can tune based on user feedback |
| Balanced sync loop and process death | Loop restarts in GatewayRepository.init; SyncForegroundService keeps process alive for background mode |
| SyncForegroundService not wallet-aware | Known limitation — tracked for future update; does not block M3 functionality |
| Bottom sheet + hierarchical grouping complexity | Compose ModalBottomSheet is well-supported; hierarchy maps directly to existing `parentWalletId` field |

## Success Criteria

- Switching wallets shows correct balance, address, and transaction history for the selected wallet
- Creating 3 wallets with "All Wallets" sync strategy keeps all 3 synced simultaneously
- Creating a 4th wallet shows soft cap warning
- Bottom sheet account selector groups wallets with sub-accounts nested correctly
- CSV export produces valid CSV with correct per-wallet transaction data
- All 8 restored test files pass
- VACUUM runs after wallet deletion, database size visible in NodeStatus
