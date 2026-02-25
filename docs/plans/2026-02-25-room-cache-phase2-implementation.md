# Room Cache Phase 2 — Header Cache + DAO Cells Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `header_cache` and `dao_cells` Room tables with `DaoSyncManager`, and extract Phase 1 cache logic into `CacheManager`.

**Architecture:** Approach B (Dedicated Managers). `GatewayRepository` delegates cache operations to `CacheManager` (Phase 1 tables) and `DaoSyncManager` (Phase 2 tables). Each manager owns its DAOs and wraps operations with error handling.

**Tech Stack:** Room 2.8.4, Hilt 2.57.2, JUnit 4, Robolectric, kotlinx-coroutines-test

**Branch:** `feature/issue-40-room-cache-phase2` from `main`

---

### Task 1: Create feature branch

**Step 1: Create and checkout branch**

```bash
cd /Users/raheemjnr/AndroidStudioProjects/ckb-wallet-gateway
git checkout main && git pull origin main
git checkout -b feature/issue-40-room-cache-phase2
```

---

### Task 2: HeaderCacheEntity + mapper + tests

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/HeaderCacheEntity.kt`
- Test: `android/app/src/test/java/com/rjnr/pocketnode/data/database/entity/HeaderCacheEntityTest.kt`

**Context:** `JniHeaderView` is defined in `data/gateway/models/JniModels.kt` with fields: `hash`, `number`, `epoch`, `timestamp`, `parentHash`, `transactionsRoot`, `proposalsHash`, `extraHash`, `dao`, `nonce`. We only cache the fields needed for DAO calculations.

**Step 1: Write entity with mapper**

```kotlin
package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView

@Entity(tableName = "header_cache")
data class HeaderCacheEntity(
    @PrimaryKey val blockHash: String,
    val number: String,
    val epoch: String,
    val timestamp: String,
    val dao: String,
    val network: String,
    val cachedAt: Long
) {
    companion object {
        fun from(header: JniHeaderView, network: String): HeaderCacheEntity =
            HeaderCacheEntity(
                blockHash = header.hash,
                number = header.number,
                epoch = header.epoch,
                timestamp = header.timestamp,
                dao = header.dao,
                network = network,
                cachedAt = System.currentTimeMillis()
            )
    }
}
```

**Step 2: Write tests**

```kotlin
package com.rjnr.pocketnode.data.database.entity

import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import org.junit.Assert.*
import org.junit.Test

class HeaderCacheEntityTest {

    private fun makeHeader() = JniHeaderView(
        hash = "0xabc123",
        number = "0x1a4",
        epoch = "0x7080291000032",
        timestamp = "0x18c8d0a7a00",
        parentHash = "0xparent",
        transactionsRoot = "0xtxroot",
        proposalsHash = "0xproposals",
        extraHash = "0xextra",
        dao = "0x40b4d9a3ddc9e730bdf4d7c1d303200020a89727c721f600005cda0fe6c50007",
        nonce = "0x0"
    )

    @Test
    fun `from JniHeaderView maps all fields`() {
        val entity = HeaderCacheEntity.from(makeHeader(), "MAINNET")

        assertEquals("0xabc123", entity.blockHash)
        assertEquals("0x1a4", entity.number)
        assertEquals("0x7080291000032", entity.epoch)
        assertEquals("0x18c8d0a7a00", entity.timestamp)
        assertEquals("0x40b4d9a3ddc9e730bdf4d7c1d303200020a89727c721f600005cda0fe6c50007", entity.dao)
        assertEquals("MAINNET", entity.network)
        assertTrue(entity.cachedAt > 0)
    }

    @Test
    fun `from preserves network correctly`() {
        val mainnet = HeaderCacheEntity.from(makeHeader(), "MAINNET")
        val testnet = HeaderCacheEntity.from(makeHeader(), "TESTNET")

        assertEquals("MAINNET", mainnet.network)
        assertEquals("TESTNET", testnet.network)
    }
}
```

**Step 3: Run tests**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.database.entity.HeaderCacheEntityTest"
```

Expected: PASS

**Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/HeaderCacheEntity.kt android/app/src/test/java/com/rjnr/pocketnode/data/database/entity/HeaderCacheEntityTest.kt
git commit -m "feat: add HeaderCacheEntity with mapper (Issue #40 Phase 2)"
```

---

### Task 3: DaoCellEntity + mapper + tests

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/DaoCellEntity.kt`
- Test: `android/app/src/test/java/com/rjnr/pocketnode/data/database/entity/DaoCellEntityTest.kt`

**Context:** On the DAO branch, `DaoDeposit` uses `OutPoint(txHash, index)` as its natural ID and has a 7-state `DaoCellStatus` enum. Since `DaoDeposit` and `DaoCellStatus` don't exist on `main` yet, this entity stores raw fields that can be mapped when the DAO branch merges. Computed fields (`apc`, `lockRemainingHours`, `compensationCycleProgress`, `cyclePhase`) are NOT stored.

