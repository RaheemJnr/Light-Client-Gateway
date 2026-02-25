# Room Cache Phase 2 — Header Cache + DAO Cells

## Goal

Add `header_cache` and `dao_cells` Room tables for the DAO sync pipeline, and extract Phase 1 cache logic into a dedicated `CacheManager` for consistency.

## Architecture

**Approach B: Dedicated Managers** — Each cache concern gets its own manager class. `GatewayRepository` delegates to managers instead of using DAOs directly.

```
GatewayRepository (JNI orchestration)
  ├── CacheManager        (TransactionDao + BalanceCacheDao)  ← extracted from Phase 1
  └── DaoSyncManager      (HeaderCacheDao + DaoCellDao)       ← new
```

**Branch:** New branch from `main` (which has Phase 1 Room code merged). The `feature/m2-nervos-dao` branch will later merge/rebase on top.

**Version:** Stays at 1.2.0. No bump.

## New Tables

### `header_cache`

Block headers are immutable on-chain — once cached, never invalidated. The `epoch` field is needed for DAO cycle progress calculation, and the `dao` field is needed for compensation calculation via `nativeCalculateMaxWithdraw`.

| Column | Type | Notes |
|--------|------|-------|
| `blockHash` (PK) | TEXT | 0x hex |
| `number` | TEXT | Hex block number |
| `epoch` | TEXT | Packed epoch hex (parsed to EpochInfo on read) |
| `timestamp` | TEXT | Unix millis as hex |
| `dao` | TEXT | 32-byte DAO field hex |
| `network` | TEXT | MAINNET / TESTNET |
| `cachedAt` | INTEGER | System.currentTimeMillis() |

### `dao_cells`

Enriched DAO deposit lifecycle tracking. Eliminates repeated JNI calls — most data is static per deposit, only compensation changes over time.

| Column | Type | Notes |
|--------|------|-------|
| `txHash` + `index` (composite PK) | TEXT, TEXT | OutPoint |
| `capacity` | INTEGER | Shannons (Long) |
| `status` | TEXT | DaoCellStatus enum name |
| `depositBlockNumber` | INTEGER | |
| `depositBlockHash` | TEXT | |
| `depositEpochHex` | TEXT | Nullable — raw packed hex |
| `withdrawBlockNumber` | INTEGER | Nullable |
| `withdrawBlockHash` | TEXT | Nullable |
| `withdrawEpochHex` | TEXT | Nullable |
| `compensation` | INTEGER | Earned shannons |
| `unlockEpochHex` | TEXT | Nullable |
| `depositTimestamp` | INTEGER | Unix millis |
| `network` | TEXT | |
| `lastUpdatedAt` | INTEGER | |

**Not persisted** (computed on read from cached data): `apc`, `lockRemainingHours`, `compensationCycleProgress`, `cyclePhase`, `DaoOverview`.

## New Files

```
data/database/dao/HeaderCacheDao.kt
data/database/dao/DaoCellDao.kt
data/database/entity/HeaderCacheEntity.kt
data/database/entity/DaoCellEntity.kt
data/gateway/CacheManager.kt
data/gateway/DaoSyncManager.kt
```

## CacheManager (extracted from GatewayRepository)

Owns `TransactionDao` + `BalanceCacheDao`. Wraps Phase 1 cache operations with consistent error handling.

```kotlin
@Singleton
class CacheManager @Inject constructor(
    private val transactionDao: TransactionDao,
    private val balanceCacheDao: BalanceCacheDao
) {
    // Balance
    fun getCachedBalance(network: String): BalanceCacheEntity?
    fun cacheBalance(response: BalanceResponse, network: String)

    // Transactions
    fun cacheTransactions(records: List<TransactionRecord>, network: String)
    fun insertPendingTransaction(txHash: String, network: String)
    fun getPendingNotIn(network: String, excludeHashes: Set<String>): List<TransactionRecord>

    // Cleanup
    fun clearAll()
}
```

## DaoSyncManager

Owns `HeaderCacheDao` + `DaoCellDao`. Provides persistence for the DAO sync pipeline.

```kotlin
@Singleton
class DaoSyncManager @Inject constructor(
    private val headerCacheDao: HeaderCacheDao,
    private val daoCellDao: DaoCellDao
) {
    // Headers (permanent cache)
    fun getCachedHeader(blockHash: String): HeaderCacheEntity?
    fun cacheHeader(header: JniHeaderView, network: String)

    // DAO cells
    fun getActiveDeposits(network: String): List<DaoCellEntity>
    fun getCompletedDeposits(network: String): List<DaoCellEntity>
    fun getByOutPoint(txHash: String, index: String): DaoCellEntity?
    fun upsertDaoCell(entity: DaoCellEntity)
    fun updateStatus(txHash: String, index: String, status: String)
    fun insertPendingDeposit(txHash: String, capacity: Long, network: String)

    // Cleanup
    fun clearForNetwork(network: String)
    fun clearAll()
}
```

## Modified Files

- **`AppDatabase.kt`** — Add `HeaderCacheEntity`, `DaoCellEntity` to entities; bump version 1 → 2 (fallbackToDestructiveMigration)
- **`AppModule.kt`** — Add providers: `HeaderCacheDao`, `DaoCellDao`, `CacheManager`, `DaoSyncManager`; update `GatewayRepository` provider to use `CacheManager` + `DaoSyncManager`
- **`GatewayRepository.kt`** — Replace direct `TransactionDao`/`BalanceCacheDao` usage with `CacheManager` calls; inject `DaoSyncManager`; update `switchNetwork()` to clear both managers

## Caching Strategy

| Data | TTL | Invalidation |
|------|-----|-------------|
| Block headers | **Permanent** (immutable on-chain) | Only on network switch |
| DAO cells | Until status changes | On new DAO tx, on sync progress |
| Transactions | Until JNI provides newer | On new tx, on sync progress |
| Balance | 2 min | On new tx confirmation |
| All caches | — | Clear on network switch |

## Testing

Same patterns as Phase 1: JUnit 4 + Robolectric + Room in-memory DB.

- `HeaderCacheEntityTest` — mapper to/from JniHeaderView
- `DaoCellEntityTest` — mapper to/from DaoDeposit
- `HeaderCacheDaoTest` — Robolectric CRUD
- `DaoCellDaoTest` — Robolectric CRUD + status lifecycle updates
- `CacheManagerTest` — Unit tests with mocked DAOs
- `DaoSyncManagerTest` — Unit tests with mocked DAOs

## Dependencies

No new external dependencies. Room 2.8.4 and kotlinx-coroutines-test already present.
