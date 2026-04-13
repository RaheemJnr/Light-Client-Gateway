# M3 Multi-Wallet Fix & UX Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix critical multi-wallet data isolation bugs, restore lost M3 features from reverted merge (commit `0bcae9a`), and redesign the wallet UI with an imToken-style hierarchical bottom sheet pattern.

**Architecture:** Hybrid restoration — surgically merge proven data layer code from the original M3 merge into the current branch (preserving post-M3 security features), then build new UI components for the imToken-style wallet experience. Two phases: Phase 1 fixes the foundation (data isolation, wallet switching, missing features), Phase 2 adds UX improvements (bottom sheet, sync strategy, visual identity).

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (Material 3), Room 2.8.4, Hilt 2.57.2, Jetpack Paging 3.3.6, CKB light client (JNI)

**Spec:** `docs/superpowers/specs/2026-04-13-m3-multi-wallet-fix-design.md`

**Source for restoration:** Git commit `0bcae9a` (original M3 merge). Extract files with: `git show 0bcae9a:<path>`

**Branch:** `feature/m3-multi-wallet`

**Test command:** `cd android && ./gradlew testDebugUnitTest`

---

## Chunk 1: Data Layer Foundation (Phase 1A)

### Task 1: Fix Database Entities

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/BalanceCacheEntity.kt` (39 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/TransactionEntity.kt` (76 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/DaoCellEntity.kt` (34 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/WalletEntity.kt` (19 lines)

- [ ] **Step 1: Extract original entity code from `0bcae9a`**

Run:
```bash
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/BalanceCacheEntity.kt > /tmp/m3-BalanceCacheEntity.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/TransactionEntity.kt > /tmp/m3-TransactionEntity.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/DaoCellEntity.kt > /tmp/m3-DaoCellEntity.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/WalletEntity.kt > /tmp/m3-WalletEntity.kt
```

- [ ] **Step 2: Fix BalanceCacheEntity — composite PK**

Change from single `@PrimaryKey val network` to composite PK:
```kotlin
@Entity(
    tableName = "balance_cache",
    primaryKeys = ["walletId", "network"]
)
data class BalanceCacheEntity(
    val walletId: String,
    val network: String,
    // ... keep existing balance fields
)
```

Update the `from()` factory to accept `walletId`:
```kotlin
companion object {
    fun from(response: BalanceResponse, network: String, walletId: String): BalanceCacheEntity =
        BalanceCacheEntity(
            walletId = walletId,
            network = network,
            address = response.address,
            capacity = response.capacity,
            capacityCkb = response.capacityCkb,
            blockNumber = response.asOfBlock,
            cachedAt = System.currentTimeMillis()
        )
}
```

Remove `@ColumnInfo(defaultValue = "")` from `walletId` — the composite PK handles it.

- [ ] **Step 3: Fix TransactionEntity — walletId in factory**

**Note:** TransactionEntity already has the composite index `idx_tx_wallet_network_time` on HEAD. Only two changes needed:

1. Remove `@ColumnInfo(defaultValue = "")` from `walletId`
2. Add `walletId: String = ""` parameter to `fromTransactionRecord()` and pass it through to entity construction. The current factory takes ~10 individual parameters — append `walletId` as the last one.

- [ ] **Step 4: Fix DaoCellEntity — remove ColumnInfo default**

**Note:** DaoCellEntity already has the composite index `idx_dao_wallet_network` on HEAD. Only change needed:

Remove `@ColumnInfo(defaultValue = "")` from `walletId`.

- [ ] **Step 5: Add `lastActiveAt` and `colorIndex` to WalletEntity**

```kotlin
@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey val walletId: String,
    val name: String,
    val type: String,
    val derivationPath: String?,
    val parentWalletId: String?,
    val accountIndex: Int = 0,
    val mainnetAddress: String = "",
    val testnetAddress: String = "",
    val isActive: Boolean = false,
    val createdAt: Long = 0L,
    val lastActiveAt: Long = 0L,
    val colorIndex: Int = 0
)
```

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/
git commit -m "fix: entity composite PKs, indices, and walletId factory params"
```

---

### Task 2: Fix Database Migrations

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/Migrations.kt` (103 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/AppDatabase.kt` (33 lines)

- [ ] **Step 1: Extract original migration from `0bcae9a`**

```bash
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/Migrations.kt > /tmp/m3-Migrations.kt
```

Compare: `git diff HEAD 0bcae9a -- android/app/src/main/java/com/rjnr/pocketnode/data/database/Migrations.kt`

- [ ] **Step 2: Fix MIGRATION_2_3 — recreate balance_cache with composite PK**

Replace the current `MIGRATION_2_3` to match `0bcae9a`: the key difference is that `balance_cache` must be **recreated** (not just ALTER TABLE) because SQLite can't change a primary key:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create wallets table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS wallets (
                walletId TEXT NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                derivationPath TEXT,
                parentWalletId TEXT,
                accountIndex INTEGER NOT NULL DEFAULT 0,
                mainnetAddress TEXT NOT NULL DEFAULT '',
                testnetAddress TEXT NOT NULL DEFAULT '',
                isActive INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(walletId)
            )
        """)

        // 2. Add walletId to transactions
        db.execSQL("ALTER TABLE transactions ADD COLUMN walletId TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_wallet_network_time ON transactions(walletId, network, timestamp DESC)")

        // 3. Recreate balance_cache with composite PK
        // IMPORTANT: column names must match current BalanceCacheEntity exactly
        db.execSQL("""
            CREATE TABLE balance_cache_new (
                walletId TEXT NOT NULL DEFAULT '',
                network TEXT NOT NULL,
                address TEXT NOT NULL DEFAULT '',
                capacity TEXT NOT NULL DEFAULT '0',
                capacityCkb TEXT NOT NULL DEFAULT '0',
                blockNumber TEXT NOT NULL DEFAULT '0',
                cachedAt INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(walletId, network)
            )
        """)
        db.execSQL("INSERT INTO balance_cache_new SELECT '', network, address, capacity, capacityCkb, blockNumber, cachedAt FROM balance_cache")
        db.execSQL("DROP TABLE balance_cache")
        db.execSQL("ALTER TABLE balance_cache_new RENAME TO balance_cache")

        // 4. Add walletId to dao_cells
        db.execSQL("ALTER TABLE dao_cells ADD COLUMN walletId TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dao_wallet_network ON dao_cells(walletId, network)")

        // 5. Index on header_cache
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_header_network_number ON header_cache(network, number)")
    }
}
```

**Note:** Verify exact `balance_cache` column names match the current entity by reading `BalanceCacheEntity.kt`.

- [ ] **Step 3: Add MIGRATION_3_4 — fixup for existing v3 users**

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Fix balance_cache PK for users who ran broken v2->v3
        // Column names must match current BalanceCacheEntity
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS balance_cache_new (
                walletId TEXT NOT NULL DEFAULT '',
                network TEXT NOT NULL,
                address TEXT NOT NULL DEFAULT '',
                capacity TEXT NOT NULL DEFAULT '0',
                capacityCkb TEXT NOT NULL DEFAULT '0',
                blockNumber TEXT NOT NULL DEFAULT '0',
                cachedAt INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(walletId, network)
            )
        """)
        db.execSQL("INSERT OR IGNORE INTO balance_cache_new SELECT walletId, network, address, capacity, capacityCkb, blockNumber, cachedAt FROM balance_cache")
        db.execSQL("DROP TABLE balance_cache")
        db.execSQL("ALTER TABLE balance_cache_new RENAME TO balance_cache")

        // Add Phase 2 columns to wallets
        db.execSQL("ALTER TABLE wallets ADD COLUMN lastActiveAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE wallets ADD COLUMN colorIndex INTEGER NOT NULL DEFAULT 0")
    }
}
```

- [ ] **Step 4: Update AppDatabase — version 4, add migration, WAL mode**

In `AppDatabase.kt`: bump `version = 4`, add `WalletEntity` to entities list if not already present.

In `AppModule.kt` `provideAppDatabase()`:
```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "pocket_node.db")
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    .build()
```

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/Migrations.kt
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/AppDatabase.kt
git add android/app/src/main/java/com/rjnr/pocketnode/di/AppModule.kt
git commit -m "fix: restore MIGRATION_2_3 with composite PK, add MIGRATION_3_4 fixup, enable WAL"
```

---

### Task 3: Restore Wallet-Scoped DAO Queries

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/TransactionDao.kt` (32 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/BalanceCacheDao.kt` (20 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/DaoCellDao.kt` (32 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/WalletDao.kt` (42 lines)

- [ ] **Step 1: Extract original DAOs from `0bcae9a`**

```bash
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/TransactionDao.kt > /tmp/m3-TransactionDao.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/BalanceCacheDao.kt > /tmp/m3-BalanceCacheDao.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/DaoCellDao.kt > /tmp/m3-DaoCellDao.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/WalletDao.kt > /tmp/m3-WalletDao.kt
```

- [ ] **Step 2: Add wallet-scoped queries to TransactionDao**

Add these queries (keep existing network-only queries for backward compat):
```kotlin
@Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network ORDER BY CASE WHEN status = 'pending' THEN 0 ELSE 1 END, timestamp DESC LIMIT :limit")
suspend fun getByWalletAndNetwork(walletId: String, network: String, limit: Int = 50): List<TransactionEntity>

@Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network ORDER BY timestamp DESC")
suspend fun getAllByWalletAndNetwork(walletId: String, network: String): List<TransactionEntity>

@Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network AND status = 'pending'")
suspend fun getPendingByWallet(walletId: String, network: String): List<TransactionEntity>

@Query("DELETE FROM transactions WHERE walletId = :walletId AND network = :network")
suspend fun deleteByWalletAndNetwork(walletId: String, network: String)

@Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network ORDER BY timestamp DESC")
fun getTransactionsPaged(walletId: String, network: String): PagingSource<Int, TransactionEntity>
```

Add import: `import androidx.paging.PagingSource`

- [ ] **Step 3: Add wallet-scoped queries to BalanceCacheDao**

```kotlin
@Query("SELECT * FROM balance_cache WHERE walletId = :walletId AND network = :network LIMIT 1")
suspend fun getByWalletAndNetwork(walletId: String, network: String): BalanceCacheEntity?

@Query("DELETE FROM balance_cache WHERE walletId = :walletId AND network = :network")
suspend fun deleteByWalletAndNetwork(walletId: String, network: String)
```

- [ ] **Step 4: Add wallet-scoped queries to DaoCellDao**

```kotlin
@Query("SELECT * FROM dao_cells WHERE walletId = :walletId AND network = :network AND status IN ('pending_deposit', 'active')")
suspend fun getActiveByWalletAndNetwork(walletId: String, network: String): List<DaoCellEntity>

@Query("SELECT * FROM dao_cells WHERE walletId = :walletId AND network = :network AND status = 'withdrawn'")
suspend fun getCompletedByWalletAndNetwork(walletId: String, network: String): List<DaoCellEntity>

@Query("DELETE FROM dao_cells WHERE walletId = :walletId AND network = :network")
suspend fun deleteByWalletAndNetwork(walletId: String, network: String)
```

- [ ] **Step 5: Update WalletDao — Flow-based API + getSubAccounts**

Merge with original M3. Key additions:
```kotlin
@Query("SELECT * FROM wallets WHERE isActive = 1 LIMIT 1")
fun getActiveWallet(): Flow<WalletEntity?>

@Query("SELECT * FROM wallets ORDER BY createdAt ASC")
fun getAllWallets(): Flow<List<WalletEntity>>

@Query("SELECT * FROM wallets WHERE parentWalletId = :parentId ORDER BY accountIndex ASC")
fun getSubAccounts(parentId: String): Flow<List<WalletEntity>>

@Query("UPDATE wallets SET name = :name WHERE walletId = :walletId")
suspend fun rename(walletId: String, name: String)

@Query("UPDATE wallets SET lastActiveAt = :timestamp WHERE walletId = :walletId")
suspend fun updateLastActiveAt(walletId: String, timestamp: Long)
```

Keep existing `getActive()` suspend function alongside the new Flow-based `getActiveWallet()` — both are used in different contexts.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/
git commit -m "feat: restore wallet-scoped DAO queries from original M3"
```

---

### Task 4: Restore CacheManager & DaoSyncManager walletId Threading

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/CacheManager.kt` (125 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/DaoSyncManager.kt` (164 lines)

- [ ] **Step 1: Extract originals from `0bcae9a`**

```bash
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/gateway/CacheManager.kt > /tmp/m3-CacheManager.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/gateway/DaoSyncManager.kt > /tmp/m3-DaoSyncManager.kt
```

- [ ] **Step 2: Add `walletId` parameter to CacheManager methods**

Update all 5 public methods to accept `walletId: String`:
- `getCachedBalance(network, walletId)` → calls `balanceCacheDao.getByWalletAndNetwork(walletId, network)`
- `cacheBalance(response, network, walletId)` → passes `walletId` to `BalanceCacheEntity.from(response, network, walletId)`
- `cacheTransactions(records, network, walletId)` → passes `walletId` to `TransactionEntity.fromTransactionRecord(record, network, walletId)`
- `insertPendingTransaction(txHash, network, walletId)` → includes `walletId` in entity construction
- `getPendingNotIn(network, excludeHashes, walletId)` → calls `transactionDao.getPendingByWallet(walletId, network)` then filters

- [ ] **Step 3: Add `walletId` parameter to DaoSyncManager methods**

Update 3 methods:
- `getActiveDeposits(network, walletId)` → calls `daoCellDao.getActiveByWalletAndNetwork(walletId, network)`
- `getCompletedDeposits(network, walletId)` → calls `daoCellDao.getCompletedByWalletAndNetwork(walletId, network)`
- `insertPendingDeposit(txHash, capacity, network, walletId)` → includes `walletId` in `DaoCellEntity` construction

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/CacheManager.kt
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/DaoSyncManager.kt
git commit -m "feat: thread walletId through CacheManager and DaoSyncManager"
```

---

### Task 5: Restore WalletPreferences Wallet Scoping

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/wallet/WalletPreferences.kt` (202 lines)

- [ ] **Step 1: Extract original from `0bcae9a`**

```bash
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/wallet/WalletPreferences.kt > /tmp/m3-WalletPreferences.kt
```

Compare: `diff /tmp/m3-WalletPreferences.kt android/app/src/main/java/com/rjnr/pocketnode/data/wallet/WalletPreferences.kt`

- [ ] **Step 2: Add wallet-scoped helper and SyncStrategy enum**

```kotlin
enum class SyncStrategy { ACTIVE_ONLY, ALL_WALLETS, BALANCED }

// Helper for wallet+network scoped preference keys
private fun walletNetworkKey(walletId: String, network: String, key: String): String =
    "${walletId}_${network}_$key"

companion object {
    // ... existing keys ...
    private const val KEY_ACTIVE_WALLET_ID = "active_wallet_id"
    private const val KEY_SYNC_STRATEGY = "sync_strategy"
}
```

- [ ] **Step 3: Add wallet-scoped getter/setter methods**

Add `walletId: String? = null` parameter to these methods. When `walletId` is non-null, use `walletNetworkKey()` for the pref key; when null, use the existing global key (backward compat):

- `getSyncMode(walletId?)` / `setSyncMode(mode, walletId?)`
- `getCustomBlockHeight(walletId?)` / `setCustomBlockHeight(height, walletId?)`
- `hasCompletedInitialSync(walletId?)` / `setInitialSyncCompleted(completed, walletId?)`
- `getLastSyncedBlock(walletId?)` / `setLastSyncedBlock(block, walletId?)`

Add new methods:
```kotlin
fun getActiveWalletId(): String? = prefs.getString(KEY_ACTIVE_WALLET_ID, null)
fun setActiveWalletId(walletId: String) = prefs.edit().putString(KEY_ACTIVE_WALLET_ID, walletId).apply()

fun getSyncStrategy(): SyncStrategy {
    val name = prefs.getString(KEY_SYNC_STRATEGY, SyncStrategy.ALL_WALLETS.name)
    return SyncStrategy.valueOf(name ?: SyncStrategy.ALL_WALLETS.name)
}
fun setSyncStrategy(strategy: SyncStrategy) = prefs.edit().putString(KEY_SYNC_STRATEGY, strategy.name).apply()
```

**Keep** all post-M3 additions: `ThemeMode`, `themeModeFlow`, `isBackgroundSyncEnabled`, `setBackgroundSyncEnabled`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/wallet/WalletPreferences.kt
git commit -m "feat: restore wallet-scoped preferences and add SyncStrategy"
```

---

### Task 6: Restore WalletRepository & WalletMigrationHelper

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/wallet/WalletRepository.kt` (150 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/migration/WalletMigrationHelper.kt` (69 lines)

- [ ] **Step 1: Extract originals from `0bcae9a`**

```bash
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/wallet/WalletRepository.kt > /tmp/m3-WalletRepository.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/migration/WalletMigrationHelper.kt > /tmp/m3-WalletMigrationHelper.kt
```

- [ ] **Step 2: Update WalletRepository**

Merge from `0bcae9a`:
- Add `MnemonicManager` and `WalletPreferences` constructor deps
- Restore `createSubAccount(parentWalletId, name)` — reads parent mnemonic, derives key at `m/44'/309'/N'/0/0` where N = next accountIndex
- Change `switchWallet()` to `switchActiveWallet()` — also calls `walletPreferences.setActiveWalletId(walletId)` and `walletDao.updateLastActiveAt(walletId, System.currentTimeMillis())`
- Auto-assign `colorIndex` on creation: `colorIndex = walletDao.count() % 8`
- **Keep** HEAD's `deleteWallet()` which calls `keyManager.deleteWalletKeys()` (cleans up KeyBackupManager)

- [ ] **Step 3: Update WalletMigrationHelper**

Merge from `0bcae9a`:
- Add `WalletPreferences` and `AppDatabase` constructor deps
- After inserting `WalletEntity`, add backfill SQL:

```kotlin
// Backfill walletId in existing cached data
val db = appDatabase.openHelper.writableDatabase
db.execSQL("UPDATE transactions SET walletId = ? WHERE walletId = ''", arrayOf(walletId))
db.execSQL("UPDATE balance_cache SET walletId = ? WHERE walletId = ''", arrayOf(walletId))
db.execSQL("UPDATE dao_cells SET walletId = ? WHERE walletId = ''", arrayOf(walletId))

// Set active wallet ID
walletPreferences.setActiveWalletId(walletId)
```

- [ ] **Step 4: Update AppModule providers**

Update `provideWalletMigrationHelper()` to inject 4 deps: `walletDao`, `keyManager`, `walletPreferences`, `appDatabase`.

Update `provideWalletRepository()` to inject 4 deps: `walletDao`, `keyManager`, `walletPreferences`, `mnemonicManager`.

Or if these use `@Inject constructor`, update the constructors directly and remove `@Provides` if Hilt auto-discovers them.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/wallet/WalletRepository.kt
git add android/app/src/main/java/com/rjnr/pocketnode/data/migration/WalletMigrationHelper.kt
git add android/app/src/main/java/com/rjnr/pocketnode/di/AppModule.kt
git commit -m "feat: restore WalletRepository sub-accounts and WalletMigrationHelper backfill"
```

---

### Task 7: GatewayRepository Wallet-Awareness + Multi-Script Registration

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt` (1590 lines)

- [ ] **Step 1: Extract original diff from `0bcae9a`**

```bash
git diff HEAD 0bcae9a -- android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt > /tmp/m3-gateway-diff.patch
```

Review the diff carefully. Only apply wallet-scoping changes, NOT changes that would remove post-M3 security features.

- [ ] **Step 2: Add `WalletDao` to GatewayRepository constructor**

Add `private val walletDao: WalletDao` to the `GatewayRepository` constructor. Update `AppModule.provideGatewayRepository()` to pass it. This is needed for `registerAllWalletScripts()` and `onActiveWalletChanged()`.

Also change `KeyManager.getPrivateKeyForWallet()` return type to `ByteArray?` (nullable) — the current implementation throws on missing key, but multi-wallet code needs graceful handling when a wallet's ESP file doesn't exist. Update to return `null` instead of throwing.

- [ ] **Step 3: Add `activeWalletId` field and init migration**

```kotlin
private var activeWalletId: String = walletPreferences.getActiveWalletId() ?: ""

init {
    migrateOldDataDir()
    scope.launch {
        walletMigrationHelper.migrateIfNeeded()
        activeWalletId = walletPreferences.getActiveWalletId() ?: ""
        initializeNode(currentNetwork)
    }
}
```

- [ ] **Step 4: Add `onActiveWalletChanged()` method**

```kotlin
suspend fun onActiveWalletChanged(wallet: WalletEntity) {
    activeWalletId = wallet.walletId
    val privateKey = keyManager.getPrivateKeyForWallet(wallet.walletId)
        ?: throw Exception("No key for wallet ${wallet.walletId}")
    val info = keyManager.deriveWalletInfo(privateKey)
    _walletInfo.value = info
    _isRegistered.value = false

    when (walletPreferences.getSyncStrategy()) {
        SyncStrategy.ALL_WALLETS -> registerAllWalletScripts()
        SyncStrategy.ACTIVE_ONLY -> registerAccount(
            syncMode = walletPreferences.getSyncMode(walletId = wallet.walletId),
            savePreference = false
        )
        SyncStrategy.BALANCED -> {
            registerAccount(
                syncMode = walletPreferences.getSyncMode(walletId = wallet.walletId),
                savePreference = false
            )
            // Balanced loop handled separately
        }
    }

    // Emit cached data immediately
    cacheManager.getCachedBalance(currentNetwork.name, walletId = activeWalletId)?.let {
        _balance.value = it
    }
}
```

- [ ] **Step 5: Add `registerAllWalletScripts()` method**

```kotlin
private suspend fun registerAllWalletScripts() {
    val wallets = walletDao.getAll()
        .sortedByDescending { it.lastActiveAt }
        .take(3)

    val scriptEntries = wallets.map { wallet ->
        val key = keyManager.getPrivateKeyForWallet(wallet.walletId)
            ?: return@map null
        val pubKey = ECKeyPair.createFromPrivateKey(key).compressedPublicKey
        val args = "0x" + Blake2b.hash160(pubKey)
        // Build script JSON entry for nativeSetScripts
        buildScriptEntry(args, walletPreferences.getSyncMode(walletId = wallet.walletId))
    }.filterNotNull()

    // Register all scripts at once — replaces entire script set
    val scriptsJson = json.encodeToString(scriptEntries)
    LightClientNative.nativeSetScripts(scriptsJson, LightClientNative.CMD_SET_SCRIPTS_ALL)
    _isRegistered.value = true
}
```

**Note:** The exact `buildScriptEntry()` JSON format and `LightClientNative.nativeSetScripts(json, command)` call signature must match the existing `registerAccount()` implementation. Read the current `registerAccount()` method to understand the JSON structure. The `CMD_SET_SCRIPTS_ALL` constant replaces the entire script set (deregistering any previously registered scripts).

- [ ] **Step 6: Thread `walletId` through all cache/DAO call sites**

Search for all calls to `cacheManager.*` and `daoSyncManager.*` in GatewayRepository and add `walletId = activeWalletId`. There are 6 call sites:

1. `cacheManager.getCachedBalance(currentNetwork.name, walletId = activeWalletId)`
2. `cacheManager.cacheBalance(resp, currentNetwork.name, walletId = activeWalletId)`
3. `cacheManager.insertPendingTransaction(txHash, currentNetwork.name, walletId = activeWalletId)`
4. `cacheManager.cacheTransactions(items, currentNetwork.name, walletId = activeWalletId)`
5. `cacheManager.getPendingNotIn(currentNetwork.name, jniTxHashes, walletId = activeWalletId)`
6. `daoSyncManager.insertPendingDeposit(txHash, amountShannons, net.name, walletId = activeWalletId)`

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt
git add android/app/src/main/java/com/rjnr/pocketnode/data/wallet/KeyManager.kt
git add android/app/src/main/java/com/rjnr/pocketnode/di/AppModule.kt
git commit -m "feat: restore wallet-aware GatewayRepository with multi-script registration"
```

---

### Task 8: Build Dependencies — Paging 3

**Files:**
- Modify: `android/gradle/libs.versions.toml` (88 lines)
- Modify: `android/app/build.gradle.kts` (186 lines)

- [ ] **Step 1: Add Paging 3 to version catalog**

In `libs.versions.toml`, add under `[versions]`:
```toml
paging = "3.3.6"
```

Under `[libraries]`:
```toml
room-paging = { group = "androidx.room", name = "room-paging", version.ref = "room" }
androidx-paging-runtime = { group = "androidx.paging", name = "paging-runtime", version.ref = "paging" }
androidx-paging-compose = { group = "androidx.paging", name = "paging-compose", version.ref = "paging" }
```

- [ ] **Step 2: Add to build.gradle.kts dependencies**

```kotlin
implementation(libs.room.paging)
implementation(libs.androidx.paging.runtime)
implementation(libs.androidx.paging.compose)
```

- [ ] **Step 3: Verify build compiles**

```bash
cd android && ./gradlew assembleDebug --dry-run
```

- [ ] **Step 4: Commit**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts
git commit -m "feat: add Paging 3 dependencies for transaction list"
```

---

## Chunk 2: Feature Restoration & UI Wiring (Phase 1B-1D)

### Task 9: Restore DatabaseMaintenanceUtil & TransactionExporter

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/database/DatabaseMaintenanceUtil.kt`
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/export/TransactionExporter.kt`

- [ ] **Step 1: Extract from `0bcae9a`**

```bash
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/database/DatabaseMaintenanceUtil.kt > android/app/src/main/java/com/rjnr/pocketnode/data/database/DatabaseMaintenanceUtil.kt
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/data/export/TransactionExporter.kt > android/app/src/main/java/com/rjnr/pocketnode/data/export/TransactionExporter.kt
```

Create the `export` directory first if it doesn't exist:
```bash
mkdir -p android/app/src/main/java/com/rjnr/pocketnode/data/export
```

- [ ] **Step 2: Verify files compile**

Read each file and ensure imports match current package structure. Fix any import issues.

- [ ] **Step 3: Wire VACUUM into WalletRepository.deleteWallet()**

After `walletDao.delete(walletId)`, add:
```kotlin
DatabaseMaintenanceUtil.vacuum(appDatabase.openHelper.writableDatabase)
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/DatabaseMaintenanceUtil.kt
git add android/app/src/main/java/com/rjnr/pocketnode/data/export/TransactionExporter.kt
git add android/app/src/main/java/com/rjnr/pocketnode/data/wallet/WalletRepository.kt
git commit -m "feat: restore DatabaseMaintenanceUtil and TransactionExporter from original M3"
```

---

### Task 10: Restore CSV Export UI

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/activity/ActivityViewModel.kt` (82 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/activity/ActivityScreen.kt` (720 lines)

- [ ] **Step 1: Extract original from `0bcae9a`**

```bash
git diff HEAD 0bcae9a -- android/app/src/main/java/com/rjnr/pocketnode/ui/screens/activity/ActivityViewModel.kt
git diff HEAD 0bcae9a -- android/app/src/main/java/com/rjnr/pocketnode/ui/screens/activity/ActivityScreen.kt
```

- [ ] **Step 2: Update ActivityViewModel**

Add constructor deps: `TransactionDao`, `WalletPreferences`

Add export logic:
```kotlin
private val _exportEvent = MutableSharedFlow<String>()
val exportEvent: SharedFlow<String> = _exportEvent.asSharedFlow()

fun exportTransactions() {
    viewModelScope.launch {
        val walletId = walletPreferences.getActiveWalletId() ?: return@launch
        val network = walletPreferences.getSelectedNetwork().name
        val transactions = transactionDao.getAllByWalletAndNetwork(walletId, network)
        val csv = TransactionExporter.export(transactions, network)
        _exportEvent.emit(csv)
    }
}
```

- [ ] **Step 3: Update ActivityScreen**

Add SAF launcher for document creation:
```kotlin
val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/csv")
) { uri ->
    uri?.let { /* write CSV content to uri via contentResolver */ }
}
```

Add `LaunchedEffect` to collect `exportEvent` and trigger the launcher.

Add export `IconButton` in the TopAppBar actions.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/activity/
git commit -m "feat: restore CSV transaction export UI from original M3"
```

---

### Task 11: UI Wiring — ViewModels Call onActiveWalletChanged

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletManagerViewModel.kt` (53 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/AddWalletViewModel.kt` (116 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletDetailViewModel.kt` (114 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeViewModel.kt` (574 lines)

- [ ] **Step 1: Fix WalletManagerViewModel**

Add `GatewayRepository` constructor dep. Update `switchWallet()`:
```kotlin
fun switchWallet(walletId: String) {
    viewModelScope.launch {
        try {
            walletRepository.switchActiveWallet(walletId)
            val wallet = walletRepository.getById(walletId) ?: return@launch
            gatewayRepository.onActiveWalletChanged(wallet)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Failed to switch wallet: ${e.message}") }
        }
    }
}
```

- [ ] **Step 2: Fix AddWalletViewModel**

Add `GatewayRepository` constructor dep. After every create/import, call `onActiveWalletChanged`:
```kotlin
fun createNewWallet() {
    viewModelScope.launch {
        // ... existing validation + walletRepository.createWallet() ...
        val (wallet, _) = result
        gatewayRepository.onActiveWalletChanged(wallet)
        _uiState.update { it.copy(createdWallet = wallet) }
    }
}
```

Same pattern for `importMnemonic()`, `importRawKey()`.

Add soft cap warning: before creating any wallet, check `walletRepository.walletCount() >= 3`. If true and sync strategy is `ALL_WALLETS`, set `showSyncCapWarning = true` in UiState. The warning dialog says: "You have 3+ wallets. Only 3 can sync simultaneously. This wallet will sync when active." User can proceed or cancel.

Add `showSyncCapWarning: Boolean = false` to `AddWalletUiState`.

Add sub-account support:
```kotlin
val parentWallets: List<WalletEntity> // loaded in init, filtered to mnemonic types only
var selectedParentId: String? // selected parent for sub-account creation

fun createSubAccount() {
    viewModelScope.launch {
        val parentId = selectedParentId ?: return@launch
        val wallet = walletRepository.createSubAccount(parentId, uiState.value.name)
        gatewayRepository.onActiveWalletChanged(wallet)
        _uiState.update { it.copy(createdWallet = wallet) }
    }
}
```

- [ ] **Step 3: Fix WalletDetailViewModel**

Add `WalletDao` constructor dep. Fix `hasMnemonic()` to check `parentWalletId`:
```kotlin
fun hasMnemonic(): Boolean {
    val wallet = _uiState.value.wallet ?: return false
    return wallet.type == KeyManager.WALLET_TYPE_MNEMONIC && wallet.parentWalletId == null
}
```

Fix `cancelEditing()` to reset `editName`:
```kotlin
fun cancelEditing() {
    _uiState.update { it.copy(isEditing = false, editName = it.wallet?.name ?: "") }
}
```

- [ ] **Step 4: Fix HomeViewModel wallet switching**

Update `switchWallet()` to use `onActiveWalletChanged()` instead of `initializeWallet()`:
```kotlin
fun switchWallet(walletId: String) {
    viewModelScope.launch {
        try {
            _uiState.update { it.copy(isSwitchingWallet = true) }
            walletRepository.switchActiveWallet(walletId)
            val wallet = walletRepository.getById(walletId) ?: return@launch
            repository.onActiveWalletChanged(wallet)
            _uiState.update { it.copy(isSwitchingWallet = false) }
        } catch (e: Exception) {
            _uiState.update { it.copy(
                isSwitchingWallet = false,
                error = "Failed to switch wallet: ${e.message}"
            ) }
        }
    }
}
```

Add `isSwitchingWallet: Boolean = false` to `HomeUiState`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeViewModel.kt
git commit -m "feat: wire onActiveWalletChanged through all ViewModels"
```

---

### Task 12: Restore AddWalletScreen Sub-Account UI

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/AddWalletScreen.kt` (253 lines)

- [ ] **Step 1: Extract original from `0bcae9a`**

```bash
git diff HEAD 0bcae9a -- android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/AddWalletScreen.kt
```

- [ ] **Step 2: Add sub-account option card (mode 4)**

Add a 4th `OptionCard` in the menu mode:
```kotlin
OptionCard(
    icon = Lucide.GitBranch,
    title = "HD Sub-Account",
    description = "Derive a new account from an existing wallet",
    onClick = { selectedMode = 4 }
)
```

- [ ] **Step 3: Add SubAccountForm composable**

```kotlin
@Composable
fun SubAccountForm(viewModel: AddWalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    Column {
        OutlinedTextField(
            value = uiState.name,
            onValueChange = { viewModel.updateName(it) },
            label = { Text("Account Name") }
        )
        // Parent wallet dropdown
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = uiState.parentWallets.find { it.walletId == uiState.selectedParentId }?.name ?: "Select wallet",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                uiState.parentWallets.forEach { wallet ->
                    DropdownMenuItem(
                        text = { Text(wallet.name) },
                        onClick = {
                            viewModel.selectParent(wallet.walletId)
                            expanded = false
                        }
                    )
                }
            }
        }
        Button(onClick = { viewModel.createSubAccount() }) {
            Text("Create Sub-Account")
        }
    }
}
```

Disable for raw_key wallets (filter `parentWallets` to mnemonic types only in ViewModel).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/AddWalletScreen.kt
git commit -m "feat: restore HD sub-account creation UI from original M3"
```

---

### Task 13: Restore WalletSwitcherDropdown (temporary)

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/components/WalletSwitcherDropdown.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt` (1193 lines)

- [ ] **Step 1: Extract from `0bcae9a`**

```bash
mkdir -p android/app/src/main/java/com/rjnr/pocketnode/ui/components
git show 0bcae9a:android/app/src/main/java/com/rjnr/pocketnode/ui/components/WalletSwitcherDropdown.kt > android/app/src/main/java/com/rjnr/pocketnode/ui/components/WalletSwitcherDropdown.kt
```

- [ ] **Step 2: Update HomeScreen to use extracted component**

Replace the inline `WalletSwitcherDropdown` composable in HomeScreen with an import from `ui.components.WalletSwitcherDropdown`. Remove the inline definition.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/components/WalletSwitcherDropdown.kt
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt
git commit -m "feat: extract WalletSwitcherDropdown to dedicated component (temporary)"
```

---

### Task 14: Restore Test Files

**Files:**
- Create: 8 new test files in `android/app/src/test/java/com/rjnr/pocketnode/`
- Modify: 2 existing test files

- [ ] **Step 1: Extract all 8 test files from `0bcae9a`**

```bash
# Create directories
mkdir -p android/app/src/test/java/com/rjnr/pocketnode/data/database/dao
mkdir -p android/app/src/test/java/com/rjnr/pocketnode/data/database/entity
mkdir -p android/app/src/test/java/com/rjnr/pocketnode/data/export
mkdir -p android/app/src/test/java/com/rjnr/pocketnode/data/migration
mkdir -p android/app/src/test/java/com/rjnr/pocketnode/data/wallet

# Extract test files
git show 0bcae9a:android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/TransactionDaoWalletTest.kt > android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/TransactionDaoWalletTest.kt
git show 0bcae9a:android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/WalletDaoTest.kt > android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/WalletDaoTest.kt
git show 0bcae9a:android/app/src/test/java/com/rjnr/pocketnode/data/database/entity/WalletEntityTest.kt > android/app/src/test/java/com/rjnr/pocketnode/data/database/entity/WalletEntityTest.kt
git show 0bcae9a:android/app/src/test/java/com/rjnr/pocketnode/data/export/TransactionExporterTest.kt > android/app/src/test/java/com/rjnr/pocketnode/data/export/TransactionExporterTest.kt
git show 0bcae9a:android/app/src/test/java/com/rjnr/pocketnode/data/migration/WalletMigrationHelperTest.kt > android/app/src/test/java/com/rjnr/pocketnode/data/migration/WalletMigrationHelperTest.kt
git show 0bcae9a:android/app/src/test/java/com/rjnr/pocketnode/data/wallet/KeyManagerMultiWalletTest.kt > android/app/src/test/java/com/rjnr/pocketnode/data/wallet/KeyManagerMultiWalletTest.kt
git show 0bcae9a:android/app/src/test/java/com/rjnr/pocketnode/data/wallet/WalletPreferencesMultiWalletTest.kt > android/app/src/test/java/com/rjnr/pocketnode/data/wallet/WalletPreferencesMultiWalletTest.kt
git show 0bcae9a:android/app/src/test/java/com/rjnr/pocketnode/data/wallet/WalletRepositoryTest.kt > android/app/src/test/java/com/rjnr/pocketnode/data/wallet/WalletRepositoryTest.kt
```

- [ ] **Step 2: Adapt tests to current API**

Read each test file and fix any API mismatches:
- `deleteKeysForWallet()` → `deleteWalletKeys()` (HEAD naming)
- `switchWallet()` → `switchActiveWallet()` (new naming)
- Any constructor changes from post-M3 additions (e.g., KeyManager now has KeyBackupManager dep)

- [ ] **Step 3: Merge wallet-scoped tests into existing test files**

For `MigrationTest.kt`: add v3 schema assertions (wallets table exists, composite PK on balance_cache) and v3→v4 migration test.

For `BalanceCacheDaoTest.kt`: add wallet-scoped query tests (`getByWalletAndNetwork`) alongside existing tests.

- [ ] **Step 4: Run tests**

```bash
cd android && ./gradlew testDebugUnitTest
```

Fix any failures.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/test/
git commit -m "feat: restore 8 M3 test files and merge wallet-scoped tests"
```

---

## Chunk 3: Phase 2 UX Improvements

### Task 15: WalletAvatar Composable

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/components/WalletAvatar.kt`

- [ ] **Step 1: Create WalletAvatar composable**

```kotlin
package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val WALLET_COLORS = listOf(
    Color(0xFF6366F1),  // Indigo
    Color(0xFFF59E0B),  // Amber
    Color(0xFF10B981),  // Emerald
    Color(0xFFEF4444),  // Red
    Color(0xFF0EA5E9),  // Sky
    Color(0xFF8B5CF6),  // Violet
    Color(0xFFEC4899),  // Pink
    Color(0xFF14B8A6),  // Teal
)

@Composable
fun WalletAvatar(
    name: String,
    colorIndex: Int,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val color = WALLET_COLORS[colorIndex.coerceIn(0, WALLET_COLORS.lastIndex)]
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    val fontSize = (size.value * 0.4f).sp

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/components/WalletAvatar.kt
git commit -m "feat: add WalletAvatar composable with color palette"
```

---

### Task 16: AccountSelectorSheet (imToken-style Bottom Sheet)

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/components/AccountSelectorSheet.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt` (1193 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeViewModel.kt` (574 lines)

- [ ] **Step 1: Create AccountSelectorSheet composable**

```kotlin
package com.rjnr.pocketnode.ui.components

// ... imports ...

data class WalletGroup(
    val wallet: WalletEntity,           // parent wallet
    val subAccounts: List<WalletEntity>  // sub-accounts (parentWalletId == wallet.walletId)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSelectorSheet(
    sheetState: SheetState,
    walletGroups: List<WalletGroup>,
    activeWalletId: String,
    onSelectAccount: (String) -> Unit,
    onManageWallets: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Select account", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onDismiss(); onManageWallets() }) {
                Text("Manage")
            }
        }

        LazyColumn {
            walletGroups.forEach { group ->
                // Group header (collapsible)
                item {
                    WalletGroupHeader(group.wallet.name)
                }
                // Parent account
                item {
                    AccountRow(
                        wallet = group.wallet,
                        isActive = group.wallet.walletId == activeWalletId,
                        onClick = { onDismiss(); onSelectAccount(group.wallet.walletId) }
                    )
                }
                // Sub-accounts
                items(group.subAccounts, key = { it.walletId }) { subAccount ->
                    AccountRow(
                        wallet = subAccount,
                        isActive = subAccount.walletId == activeWalletId,
                        onClick = { onDismiss(); onSelectAccount(subAccount.walletId) }
                    )
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun AccountRow(wallet: WalletEntity, isActive: Boolean, onClick: () -> Unit) {
    // Card with WalletAvatar, account name, truncated address, balance, checkmark if active
    // Use wallet.colorIndex for avatar color
    // Highlighted border if active
}

@Composable
private fun WalletGroupHeader(name: String) {
    // Collapsible section header with wallet group icon and name
}
```

- [ ] **Step 2: Update HomeScreen — replace dropdown with bottom sheet**

Replace `WalletSwitcherDropdown` usage with `AccountSelectorSheet`:

```kotlin
var showAccountSelector by remember { mutableStateOf(false) }
val sheetState = rememberModalBottomSheetState()

// In TopAppBar title:
Row(
    modifier = Modifier.clickable { showAccountSelector = true },
    verticalAlignment = Alignment.CenterVertically
) {
    val activeWallet = uiState.wallets.find { it.isActive }
    activeWallet?.let {
        WalletAvatar(name = it.name, colorIndex = it.colorIndex, size = 32.dp)
        Spacer(Modifier.width(8.dp))
    }
    Column {
        Text("wallet", style = MaterialTheme.typography.labelSmall)
        Text(activeWallet?.name ?: "Pocket Node", fontWeight = FontWeight.SemiBold)
    }
    Icon(Lucide.ChevronDown, contentDescription = "Switch")
}

// Bottom sheet
if (showAccountSelector) {
    AccountSelectorSheet(
        sheetState = sheetState,
        walletGroups = uiState.walletGroups,
        activeWalletId = uiState.wallets.find { it.isActive }?.walletId ?: "",
        onSelectAccount = { viewModel.switchWallet(it) },
        onManageWallets = onNavigateToWalletManager,
        onDismiss = { showAccountSelector = false }
    )
}
```

- [ ] **Step 3: Add walletGroups to HomeViewModel**

```kotlin
// In HomeUiState:
val walletGroups: List<WalletGroup> = emptyList()

// In init block, after collecting wallets:
walletRepository.walletsFlow.collect { wallets ->
    val parentWallets = wallets.filter { it.parentWalletId == null }
    val groups = parentWallets.map { parent ->
        WalletGroup(
            wallet = parent,
            subAccounts = wallets.filter { it.parentWalletId == parent.walletId }
        )
    }
    _uiState.update { it.copy(wallets = wallets, walletGroups = groups) }
}
```

- [ ] **Step 4: Remove inline WalletSwitcherDropdown from HomeScreen**

Delete the inline `WalletSwitcherDropdown` composable function that was defined inside HomeScreen. The temporary extracted component from Task 13 can also be deleted since the `AccountSelectorSheet` replaces it.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/components/AccountSelectorSheet.kt
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeViewModel.kt
git commit -m "feat: add imToken-style AccountSelectorSheet bottom sheet"
```

---

### Task 17: Rework ManageWalletsScreen (Wallet Groups)

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletManagerScreen.kt` (193 lines)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletManagerViewModel.kt` (53 lines)

- [ ] **Step 1: Update WalletManagerViewModel — group wallets**

```kotlin
data class WalletManagerUiState(
    val walletGroups: List<WalletGroup> = emptyList(),
    val error: String? = null
)

init {
    viewModelScope.launch {
        walletRepository.walletsFlow.collect { wallets ->
            val parents = wallets.filter { it.parentWalletId == null }
            val groups = parents.map { parent ->
                WalletGroup(parent, wallets.filter { it.parentWalletId == parent.walletId })
            }
            _uiState.update { it.copy(walletGroups = groups) }
        }
    }
}
```

- [ ] **Step 2: Rework WalletManagerScreen — wallet group cards**

Replace the flat `LazyColumn` of `WalletCard`s with wallet group cards:

```kotlin
LazyColumn {
    items(uiState.walletGroups, key = { it.wallet.walletId }) { group ->
        WalletGroupCard(
            group = group,
            onTap = { onNavigateToWalletSettings(group.wallet.walletId) },
            onAddSubAccount = { viewModel.addSubAccount(group.wallet.walletId) }
        )
    }
}
```

Each `WalletGroupCard` shows:
- Wallet icon + name
- Account count ("2 accounts")
- Small avatar dots for each account
- "Add" link (disabled for raw_key wallets)
- Chevron to navigate to WalletSettings

- [ ] **Step 3: Update navigation**

Change `onNavigateToWalletDetail` parameter to `onNavigateToWalletSettings` (same route, semantic rename).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletManagerScreen.kt
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletManagerViewModel.kt
git commit -m "feat: rework ManageWalletsScreen to show wallet groups"
```

---

### Task 18: WalletSettingsScreen (replaces WalletDetailScreen)

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletSettingsScreen.kt`
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletSettingsViewModel.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/navigation/NavGraph.kt` (402 lines)

- [ ] **Step 1: Create WalletSettingsViewModel**

```kotlin
@HiltViewModel
class WalletSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val walletDao: WalletDao,
    private val keyManager: KeyManager
) : ViewModel() {
    private val walletId: String = savedStateHandle["walletId"] ?: ""

    data class WalletSettingsUiState(
        val wallet: WalletEntity? = null,
        val subAccounts: List<WalletEntity> = emptyList(),
        val isEditing: Boolean = false,
        val editName: String = "",
        val showDeleteConfirm: Boolean = false,
        val deleted: Boolean = false,
        val error: String? = null
    )

    // Load wallet + sub-accounts reactively
    init {
        viewModelScope.launch {
            walletDao.getSubAccounts(walletId).collect { subs ->
                _uiState.update { it.copy(subAccounts = subs) }
            }
        }
        loadWallet()
    }

    fun addSubAccount(name: String) { /* calls walletRepository.createSubAccount */ }
    fun saveName() { /* calls walletRepository.renameWallet */ }
    fun confirmDelete() { /* safety checks + walletRepository.deleteWallet */ }
    fun hasMnemonic(): Boolean { /* checks type + parentWalletId == null */ }
    fun getMnemonic(): List<String>? { /* keyManager.getMnemonicForWallet */ }
}
```

- [ ] **Step 2: Create WalletSettingsScreen**

Build the screen matching the imToken wallet settings mockup:
- Wallet icon + editable name at top
- Info section: Source type, Derivation path
- Actions: Backup wallet (status), View seed phrase
- Accounts section: list of sub-accounts with "Add account" button
- "Remove" in top app bar (with safety checks)

Delete flow:
```kotlin
when {
    wallet.isActive && walletGroups.size == 1 ->
        // "This is your only wallet. Create another before deleting."
    wallet.isActive ->
        // "Switch to another wallet first"
    else ->
        // Normal confirm dialog
}
```

- [ ] **Step 3: Update NavGraph — replace WalletDetail route**

Replace `Screen.WalletDetail` composable with `WalletSettingsScreen`:
```kotlin
composable(
    route = Screen.WalletDetail.route,  // keep same route for now
    arguments = listOf(navArgument("walletId") { type = NavType.StringType })
) {
    WalletSettingsScreen(onNavigateBack = { navController.popBackStack() })
}
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletSettingsScreen.kt
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/WalletSettingsViewModel.kt
git add android/app/src/main/java/com/rjnr/pocketnode/ui/navigation/NavGraph.kt
git commit -m "feat: add WalletSettingsScreen with sub-account management"
```

---

### Task 19: Sync Strategy Settings UI

**Files:**
- Modify: Settings screen (find the SettingsScreen.kt or the Settings tab composable)
- WalletPreferences already has `SyncStrategy` from Task 5

- [ ] **Step 1: Modify the Settings screen**

The settings UI is at `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/settings/SettingsScreen.kt`.

- [ ] **Step 2: Add Sync Strategy row**

```kotlin
// In Settings section:
SettingsRow(
    title = "Wallet Sync Strategy",
    subtitle = when (syncStrategy) {
        ACTIVE_ONLY -> "Only syncs the wallet you're using"
        ALL_WALLETS -> "Keeps all wallets synced (up to 3)"
        BALANCED -> "Active wallet real-time, others every 15 min"
    },
    onClick = { showSyncStrategyDialog = true }
)

// Dialog:
if (showSyncStrategyDialog) {
    AlertDialog(
        onDismissRequest = { showSyncStrategyDialog = false },
        title = { Text("Sync Strategy") },
        text = {
            Column {
                SyncStrategy.entries.forEach { strategy ->
                    Row(
                        modifier = Modifier.clickable { /* select */ },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = strategy == currentStrategy, onClick = { /* select */ })
                        Column {
                            Text(strategy.displayName)
                            Text(strategy.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { /* apply */ }
    )
}
```

- [ ] **Step 3: Add NodeStatus database size display**

In `NodeStatusScreen.kt`, add:
```kotlin
Text("Database size: ${formatBytes(dbSizeBytes)}")
```

Wire via `NodeStatusViewModel` calling `DatabaseMaintenanceUtil.getDatabaseSizeBytes()`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/
git commit -m "feat: add sync strategy settings and database size display"
```

---

### Task 20: Mnemonic Backup for New Wallets + Loading State

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/onboarding/MnemonicBackupScreen.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/wallet/AddWalletScreen.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/navigation/NavGraph.kt`

- [ ] **Step 1: Add `simplified` flag to MnemonicBackupScreen**

```kotlin
@Composable
fun MnemonicBackupScreen(
    // ... existing params ...
    simplified: Boolean = false  // skip 3-word quiz
)
```

When `simplified = true`:
- Show mnemonic grid with `FLAG_SECURE`
- Show single "I've saved my seed phrase" button
- Skip the verification quiz step
- On confirm: `keyManager.setMnemonicBackedUpForWallet(walletId, true)`, navigate back

- [ ] **Step 2: Update AddWalletScreen navigation**

After creating a new mnemonic wallet, navigate to `MnemonicBackupScreen` (simplified) instead of popping directly to Main:
```kotlin
onWalletCreated = { walletId ->
    navController.navigate("mnemonic_backup?simplified=true&walletId=$walletId")
}
```

Update NavGraph route for MnemonicBackup to accept optional `simplified` and `walletId` params.

- [ ] **Step 3: Add loading indicator during wallet switch**

In HomeScreen, when `uiState.isSwitchingWallet`:
```kotlin
if (uiState.isSwitchingWallet) {
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary
    )
}
```

Place at top of Scaffold content, below the TopAppBar.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/
git commit -m "feat: add mnemonic backup for new wallets and wallet switch loading state"
```

---

### Task 21: Final Cleanup & Delete Temporary Files

- [ ] **Step 1: Remove temporary WalletSwitcherDropdown**

Delete `ui/components/WalletSwitcherDropdown.kt` (replaced by `AccountSelectorSheet`).

- [ ] **Step 2: Remove old WalletDetailScreen if fully replaced**

If `WalletSettingsScreen` fully replaces `WalletDetailScreen`, delete:
- `ui/screens/wallet/WalletDetailScreen.kt`
- `ui/screens/wallet/WalletDetailViewModel.kt`

- [ ] **Step 3: Run full test suite**

```bash
cd android && ./gradlew testDebugUnitTest
```

Fix any failures.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "chore: remove deprecated WalletDetailScreen and temporary WalletSwitcherDropdown"
```

---

## Dependency Graph

```
Task 1 (Entities) ──────┐
Task 2 (Migrations) ────┤
Task 8 (Build Deps) ────┤
                         ├── Task 3 (DAO Queries) ── Task 4 (CacheManager) ── Task 7 (GatewayRepo)
Task 5 (WalletPrefs) ───┤                    │                                      │
                         │                    └── Task 6 (WalletRepo) ───────────────┤
                                                                                    │
Task 9 (Maintenance+Exporter) ─── Task 10 (CSV Export UI) ─────────────────────────┤
                                                                                    │
Task 11 (ViewModel wiring) ────── Task 12 (SubAccount UI) ─────────────────────────┤
Task 13 (WalletSwitcher temp) ──────────────────────────────────────────────────────┤
Task 14 (Tests) ────────────────────────────────────────────────────────────────────┘
                                                                                    │
                                                                              Phase 1 Done
                                                                                    │
Task 15 (WalletAvatar) ── Task 16 (AccountSelectorSheet) ── Task 17 (ManageWallets)│
                                                                                    │
Task 18 (WalletSettings) ── Task 19 (SyncStrategy UI) ── Task 20 (Backup+Loading) ─┤
                                                                                    │
Task 21 (Cleanup) ──────────────────────────────────────────────────────────────────┘
```