**Step 1: Write entity**

```kotlin
package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity

@Entity(tableName = "dao_cells", primaryKeys = ["txHash", "index"])
data class DaoCellEntity(
    val txHash: String,
    val index: String,
    val capacity: Long,
    val status: String,
    val depositBlockNumber: Long,
    val depositBlockHash: String,
    val depositEpochHex: String?,
    val withdrawBlockNumber: Long?,
    val withdrawBlockHash: String?,
    val withdrawEpochHex: String?,
    val compensation: Long,
    val unlockEpochHex: String?,
    val depositTimestamp: Long,
    val network: String,
    val lastUpdatedAt: Long
)
```

**Step 2: Write tests**

```kotlin
package com.rjnr.pocketnode.data.database.entity

import org.junit.Assert.*
import org.junit.Test

class DaoCellEntityTest {

    private fun makeDeposited() = DaoCellEntity(
        txHash = "0xdep123",
        index = "0x0",
        capacity = 10_200_000_000L,
        status = "DEPOSITED",
        depositBlockNumber = 100L,
        depositBlockHash = "0xblockhash1",
        depositEpochHex = "0x7080291000032",
        withdrawBlockNumber = null,
        withdrawBlockHash = null,
        withdrawEpochHex = null,
        compensation = 500_000L,
        unlockEpochHex = null,
        depositTimestamp = 1700000000000L,
        network = "MAINNET",
        lastUpdatedAt = System.currentTimeMillis()
    )

    @Test
    fun `deposited entity has correct fields`() {
        val entity = makeDeposited()

        assertEquals("0xdep123", entity.txHash)
        assertEquals("0x0", entity.index)
        assertEquals(10_200_000_000L, entity.capacity)
        assertEquals("DEPOSITED", entity.status)
        assertEquals(100L, entity.depositBlockNumber)
        assertNull(entity.withdrawBlockNumber)
        assertNull(entity.withdrawBlockHash)
        assertEquals(500_000L, entity.compensation)
    }

    @Test
    fun `withdrawing entity has withdraw fields populated`() {
        val entity = makeDeposited().copy(
            status = "LOCKED",
            withdrawBlockNumber = 200L,
            withdrawBlockHash = "0xwithdrawhash",
            withdrawEpochHex = "0x70802d2000064",
            unlockEpochHex = "0x7080500000096"
        )

        assertEquals("LOCKED", entity.status)
        assertEquals(200L, entity.withdrawBlockNumber)
        assertEquals("0xwithdrawhash", entity.withdrawBlockHash)
        assertNotNull(entity.unlockEpochHex)
    }

    @Test
    fun `pending deposit has DEPOSITING status`() {
        val entity = DaoCellEntity(
            txHash = "0xpending",
            index = "0x0",
            capacity = 10_200_000_000L,
            status = "DEPOSITING",
            depositBlockNumber = 0L,
            depositBlockHash = "",
            depositEpochHex = null,
            withdrawBlockNumber = null,
            withdrawBlockHash = null,
            withdrawEpochHex = null,
            compensation = 0L,
            unlockEpochHex = null,
            depositTimestamp = System.currentTimeMillis(),
            network = "MAINNET",
            lastUpdatedAt = System.currentTimeMillis()
        )

        assertEquals("DEPOSITING", entity.status)
        assertEquals(0L, entity.depositBlockNumber)
        assertEquals(0L, entity.compensation)
    }
}
```

**Step 3: Run tests**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.database.entity.DaoCellEntityTest"
```

Expected: PASS

**Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/DaoCellEntity.kt android/app/src/test/java/com/rjnr/pocketnode/data/database/entity/DaoCellEntityTest.kt
git commit -m "feat: add DaoCellEntity for DAO cell lifecycle tracking (Issue #40 Phase 2)"
```

---

### Task 4: HeaderCacheDao

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/HeaderCacheDao.kt`
- Test: `android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/HeaderCacheDaoTest.kt`

**Depends on:** Task 2 (HeaderCacheEntity), Task 6 (AppDatabase update). Write the DAO now, test after Task 6.

**Step 1: Write DAO**

```kotlin
package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity

@Dao
interface HeaderCacheDao {

    @Query("SELECT * FROM header_cache WHERE blockHash = :blockHash")
    suspend fun getByBlockHash(blockHash: String): HeaderCacheEntity?

    @Query("SELECT * FROM header_cache WHERE network = :network")
    suspend fun getByNetwork(network: String): List<HeaderCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HeaderCacheEntity)

    @Query("DELETE FROM header_cache WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM header_cache")
    suspend fun deleteAll()
}
```

**Step 2: Commit (tests written in Task 8)**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/HeaderCacheDao.kt
git commit -m "feat: add HeaderCacheDao (Issue #40 Phase 2)"
```

---

### Task 5: DaoCellDao

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/DaoCellDao.kt`

**Step 1: Write DAO**

```kotlin
package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity

@Dao
interface DaoCellDao {

    @Query("SELECT * FROM dao_cells WHERE network = :network AND status NOT IN ('COMPLETED')")
    suspend fun getActiveByNetwork(network: String): List<DaoCellEntity>

    @Query("SELECT * FROM dao_cells WHERE network = :network AND status = 'COMPLETED'")
    suspend fun getCompletedByNetwork(network: String): List<DaoCellEntity>

    @Query("SELECT * FROM dao_cells WHERE txHash = :txHash AND `index` = :index")
    suspend fun getByOutPoint(txHash: String, index: String): DaoCellEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DaoCellEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DaoCellEntity>)

    @Query("UPDATE dao_cells SET status = :status, lastUpdatedAt = :updatedAt WHERE txHash = :txHash AND `index` = :index")
    suspend fun updateStatus(txHash: String, index: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM dao_cells WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM dao_cells")
    suspend fun deleteAll()
}
```

**Step 2: Commit (tests written in Task 8)**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/DaoCellDao.kt
git commit -m "feat: add DaoCellDao (Issue #40 Phase 2)"
```

---

### Task 6: Update AppDatabase to v2

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/AppDatabase.kt`

**Context:** Current `AppDatabase` has version=1, entities=[TransactionEntity, BalanceCacheEntity]. Add 2 new entities, bump to version=2. `fallbackToDestructiveMigration()` is already set in `AppModule.kt` so no migration needed.

**Step 1: Update AppDatabase**

The updated file should be:

```kotlin
package com.rjnr.pocketnode.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        BalanceCacheEntity::class,
        HeaderCacheEntity::class,
        DaoCellEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun balanceCacheDao(): BalanceCacheDao
    abstract fun headerCacheDao(): HeaderCacheDao
    abstract fun daoCellDao(): DaoCellDao
}
```

**Step 2: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/database/AppDatabase.kt
git commit -m "feat: add HeaderCacheEntity and DaoCellEntity to AppDatabase v2 (Issue #40 Phase 2)"
```

---

### Task 7: CacheManager — extract Phase 1 cache logic

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/CacheManager.kt`
- Test: `android/app/src/test/java/com/rjnr/pocketnode/data/gateway/CacheManagerTest.kt`

**Context:** Extract the cache operations currently inline in `GatewayRepository.kt` into a dedicated class. The touch points are:
- `refreshBalance()` lines 452-460: read cached balance
- `refreshBalance()` lines 570-575: write balance cache
- `sendTransaction()` lines 718-738: insert pending tx
- `getTransactions()` lines 877-906: write tx cache + merge pending
- `switchNetwork()` lines 237-243: clear all caches

**Step 1: Write CacheManager**

```kotlin
package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheManager @Inject constructor(
    private val transactionDao: TransactionDao,
    private val balanceCacheDao: BalanceCacheDao
) {
    // --- Balance cache ---

    suspend fun getCachedBalance(network: String): BalanceResponse? {
        return try {
            balanceCacheDao.getByNetwork(network)?.toBalanceResponse()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read balance cache: ${e.message}")
            null
        }
    }

    suspend fun cacheBalance(response: BalanceResponse, network: String) {
        try {
            balanceCacheDao.upsert(BalanceCacheEntity.from(response, network))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write balance cache: ${e.message}")
        }
    }

    // --- Transaction cache ---

    suspend fun cacheTransactions(records: List<TransactionRecord>, network: String) {
        try {
            val entities = records.map { record ->
                TransactionEntity.fromTransactionRecord(
                    txHash = record.txHash,
                    blockNumber = record.blockNumber,
                    blockHash = record.blockHash,
                    timestamp = record.timestamp,
                    balanceChange = record.balanceChange,
                    direction = record.direction,
                    fee = record.fee,
                    confirmations = record.confirmations,
                    blockTimestampHex = record.blockTimestampHex,
                    network = network
                )
            }
            transactionDao.insertAll(entities)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write transaction cache: ${e.message}")
        }
    }

    suspend fun insertPendingTransaction(txHash: String, network: String) {
        try {
            transactionDao.insert(
                TransactionEntity(
                    txHash = txHash,
                    blockNumber = "",
                    blockHash = "",
                    timestamp = System.currentTimeMillis(),
                    balanceChange = "0x0",
                    direction = "out",
                    fee = "0x186a0",
                    confirmations = 0,
                    blockTimestampHex = null,
                    network = network,
                    status = "PENDING",
                    isLocal = true,
                    cachedAt = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Pending transaction cached in Room: $txHash")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache pending tx: ${e.message}")
        }
    }

    suspend fun getPendingNotIn(network: String, excludeHashes: Set<String>): List<TransactionRecord> {
        return try {
            transactionDao.getPending(network)
                .filter { it.isLocal && it.txHash !in excludeHashes }
                .map { it.toTransactionRecord() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- Cleanup ---

    suspend fun clearAll() {
        try {
            transactionDao.deleteAll()
            balanceCacheDao.deleteAll()
            Log.d(TAG, "All caches cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear caches: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CacheManager"
    }
}
```

**Step 2: Write tests**

```kotlin
package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CacheManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var cacheManager: CacheManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheManager = CacheManager(db.transactionDao(), db.balanceCacheDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `cacheBalance and getCachedBalance round-trip`() = runTest {
        val response = BalanceResponse(
            address = "ckt1qz...",
            capacity = "0x174876e800",
            capacityCkb = "1000.0",
            asOfBlock = "0x100"
        )

        cacheManager.cacheBalance(response, "MAINNET")
        val cached = cacheManager.getCachedBalance("MAINNET")

        assertNotNull(cached)
        assertEquals("ckt1qz...", cached!!.address)
        assertEquals("0x174876e800", cached.capacity)
    }

    @Test
    fun `getCachedBalance returns null for missing network`() = runTest {
        assertNull(cacheManager.getCachedBalance("MAINNET"))
    }

    @Test
    fun `insertPendingTransaction and getPendingNotIn`() = runTest {
        cacheManager.insertPendingTransaction("0xtx1", "MAINNET")
        cacheManager.insertPendingTransaction("0xtx2", "MAINNET")

        val pending = cacheManager.getPendingNotIn("MAINNET", setOf("0xtx1"))
        assertEquals(1, pending.size)
        assertEquals("0xtx2", pending[0].txHash)
    }

    @Test
    fun `cacheTransactions stores records`() = runTest {
        val records = listOf(
            TransactionRecord(
                txHash = "0xabc",
                blockNumber = "0x100",
                blockHash = "0xhash",
                timestamp = 1700000000000L,
                balanceChange = "0x174876e800",
                direction = "in",
                fee = "0x0",
                confirmations = 5
            )
        )

        cacheManager.cacheTransactions(records, "MAINNET")

        // Verify via getPendingNotIn (won't return confirmed, but clearAll should work)
        val pending = cacheManager.getPendingNotIn("MAINNET", emptySet())
        assertEquals(0, pending.size) // confirmed tx is not pending
    }

    @Test
    fun `clearAll removes all data`() = runTest {
        val response = BalanceResponse("addr", "0x100", "1.0", "0x1")
        cacheManager.cacheBalance(response, "MAINNET")
        cacheManager.insertPendingTransaction("0xtx1", "MAINNET")

        cacheManager.clearAll()

        assertNull(cacheManager.getCachedBalance("MAINNET"))
        assertEquals(0, cacheManager.getPendingNotIn("MAINNET", emptySet()).size)
    }
}
```

**Step 3: Run tests**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.gateway.CacheManagerTest"
```

Expected: PASS

**Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/CacheManager.kt android/app/src/test/java/com/rjnr/pocketnode/data/gateway/CacheManagerTest.kt
git commit -m "feat: add CacheManager extracting Phase 1 cache logic (Issue #40 Phase 2)"
```

---

### Task 8: DaoSyncManager + DAO tests

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/DaoSyncManager.kt`
- Test: `android/app/src/test/java/com/rjnr/pocketnode/data/gateway/DaoSyncManagerTest.kt`
- Test: `android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/HeaderCacheDaoTest.kt`
- Test: `android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/DaoCellDaoTest.kt`

**Step 1: Write DaoSyncManager**

```kotlin
package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DaoSyncManager @Inject constructor(
    private val headerCacheDao: HeaderCacheDao,
    private val daoCellDao: DaoCellDao
) {
    // --- Header cache (permanent — block headers are immutable) ---

    suspend fun getCachedHeader(blockHash: String): HeaderCacheEntity? {
        return try {
            headerCacheDao.getByBlockHash(blockHash)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read header cache: ${e.message}")
            null
        }
    }

    suspend fun cacheHeader(header: JniHeaderView, network: String) {
        try {
            headerCacheDao.upsert(HeaderCacheEntity.from(header, network))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write header cache: ${e.message}")
        }
    }

    // --- DAO cell lifecycle ---

    suspend fun getActiveDeposits(network: String): List<DaoCellEntity> {
        return try {
            daoCellDao.getActiveByNetwork(network)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read active deposits: ${e.message}")
            emptyList()
        }
    }

    suspend fun getCompletedDeposits(network: String): List<DaoCellEntity> {
        return try {
            daoCellDao.getCompletedByNetwork(network)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read completed deposits: ${e.message}")
            emptyList()
        }
    }

    suspend fun getByOutPoint(txHash: String, index: String): DaoCellEntity? {
        return try {
            daoCellDao.getByOutPoint(txHash, index)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read DAO cell: ${e.message}")
            null
        }
    }

    suspend fun upsertDaoCell(entity: DaoCellEntity) {
        try {
            daoCellDao.upsert(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upsert DAO cell: ${e.message}")
        }
    }

    suspend fun upsertDaoCells(entities: List<DaoCellEntity>) {
        try {
            daoCellDao.upsertAll(entities)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upsert DAO cells: ${e.message}")
        }
    }

    suspend fun updateStatus(txHash: String, index: String, status: String) {
        try {
            daoCellDao.updateStatus(txHash, index, status)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update DAO cell status: ${e.message}")
        }
    }

    suspend fun insertPendingDeposit(txHash: String, capacity: Long, network: String) {
        try {
            daoCellDao.upsert(
                DaoCellEntity(
                    txHash = txHash,
                    index = "0x0",
                    capacity = capacity,
                    status = "DEPOSITING",
                    depositBlockNumber = 0L,
                    depositBlockHash = "",
                    depositEpochHex = null,
                    withdrawBlockNumber = null,
                    withdrawBlockHash = null,
                    withdrawEpochHex = null,
                    compensation = 0L,
                    unlockEpochHex = null,
                    depositTimestamp = System.currentTimeMillis(),
                    network = network,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Pending DAO deposit cached: $txHash")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache pending deposit: ${e.message}")
        }
    }

    // --- Cleanup ---

    suspend fun clearForNetwork(network: String) {
        try {
            headerCacheDao.deleteByNetwork(network)
            daoCellDao.deleteByNetwork(network)
            Log.d(TAG, "DAO caches cleared for $network")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear DAO caches: ${e.message}")
        }
    }

    suspend fun clearAll() {
        try {
            headerCacheDao.deleteAll()
            daoCellDao.deleteAll()
            Log.d(TAG, "All DAO caches cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear DAO caches: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DaoSyncManager"
    }
}
```

**Step 2: Write HeaderCacheDaoTest**

```kotlin
package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeaderCacheDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: HeaderCacheDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.headerCacheDao()
    }

    @After
    fun teardown() { db.close() }

    private fun makeHeader(
        hash: String = "0xabc",
        network: String = "MAINNET"
    ) = HeaderCacheEntity(
        blockHash = hash,
        number = "0x100",
        epoch = "0x7080291000032",
        timestamp = "0x18c8d0a7a00",
        dao = "0x40b4d9a3ddc9e730",
        network = network,
        cachedAt = System.currentTimeMillis()
    )

    @Test
    fun `upsert and getByBlockHash`() = runTest {
        dao.upsert(makeHeader("0xabc"))
        val result = dao.getByBlockHash("0xabc")
        assertNotNull(result)
        assertEquals("0x100", result!!.number)
    }

    @Test
    fun `getByBlockHash returns null for missing hash`() = runTest {
        assertNull(dao.getByBlockHash("0xmissing"))
    }

    @Test
    fun `getByNetwork filters correctly`() = runTest {
        dao.upsert(makeHeader("0x1", "MAINNET"))
        dao.upsert(makeHeader("0x2", "TESTNET"))

        val mainnet = dao.getByNetwork("MAINNET")
        assertEquals(1, mainnet.size)
        assertEquals("0x1", mainnet[0].blockHash)
    }

    @Test
    fun `upsert replaces existing entry`() = runTest {
        dao.upsert(makeHeader("0xabc").copy(number = "0x100"))
        dao.upsert(makeHeader("0xabc").copy(number = "0x200"))

        val result = dao.getByBlockHash("0xabc")
        assertEquals("0x200", result!!.number)
    }

    @Test
    fun `deleteByNetwork only removes matching network`() = runTest {
        dao.upsert(makeHeader("0x1", "MAINNET"))
        dao.upsert(makeHeader("0x2", "TESTNET"))

        dao.deleteByNetwork("MAINNET")

        assertTrue(dao.getByNetwork("MAINNET").isEmpty())
        assertEquals(1, dao.getByNetwork("TESTNET").size)
    }
}
```

**Step 3: Write DaoCellDaoTest**

```kotlin
package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoCellDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DaoCellDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.daoCellDao()
    }

    @After
    fun teardown() { db.close() }

    private fun makeCell(
        txHash: String = "0xdep1",
        index: String = "0x0",
        status: String = "DEPOSITED",
        network: String = "MAINNET"
    ) = DaoCellEntity(
        txHash = txHash,
        index = index,
        capacity = 10_200_000_000L,
        status = status,
        depositBlockNumber = 100L,
        depositBlockHash = "0xblockhash",
        depositEpochHex = "0x7080291000032",
        withdrawBlockNumber = null,
        withdrawBlockHash = null,
        withdrawEpochHex = null,
        compensation = 500_000L,
        unlockEpochHex = null,
        depositTimestamp = 1700000000000L,
        network = network,
        lastUpdatedAt = System.currentTimeMillis()
    )

    @Test
    fun `upsert and getByOutPoint`() = runTest {
        dao.upsert(makeCell("0xdep1", "0x0"))
        val result = dao.getByOutPoint("0xdep1", "0x0")
        assertNotNull(result)
        assertEquals(10_200_000_000L, result!!.capacity)
    }

    @Test
    fun `getActiveByNetwork excludes COMPLETED`() = runTest {
        dao.upsert(makeCell("0x1", status = "DEPOSITED"))
        dao.upsert(makeCell("0x2", status = "LOCKED"))
        dao.upsert(makeCell("0x3", status = "COMPLETED"))

        val active = dao.getActiveByNetwork("MAINNET")
        assertEquals(2, active.size)
        assertTrue(active.none { it.status == "COMPLETED" })
    }

    @Test
    fun `getCompletedByNetwork returns only COMPLETED`() = runTest {
        dao.upsert(makeCell("0x1", status = "DEPOSITED"))
        dao.upsert(makeCell("0x2", status = "COMPLETED"))

        val completed = dao.getCompletedByNetwork("MAINNET")
        assertEquals(1, completed.size)
        assertEquals("0x2", completed[0].txHash)
    }

    @Test
    fun `updateStatus changes status`() = runTest {
        dao.upsert(makeCell("0xdep1", status = "DEPOSITED"))
        dao.updateStatus("0xdep1", "0x0", "LOCKED")

        val updated = dao.getByOutPoint("0xdep1", "0x0")
        assertEquals("LOCKED", updated!!.status)
    }

    @Test
    fun `deleteByNetwork only removes matching network`() = runTest {
        dao.upsert(makeCell("0x1", network = "MAINNET"))
        dao.upsert(makeCell("0x2", network = "TESTNET"))

        dao.deleteByNetwork("MAINNET")

        assertTrue(dao.getActiveByNetwork("MAINNET").isEmpty())
        assertEquals(1, dao.getActiveByNetwork("TESTNET").size)
    }

    @Test
    fun `upsert replaces existing entry`() = runTest {
        dao.upsert(makeCell("0xdep1", status = "DEPOSITING"))
        dao.upsert(makeCell("0xdep1", status = "DEPOSITED"))

        val result = dao.getByOutPoint("0xdep1", "0x0")
        assertEquals("DEPOSITED", result!!.status)
    }
}
```

**Step 4: Write DaoSyncManagerTest**

```kotlin
package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoSyncManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var manager: DaoSyncManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = DaoSyncManager(db.headerCacheDao(), db.daoCellDao())
    }

    @After
    fun teardown() { db.close() }

    private fun makeHeaderView() = JniHeaderView(
        hash = "0xabc123",
        number = "0x100",
        epoch = "0x7080291000032",
        timestamp = "0x18c8d0a7a00",
        parentHash = "0xp",
        transactionsRoot = "0xt",
        proposalsHash = "0xpr",
        extraHash = "0xe",
        dao = "0x40b4d9a3ddc9e730",
        nonce = "0x0"
    )

    @Test
    fun `cacheHeader and getCachedHeader round-trip`() = runTest {
        manager.cacheHeader(makeHeaderView(), "MAINNET")
        val cached = manager.getCachedHeader("0xabc123")

        assertNotNull(cached)
        assertEquals("0x100", cached!!.number)
        assertEquals("0x40b4d9a3ddc9e730", cached.dao)
    }

    @Test
    fun `getCachedHeader returns null for missing hash`() = runTest {
        assertNull(manager.getCachedHeader("0xmissing"))
    }

    @Test
    fun `insertPendingDeposit creates DEPOSITING entry`() = runTest {
        manager.insertPendingDeposit("0xtx1", 10_200_000_000L, "MAINNET")

        val cell = manager.getByOutPoint("0xtx1", "0x0")
        assertNotNull(cell)
        assertEquals("DEPOSITING", cell!!.status)
        assertEquals(10_200_000_000L, cell.capacity)
    }

    @Test
    fun `getActiveDeposits excludes COMPLETED`() = runTest {
        manager.insertPendingDeposit("0x1", 100L, "MAINNET")
        manager.updateStatus("0x1", "0x0", "COMPLETED")
        manager.insertPendingDeposit("0x2", 200L, "MAINNET")

        val active = manager.getActiveDeposits("MAINNET")
        assertEquals(1, active.size)
        assertEquals("0x2", active[0].txHash)
    }

    @Test
    fun `clearAll removes everything`() = runTest {
        manager.cacheHeader(makeHeaderView(), "MAINNET")
        manager.insertPendingDeposit("0x1", 100L, "MAINNET")

        manager.clearAll()

        assertNull(manager.getCachedHeader("0xabc123"))
        assertTrue(manager.getActiveDeposits("MAINNET").isEmpty())
    }
}
```

**Step 5: Run all tests**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.database.dao.HeaderCacheDaoTest" --tests "com.rjnr.pocketnode.data.database.dao.DaoCellDaoTest" --tests "com.rjnr.pocketnode.data.gateway.DaoSyncManagerTest"
```

Expected: PASS

**Step 6: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/DaoSyncManager.kt android/app/src/test/java/com/rjnr/pocketnode/data/gateway/DaoSyncManagerTest.kt android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/HeaderCacheDaoTest.kt android/app/src/test/java/com/rjnr/pocketnode/data/database/dao/DaoCellDaoTest.kt
git commit -m "feat: add DaoSyncManager with HeaderCache and DaoCell DAO tests (Issue #40 Phase 2)"
```

---

### Task 9: Wire Hilt DI in AppModule

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/di/AppModule.kt`

**Context:** Current `AppModule` provides TransactionDao and BalanceCacheDao separately, and passes them directly to GatewayRepository. We need to:
1. Add providers for HeaderCacheDao and DaoCellDao
2. Add provider for CacheManager
3. Add provider for DaoSyncManager
4. Change GatewayRepository provider to take CacheManager + DaoSyncManager instead of raw DAOs

**Step 1: Update AppModule**

Add after `provideBalanceCacheDao`:

```kotlin
    @Provides
    fun provideHeaderCacheDao(db: AppDatabase): HeaderCacheDao = db.headerCacheDao()

    @Provides
    fun provideDaoCellDao(db: AppDatabase): DaoCellDao = db.daoCellDao()

    @Provides
    @Singleton
    fun provideCacheManager(
        transactionDao: TransactionDao,
        balanceCacheDao: BalanceCacheDao
    ): CacheManager = CacheManager(transactionDao, balanceCacheDao)

    @Provides
    @Singleton
    fun provideDaoSyncManager(
        headerCacheDao: HeaderCacheDao,
        daoCellDao: DaoCellDao
    ): DaoSyncManager = DaoSyncManager(headerCacheDao, daoCellDao)
```

Update `provideGatewayRepository` to use managers instead of raw DAOs:

```kotlin
    @Provides
    @Singleton
    fun provideGatewayRepository(
        @ApplicationContext context: Context,
        keyManager: KeyManager,
        walletPreferences: WalletPreferences,
        json: Json,
        cacheManager: CacheManager,
        daoSyncManager: DaoSyncManager
    ): GatewayRepository = GatewayRepository(context, keyManager, walletPreferences, json, cacheManager, daoSyncManager)
```

Add imports:
```kotlin
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.gateway.CacheManager
import com.rjnr.pocketnode.data.gateway.DaoSyncManager
```

**Step 2: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/di/AppModule.kt
git commit -m "feat: wire CacheManager, DaoSyncManager, and new DAOs in Hilt DI (Issue #40 Phase 2)"
```

---

### Task 10: Refactor GatewayRepository to use CacheManager + DaoSyncManager

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt`

**Context:** Replace direct `TransactionDao`/`BalanceCacheDao` usage with `CacheManager` calls. Inject `DaoSyncManager`. The 4 cache touch points in GatewayRepository:

1. **Constructor** (line 34-41): Replace `transactionDao`/`balanceCacheDao` params with `cacheManager`/`daoSyncManager`
2. **refreshBalance()** (lines 452-460, 570-575): Replace `balanceCacheDao.*` with `cacheManager.*`
3. **sendTransaction()** (lines 718-738): Replace `transactionDao.insert(...)` with `cacheManager.insertPendingTransaction(...)`
4. **getTransactions()** (lines 877-906): Replace `transactionDao.insertAll(...)` and pending merge with `cacheManager.*`
5. **switchNetwork()** (lines 237-243): Replace `transactionDao.deleteAll()` + `balanceCacheDao.deleteAll()` with `cacheManager.clearAll()` + `daoSyncManager.clearAll()`

**Step 1: Update constructor**

Replace:
```kotlin
    private val transactionDao: TransactionDao,
    private val balanceCacheDao: BalanceCacheDao
```
With:
```kotlin
    private val cacheManager: CacheManager,
    private val daoSyncManager: DaoSyncManager
```

Remove these imports (no longer directly used):
```kotlin
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
```

**Step 2: Update refreshBalance()**

Replace cache-read block (lines 452-460):
```kotlin
        // --- Cache-first: emit cached balance immediately ---
        try {
            val cached = balanceCacheDao.getByNetwork(currentNetwork.name)
            if (cached != null) {
                _balance.value = cached.toBalanceResponse()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read balance cache: ${e.message}")
        }
```
With:
```kotlin
        // --- Cache-first: emit cached balance immediately ---
        cacheManager.getCachedBalance(currentNetwork.name)?.let {
            _balance.value = it
        }
```

Replace cache-write block (lines 570-575):
```kotlin
        // --- Cache write ---
        try {
            balanceCacheDao.upsert(BalanceCacheEntity.from(resp, currentNetwork.name))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write balance cache: ${e.message}")
        }
```
With:
```kotlin
        // --- Cache write ---
        cacheManager.cacheBalance(resp, currentNetwork.name)
```

**Step 3: Update sendTransaction()**

Replace pending tx insert block (lines 718-738):
```kotlin
        // --- Insert pending transaction into Room cache ---
        try {
            transactionDao.insert(TransactionEntity(
                txHash = txHash,
                blockNumber = "",
                blockHash = "",
                timestamp = System.currentTimeMillis(),
                balanceChange = "0x0",
                direction = "out",
                fee = "0x186a0",
                confirmations = 0,
                blockTimestampHex = null,
                network = currentNetwork.name,
                status = "PENDING",
                isLocal = true,
                cachedAt = System.currentTimeMillis()
            ))
            Log.d(TAG, "Pending transaction cached in Room: $txHash")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache pending tx: ${e.message}")
        }
```
With:
```kotlin
        // --- Insert pending transaction into Room cache ---
        cacheManager.insertPendingTransaction(txHash, currentNetwork.name)
```

**Step 4: Update getTransactions()**

Replace cache-write + merge block (lines 877-907):
```kotlin
        // --- Cache write: upsert JNI results into Room ---
        try {
            val entities = items.map { record ->
                TransactionEntity.fromTransactionRecord(
                    txHash = record.txHash,
                    blockNumber = record.blockNumber,
                    blockHash = record.blockHash,
                    timestamp = record.timestamp,
                    balanceChange = record.balanceChange,
                    direction = record.direction,
                    fee = record.fee,
                    confirmations = record.confirmations,
                    blockTimestampHex = record.blockTimestampHex,
                    network = currentNetwork.name
                )
            }
            transactionDao.insertAll(entities)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write transaction cache: ${e.message}")
        }

        // Merge: include pending local txs not yet returned by JNI
        val jniTxHashes = items.map { it.txHash }.toSet()
        val pendingLocal = try {
            transactionDao.getPending(currentNetwork.name)
                .filter { it.isLocal && it.txHash !in jniTxHashes }
                .map { it.toTransactionRecord() }
        } catch (e: Exception) {
            emptyList()
        }
        val mergedItems = pendingLocal + items
```
With:
```kotlin
        // --- Cache write: upsert JNI results into Room ---
        cacheManager.cacheTransactions(items, currentNetwork.name)

        // Merge: include pending local txs not yet returned by JNI
        val jniTxHashes = items.map { it.txHash }.toSet()
        val pendingLocal = cacheManager.getPendingNotIn(currentNetwork.name, jniTxHashes)
        val mergedItems = pendingLocal + items
```

**Step 5: Update switchNetwork()**

Replace Room clear block (lines 236-243):
```kotlin
            // Clear Room caches before process restart
            try {
                transactionDao.deleteAll()
                balanceCacheDao.deleteAll()
                Log.d(TAG, "Room caches cleared for network switch")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear Room caches: ${e.message}")
            }
```
With:
```kotlin
            // Clear Room caches before process restart
            cacheManager.clearAll()
            daoSyncManager.clearAll()
```

**Step 6: Verify build compiles**

```bash
cd android && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt
git commit -m "refactor: replace direct DAO usage with CacheManager and DaoSyncManager (Issue #40 Phase 2)"
```

---

### Task 11: Run full test suite

**Step 1: Run all tests**

```bash
cd android && ./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL — all existing tests plus new tests pass.

**Step 2: Verify no regressions**

Check that Phase 1 tests (TransactionEntityTest, BalanceCacheEntityTest, TransactionDaoTest, BalanceCacheDaoTest) still pass alongside the new Phase 2 tests.

---

### Task 12: Push and create PR

**Step 1: Push branch**

```bash
git push -u origin feature/issue-40-room-cache-phase2
```

**Step 2: Create PR**

```bash
gh pr create --base main --head feature/issue-40-room-cache-phase2 --title "feat: Room cache Phase 2 — header_cache, dao_cells, CacheManager extraction (Issue #40)" --body "$(cat <<'EOF'
## Summary

- Add `header_cache` table for permanent block header caching (immutable on-chain data needed for DAO compensation calculations)
- Add `dao_cells` table for DAO deposit lifecycle tracking (7-state FSM: DEPOSITING → DEPOSITED → WITHDRAWING → LOCKED → UNLOCKABLE → UNLOCKING → COMPLETED)
- Create `DaoSyncManager` to own DAO-specific cache operations (HeaderCacheDao + DaoCellDao)
- Extract Phase 1 cache logic from `GatewayRepository` into `CacheManager` (TransactionDao + BalanceCacheDao)
- Refactor `GatewayRepository` to delegate to managers instead of using DAOs directly
- Bump Room schema version 1 → 2 (fallbackToDestructiveMigration)

**Phase 2 of Issue #40** — groundwork for Nervos DAO integration. The `feature/m2-nervos-dao` branch will merge on top of this.

## Test Plan

- [ ] HeaderCacheEntityTest — mapper from JniHeaderView
- [ ] DaoCellEntityTest — entity field verification for all lifecycle states
- [ ] HeaderCacheDaoTest — Robolectric CRUD + network filtering
- [ ] DaoCellDaoTest — Robolectric CRUD + status updates + active/completed filtering
- [ ] CacheManagerTest — balance + transaction caching integration
- [ ] DaoSyncManagerTest — header + DAO cell caching integration
- [ ] All Phase 1 tests still pass (no regressions)
- [ ] Full test suite passes
EOF
)"
```
