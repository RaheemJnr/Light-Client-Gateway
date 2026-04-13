# Key Storage Redesign — Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the primary key store from EncryptedSharedPreferences to Android Keystore + Room, so key material is encrypted with a Keystore-managed AES key and stored in the SQLite database. The PIN-encrypted backup from Phase 1 provides a safety net during migration.

**Architecture:** A `KeystoreEncryptionManager` manages AES-256-GCM keys in Android Keystore. Key material is encrypted/decrypted through this manager and stored as `ByteArray` columns in a new `key_material` Room table (MIGRATION_3_4). `KeyManager` is updated to read/write via Room first, falling back to ESP during the transition. A one-time migration copies data from ESP → Room on first launch.

**Tech Stack:** Kotlin, Android Keystore (AES-256-GCM), Room 2.8.4, JUnit 4 + Robolectric

**Spec:** `docs/superpowers/specs/2026-04-13-key-storage-redesign-design.md` (Phase 2 section)

**Prerequisite:** Phase 1 complete — `KeyBackupManager` provides PIN-encrypted backups as a safety net.

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `data/crypto/KeystoreEncryptionManager.kt` | Android Keystore AES-256-GCM key management: encrypt/decrypt |
| `data/crypto/KeystoreEncryptionManagerTest.kt` (test) | Unit tests for encrypt/decrypt round-trip |
| `data/database/entity/KeyMaterialEntity.kt` | Room entity for encrypted key material |
| `data/database/dao/KeyMaterialDao.kt` | Room DAO for key material CRUD |
| `data/database/dao/KeyMaterialDaoTest.kt` (test) | DAO tests with in-memory Room DB |
| `data/migration/KeyStoreMigrationHelper.kt` | One-time ESP → Room migration logic |
| `data/migration/KeyStoreMigrationHelperTest.kt` (test) | Migration helper tests |

### Modified Files

| File | Changes |
|------|---------|
| `data/database/AppDatabase.kt` | Add `KeyMaterialEntity` to entities, add `keyMaterialDao()`, bump version to 4 |
| `data/database/Migrations.kt` | Add `MIGRATION_3_4` (create `key_material` table) |
| `data/database/MigrationTest.kt` | Add tests for v3→v4 migration and `key_material` schema |
| `di/AppModule.kt` | Provide `KeystoreEncryptionManager`, `KeyMaterialDao`, `KeyStoreMigrationHelper` |
| `data/wallet/KeyManager.kt` | Add Room-based read/write paths alongside ESP, migration trigger |

---

## Chunk 1: KeystoreEncryptionManager + Room Schema

### Task 1: KeystoreEncryptionManager

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/crypto/KeystoreEncryptionManager.kt`
- Create: `android/app/src/test/java/com/rjnr/pocketnode/data/crypto/KeystoreEncryptionManagerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `KeystoreEncryptionManagerTest.kt`:

```kotlin
package com.rjnr.pocketnode.data.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeystoreEncryptionManagerTest {

    private lateinit var manager: KeystoreEncryptionManager

    @Before
    fun setUp() {
        // Use test mode which generates a random AES key in memory
        // (Android Keystore is not available in Robolectric)
        manager = KeystoreEncryptionManager.createForTest()
    }

    @Test
    fun `encrypt and decrypt round-trip`() {
        val plaintext = "hello world private key".toByteArray()
        val (ciphertext, iv) = manager.encrypt(plaintext)

        assertFalse(ciphertext.contentEquals(plaintext))
        assertEquals(12, iv.size) // GCM IV is 12 bytes

        val decrypted = manager.decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertext each time`() {
        val plaintext = "same input".toByteArray()
        val (ct1, _) = manager.encrypt(plaintext)
        val (ct2, _) = manager.encrypt(plaintext)

        assertFalse(ct1.contentEquals(ct2)) // Different IVs → different ciphertext
    }

    @Test
    fun `decrypt with wrong IV fails`() {
        val plaintext = "secret".toByteArray()
        val (ciphertext, _) = manager.encrypt(plaintext)
        val wrongIv = ByteArray(12) { 0xFF.toByte() }

        try {
            manager.decrypt(ciphertext, wrongIv)
            fail("Should throw on wrong IV")
        } catch (e: Exception) {
            // Expected: AEADBadTagException or similar
        }
    }

    @Test
    fun `encrypt empty data works`() {
        val plaintext = ByteArray(0)
        val (ciphertext, iv) = manager.encrypt(plaintext)
        val decrypted = manager.decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt large data works`() {
        val plaintext = ByteArray(10_000) { (it % 256).toByte() }
        val (ciphertext, iv) = manager.encrypt(plaintext)
        val decrypted = manager.decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManagerTest"`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement KeystoreEncryptionManager**

Create `KeystoreEncryptionManager.kt`:

```kotlin
package com.rjnr.pocketnode.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystoreEncryptionManager @Inject constructor() {

    private var testKey: SecretKey? = null

    private val secretKey: SecretKey
        get() = testKey ?: getOrCreateKeystoreKey()

    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return Pair(ciphertext, iv)
    }

    fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        keyStore.getEntry(KEY_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return keyGen.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pocket_node_key_material"
        private const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256

        /**
         * Create an instance backed by a random in-memory AES key for testing.
         * Android Keystore is not available in Robolectric.
         */
        @VisibleForTesting
        fun createForTest(): KeystoreEncryptionManager {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(KEY_SIZE_BITS)
            return KeystoreEncryptionManager().apply {
                testKey = keyGen.generateKey()
            }
        }
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManagerTest"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd android && git add -A && git commit -m "feat: add KeystoreEncryptionManager for AES-256-GCM key material encryption"
```

---

### Task 2: KeyMaterialEntity + KeyMaterialDao

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/database/entity/KeyMaterialEntity.kt`
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/database/dao/KeyMaterialDao.kt`

- [ ] **Step 1: Create KeyMaterialEntity**

```kotlin
package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "key_material")
data class KeyMaterialEntity(
    @PrimaryKey val walletId: String,
    val encryptedPrivateKey: ByteArray,
    val encryptedMnemonic: ByteArray?,
    val iv: ByteArray,
    val walletType: String,
    val mnemonicBackedUp: Boolean,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyMaterialEntity) return false
        return walletId == other.walletId &&
            encryptedPrivateKey.contentEquals(other.encryptedPrivateKey) &&
            (encryptedMnemonic?.contentEquals(other.encryptedMnemonic ?: byteArrayOf()) ?: (other.encryptedMnemonic == null)) &&
            iv.contentEquals(other.iv) &&
            walletType == other.walletType &&
            mnemonicBackedUp == other.mnemonicBackedUp &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = walletId.hashCode()
        result = 31 * result + encryptedPrivateKey.contentHashCode()
        result = 31 * result + (encryptedMnemonic?.contentHashCode() ?: 0)
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + walletType.hashCode()
        result = 31 * result + mnemonicBackedUp.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
```

- [ ] **Step 2: Create KeyMaterialDao**

```kotlin
package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity

@Dao
interface KeyMaterialDao {

    @Query("SELECT * FROM key_material WHERE walletId = :walletId")
    suspend fun getByWalletId(walletId: String): KeyMaterialEntity?

    @Query("SELECT * FROM key_material")
    suspend fun getAll(): List<KeyMaterialEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KeyMaterialEntity)

    @Query("DELETE FROM key_material WHERE walletId = :walletId")
    suspend fun delete(walletId: String)

    @Query("SELECT COUNT(*) FROM key_material")
    suspend fun count(): Int

    @Query("UPDATE key_material SET mnemonicBackedUp = :backedUp, updatedAt = :updatedAt WHERE walletId = :walletId")
    suspend fun updateMnemonicBackedUp(walletId: String, backedUp: Boolean, updatedAt: Long)
}
```

- [ ] **Step 3: Run build to check compilation**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: FAIL — `KeyMaterialEntity` not in `AppDatabase.entities` yet (expected, we'll fix in Task 3)

- [ ] **Step 4: Commit**

```bash
cd android && git add -A && git commit -m "feat: add KeyMaterialEntity and KeyMaterialDao for encrypted key storage in Room"
```

---

### Task 3: Room MIGRATION_3_4 + AppDatabase Update

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/AppDatabase.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/database/Migrations.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/di/AppModule.kt`
- Modify: `android/app/src/test/java/com/rjnr/pocketnode/data/database/MigrationTest.kt`

- [ ] **Step 1: Add MIGRATION_3_4 to Migrations.kt**

Append after `MIGRATION_2_3`:

```kotlin
/**
 * v3 -> v4: Add key_material table for encrypted key storage (Phase 2 of key storage redesign).
 * Key material moves from EncryptedSharedPreferences to Room, encrypted with Android Keystore AES key.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `key_material` (
                `walletId` TEXT NOT NULL,
                `encryptedPrivateKey` BLOB NOT NULL,
                `encryptedMnemonic` BLOB,
                `iv` BLOB NOT NULL,
                `walletType` TEXT NOT NULL,
                `mnemonicBackedUp` INTEGER NOT NULL DEFAULT 0,
                `updatedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`walletId`)
            )
            """.trimIndent()
        )
    }
}
```

- [ ] **Step 2: Update AppDatabase.kt**

Change version from 3 to 4, add `KeyMaterialEntity` to entities, add `keyMaterialDao()`:

```kotlin
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity

@Database(
    entities = [
        TransactionEntity::class,
        BalanceCacheEntity::class,
        HeaderCacheEntity::class,
        DaoCellEntity::class,
        WalletEntity::class,
        KeyMaterialEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun balanceCacheDao(): BalanceCacheDao
    abstract fun headerCacheDao(): HeaderCacheDao
    abstract fun daoCellDao(): DaoCellDao
    abstract fun walletDao(): WalletDao
    abstract fun keyMaterialDao(): KeyMaterialDao
}
```

- [ ] **Step 3: Update AppModule.kt**

Add `MIGRATION_3_4` to the database builder:

```kotlin
fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "pocket_node.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
```

Add DAO provider:

```kotlin
@Provides
fun provideKeyMaterialDao(db: AppDatabase): KeyMaterialDao = db.keyMaterialDao()
```

Add `KeystoreEncryptionManager` provider:

```kotlin
@Provides
@Singleton
fun provideKeystoreEncryptionManager(): KeystoreEncryptionManager = KeystoreEncryptionManager()
```

Add imports:
```kotlin
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
```

- [ ] **Step 4: Add migration test**

Add to `MigrationTest.kt`:

Update `setUp()` to include `MIGRATION_2_3` and `MIGRATION_3_4`:
```kotlin
db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    .allowMainThreadQueries()
    .build()
```

Add tests:

```kotlin
@Test
fun `v4 database has key_material table accessible`() = runTest {
    assertEquals(0, db.keyMaterialDao().count())
}

@Test
fun `v4 key_material entity round-trip`() = runTest {
    val entity = KeyMaterialEntity(
        walletId = "test-wallet",
        encryptedPrivateKey = byteArrayOf(1, 2, 3),
        encryptedMnemonic = byteArrayOf(4, 5, 6),
        iv = byteArrayOf(7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18),
        walletType = "mnemonic",
        mnemonicBackedUp = true,
        updatedAt = System.currentTimeMillis()
    )
    db.keyMaterialDao().upsert(entity)

    val loaded = db.keyMaterialDao().getByWalletId("test-wallet")
    assertNotNull(loaded)
    assertEquals("test-wallet", loaded!!.walletId)
    assertArrayEquals(byteArrayOf(1, 2, 3), loaded.encryptedPrivateKey)
    assertArrayEquals(byteArrayOf(4, 5, 6), loaded.encryptedMnemonic)
    assertEquals("mnemonic", loaded.walletType)
    assertTrue(loaded.mnemonicBackedUp)
}

@Test
fun `v4 key_material nullable mnemonic`() = runTest {
    val entity = KeyMaterialEntity(
        walletId = "raw-key-wallet",
        encryptedPrivateKey = byteArrayOf(10, 20, 30),
        encryptedMnemonic = null,
        iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
        walletType = "raw_key",
        mnemonicBackedUp = false,
        updatedAt = System.currentTimeMillis()
    )
    db.keyMaterialDao().upsert(entity)

    val loaded = db.keyMaterialDao().getByWalletId("raw-key-wallet")
    assertNotNull(loaded)
    assertNull(loaded!!.encryptedMnemonic)
    assertEquals("raw_key", loaded.walletType)
}

@Test
fun `v4 key_material updateMnemonicBackedUp`() = runTest {
    val entity = KeyMaterialEntity(
        walletId = "w1",
        encryptedPrivateKey = byteArrayOf(1),
        encryptedMnemonic = null,
        iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
        walletType = "mnemonic",
        mnemonicBackedUp = false,
        updatedAt = 100L
    )
    db.keyMaterialDao().upsert(entity)

    db.keyMaterialDao().updateMnemonicBackedUp("w1", true, 200L)

    val loaded = db.keyMaterialDao().getByWalletId("w1")
    assertTrue(loaded!!.mnemonicBackedUp)
    assertEquals(200L, loaded.updatedAt)
}

@Test
fun `v4 key_material delete`() = runTest {
    val entity = KeyMaterialEntity(
        walletId = "to-delete",
        encryptedPrivateKey = byteArrayOf(1),
        encryptedMnemonic = null,
        iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
        walletType = "raw_key",
        mnemonicBackedUp = false,
        updatedAt = 0L
    )
    db.keyMaterialDao().upsert(entity)
    assertEquals(1, db.keyMaterialDao().count())

    db.keyMaterialDao().delete("to-delete")
    assertEquals(0, db.keyMaterialDao().count())
}
```

Add import to MigrationTest: `import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity`

- [ ] **Step 5: Run tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.database.MigrationTest"`
Expected: All PASS

- [ ] **Step 6: Run full test suite**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 7: Commit**

```bash
cd android && git add -A && git commit -m "feat: add MIGRATION_3_4 with key_material table, update AppDatabase to v4"
```

---

## Chunk 2: Migration Helper + KeyManager Integration

### Task 4: KeyStoreMigrationHelper

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/migration/KeyStoreMigrationHelper.kt`
- Create: `android/app/src/test/java/com/rjnr/pocketnode/data/migration/KeyStoreMigrationHelperTest.kt`

- [ ] **Step 1: Write failing tests**

Create `KeyStoreMigrationHelperTest.kt`:

```kotlin
package com.rjnr.pocketnode.data.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyStoreMigrationHelperTest {

    private lateinit var db: AppDatabase
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var walletDao: WalletDao
    private lateinit var encryptionManager: KeystoreEncryptionManager
    private lateinit var migrationPrefs: SharedPreferences
    private lateinit var helper: KeyStoreMigrationHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        keyMaterialDao = db.keyMaterialDao()
        walletDao = db.walletDao()
        encryptionManager = KeystoreEncryptionManager.createForTest()
        migrationPrefs = context.getSharedPreferences("test_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()

        helper = KeyStoreMigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `migrateWallet encrypts and stores key material`() = runTest {
        helper.migrateWallet("wallet-1", "aabb".repeat(16), "word1 word2 word3", "mnemonic", false)

        val entity = keyMaterialDao.getByWalletId("wallet-1")
        assertNotNull(entity)
        assertEquals("mnemonic", entity!!.walletType)
        assertFalse(entity.mnemonicBackedUp)

        // Decrypt and verify round-trip
        val decryptedKey = encryptionManager.decrypt(entity.encryptedPrivateKey, entity.iv)
        assertEquals("aabb".repeat(16), String(decryptedKey, Charsets.UTF_8))
    }

    @Test
    fun `migrateWallet with null mnemonic stores null`() = runTest {
        helper.migrateWallet("wallet-2", "ccdd".repeat(16), null, "raw_key", false)

        val entity = keyMaterialDao.getByWalletId("wallet-2")
        assertNotNull(entity)
        assertNull(entity!!.encryptedMnemonic)
        assertEquals("raw_key", entity.walletType)
    }

    @Test
    fun `isMigrationComplete returns false initially`() {
        assertFalse(helper.isMigrationComplete())
    }

    @Test
    fun `markMigrationComplete sets flag`() {
        helper.markMigrationComplete()
        assertTrue(helper.isMigrationComplete())
    }

    @Test
    fun `readDecryptedKey returns correct data after migration`() = runTest {
        helper.migrateWallet("wallet-1", "aabb".repeat(16), "word1 word2 word3", "mnemonic", true)

        val result = helper.readDecryptedKey("wallet-1")
        assertNotNull(result)
        assertEquals("aabb".repeat(16), result!!.privateKeyHex)
        assertEquals("word1 word2 word3", result.mnemonic)
        assertEquals("mnemonic", result.walletType)
        assertTrue(result.mnemonicBackedUp)
    }

    @Test
    fun `readDecryptedKey returns null for missing wallet`() = runTest {
        val result = helper.readDecryptedKey("nonexistent")
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelperTest"`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement KeyStoreMigrationHelper**

```kotlin
package com.rjnr.pocketnode.data.migration

import android.content.SharedPreferences
import android.util.Log
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import javax.inject.Inject
import javax.inject.Singleton

data class DecryptedKeyData(
    val privateKeyHex: String,
    val mnemonic: String?,
    val walletType: String,
    val mnemonicBackedUp: Boolean
)

@Singleton
class KeyStoreMigrationHelper @Inject constructor(
    private val keyMaterialDao: KeyMaterialDao,
    private val encryptionManager: KeystoreEncryptionManager,
    private val migrationPrefs: SharedPreferences
) {

    suspend fun migrateWallet(
        walletId: String,
        privateKeyHex: String,
        mnemonic: String?,
        walletType: String,
        mnemonicBackedUp: Boolean
    ) {
        val keyBytes = privateKeyHex.toByteArray(Charsets.UTF_8)
        val (encryptedKey, iv) = encryptionManager.encrypt(keyBytes)

        val encryptedMnemonic = mnemonic?.let {
            encryptionManager.encrypt(it.toByteArray(Charsets.UTF_8)).first
        }

        // Use the same IV for the key — mnemonic gets its own IV embedded
        // Actually, each encrypt() call generates a new IV. We need to store
        // both IVs. For simplicity, concatenate key ciphertext with its IV,
        // and store mnemonic separately with its own IV.
        // Revised: store a single IV for the key. For mnemonic, prepend its IV.

        val mnemonicWithIv = mnemonic?.let {
            val mnemonicBytes = it.toByteArray(Charsets.UTF_8)
            val (encMnemonic, mnemonicIv) = encryptionManager.encrypt(mnemonicBytes)
            mnemonicIv + encMnemonic // 12-byte IV prefix + ciphertext
        }

        val entity = KeyMaterialEntity(
            walletId = walletId,
            encryptedPrivateKey = encryptedKey,
            encryptedMnemonic = mnemonicWithIv,
            iv = iv,
            walletType = walletType,
            mnemonicBackedUp = mnemonicBackedUp,
            updatedAt = System.currentTimeMillis()
        )

        keyMaterialDao.upsert(entity)
    }

    suspend fun readDecryptedKey(walletId: String): DecryptedKeyData? {
        val entity = keyMaterialDao.getByWalletId(walletId) ?: return null

        return try {
            val keyBytes = encryptionManager.decrypt(entity.encryptedPrivateKey, entity.iv)
            val privateKeyHex = String(keyBytes, Charsets.UTF_8)

            val mnemonic = entity.encryptedMnemonic?.let { combined ->
                val mnemonicIv = combined.sliceArray(0 until 12)
                val mnemonicCiphertext = combined.sliceArray(12 until combined.size)
                String(encryptionManager.decrypt(mnemonicCiphertext, mnemonicIv), Charsets.UTF_8)
            }

            DecryptedKeyData(
                privateKeyHex = privateKeyHex,
                mnemonic = mnemonic,
                walletType = entity.walletType,
                mnemonicBackedUp = entity.mnemonicBackedUp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt key material for $walletId", e)
            null
        }
    }

    fun isMigrationComplete(): Boolean {
        return migrationPrefs.getBoolean(KEY_MIGRATION_COMPLETE, false)
    }

    fun markMigrationComplete() {
        migrationPrefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true).commit()
    }

    companion object {
        private const val TAG = "KeyStoreMigrationHelper"
        private const val KEY_MIGRATION_COMPLETE = "esp_to_room_migration_complete"
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelperTest"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd android && git add -A && git commit -m "feat: add KeyStoreMigrationHelper for ESP-to-Room key migration"
```

---

### Task 5: Wire KeyStoreMigrationHelper into DI

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/di/AppModule.kt`

- [ ] **Step 1: Add provider for migration prefs and helper**

```kotlin
@Provides
@Singleton
@javax.inject.Named("migrationPrefs")
fun provideMigrationPrefs(@ApplicationContext context: Context): SharedPreferences =
    context.getSharedPreferences("key_migration", Context.MODE_PRIVATE)

@Provides
@Singleton
fun provideKeyStoreMigrationHelper(
    keyMaterialDao: KeyMaterialDao,
    encryptionManager: KeystoreEncryptionManager,
    @javax.inject.Named("migrationPrefs") migrationPrefs: SharedPreferences
): KeyStoreMigrationHelper = KeyStoreMigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
```

Add import: `import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper`

- [ ] **Step 2: Run build**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "feat: wire KeyStoreMigrationHelper and KeystoreEncryptionManager into DI"
```

---

### Task 6: KeyManager Room Integration

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/wallet/KeyManager.kt`

- [ ] **Step 1: Add Room-based read/write alongside ESP**

Add new dependencies to KeyManager (injected via setter like `setBackupManager`):

```kotlin
@VisibleForTesting
internal var keyStoreMigrationHelper: KeyStoreMigrationHelper? = null

@Inject
fun setMigrationHelper(helper: KeyStoreMigrationHelper) {
    this.keyStoreMigrationHelper = helper
}
```

Add a method that reads key material, preferring Room over ESP:

```kotlin
fun getPrivateKeyPreferRoom(walletId: String): ByteArray {
    val helper = keyStoreMigrationHelper
    if (helper != null && helper.isMigrationComplete()) {
        val data = kotlinx.coroutines.runBlocking { helper.readDecryptedKey(walletId) }
        if (data != null) {
            return org.nervos.ckb.utils.Numeric.hexStringToByteArray(data.privateKeyHex)
        }
    }
    // Fallback to ESP
    return if (walletId == "default") getPrivateKey() else getPrivateKeyForWallet(walletId)
}

fun getMnemonicPreferRoom(walletId: String): List<String>? {
    val helper = keyStoreMigrationHelper
    if (helper != null && helper.isMigrationComplete()) {
        val data = kotlinx.coroutines.runBlocking { helper.readDecryptedKey(walletId) }
        if (data?.mnemonic != null) {
            return data.mnemonic.split(" ")
        }
    }
    // Fallback to ESP
    return if (walletId == "default") getMnemonic() else getMnemonicForWallet(walletId)
}
```

Add a method to trigger the one-time ESP → Room migration:

```kotlin
suspend fun migrateEspToRoom(walletDao: com.rjnr.pocketnode.data.database.dao.WalletDao) {
    val helper = keyStoreMigrationHelper ?: return
    if (helper.isMigrationComplete()) return

    try {
        val wallets = walletDao.getAll()
        for (wallet in wallets) {
            val privKeyHex = try {
                getPrivateKeyForWallet(wallet.walletId)
                    .joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read ESP key for ${wallet.walletId}, skipping", e)
                continue
            }

            val mnemonic = getMnemonicForWallet(wallet.walletId)?.joinToString(" ")
            val walletType = try {
                getWalletPrefs(wallet.walletId).getString(KEY_WALLET_TYPE, WALLET_TYPE_RAW_KEY) ?: WALLET_TYPE_RAW_KEY
            } catch (e: Exception) {
                WALLET_TYPE_RAW_KEY
            }
            val backed = try {
                hasMnemonicBackupForWallet(wallet.walletId)
            } catch (e: Exception) {
                false
            }

            helper.migrateWallet(wallet.walletId, privKeyHex, mnemonic, walletType, backed)

            // Verify round-trip
            val check = helper.readDecryptedKey(wallet.walletId)
            if (check == null || check.privateKeyHex != privKeyHex) {
                Log.e(TAG, "Round-trip verification failed for ${wallet.walletId}")
                return // Abort migration — don't mark complete
            }
        }

        // Also migrate the "default" legacy wallet if it exists
        if (hasWallet() && wallets.none { it.walletId == "default" }) {
            val privKeyHex = prefs.getString(KEY_PRIVATE_KEY, null)
            if (privKeyHex != null) {
                val mnemonic = prefs.getString(KEY_MNEMONIC, null)
                val walletType = getWalletType()
                val backed = hasMnemonicBackup()
                helper.migrateWallet("default", privKeyHex, mnemonic, walletType, backed)
            }
        }

        helper.markMigrationComplete()
        Log.i(TAG, "ESP to Room migration complete for ${wallets.size} wallets")
    } catch (e: Exception) {
        Log.e(TAG, "ESP to Room migration failed", e)
        // Don't mark complete — will retry next launch
    }
}
```

Note: `getWalletPrefs` is currently private. It needs to be changed to `internal` so the migration can access it, or the method can be accessed through the existing public `getPrivateKeyForWallet`/`getMnemonicForWallet` methods which already call `getWalletPrefs` internally.

- [ ] **Step 2: Run build**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "feat: add Room-based key read paths to KeyManager with ESP fallback and migration trigger"
```

---

### Task 7: Trigger Migration on Startup

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt`

- [ ] **Step 1: Add migration call to startup**

Find the `GatewayRepository` initialization or startup method. Add a call to `keyManager.migrateEspToRoom(walletDao)` during the init sequence (after the existing `WalletMigrationHelper` runs).

This should run inside a coroutine scope. If `GatewayRepository` has an init block or a `startNode()` method, add:

```kotlin
// After wallet migration helper:
viewModelScope.launch(Dispatchers.IO) {
    keyManager.migrateEspToRoom(walletDao)
}
```

Or if GatewayRepository uses its own coroutine scope:

```kotlin
scope.launch {
    keyManager.migrateEspToRoom(walletDao)
}
```

Read the file to determine the exact placement. The migration must run AFTER `WalletMigrationHelper.migrateIfNeeded()` (which creates wallet entries in Room) and BEFORE any key reads happen.

- [ ] **Step 2: Add WalletDao dependency to GatewayRepository if not already present**

Check the constructor. If `walletDao` is not directly available, pass it through or access via `AppDatabase`.

- [ ] **Step 3: Run build + tests**

Run: `cd android && ./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: All PASS, BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
cd android && git add -A && git commit -m "feat: trigger ESP-to-Room key migration on startup after wallet migration"
```

---

### Task 8: Update KeyManager Write Paths to Dual-Write to Room

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/wallet/KeyManager.kt`

- [ ] **Step 1: Add Room write alongside ESP write in mutation methods**

Add a private helper:

```kotlin
private fun writeToRoomIfMigrated(walletId: String, privateKeyHex: String, mnemonic: String?, walletType: String, mnemonicBackedUp: Boolean) {
    val helper = keyStoreMigrationHelper ?: return
    if (!helper.isMigrationComplete()) return
    try {
        kotlinx.coroutines.runBlocking {
            helper.migrateWallet(walletId, privateKeyHex, mnemonic, walletType, mnemonicBackedUp)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Room write failed for $walletId", e)
    }
}
```

Add calls to `writeToRoomIfMigrated` in each mutation method, right after the `writeBackupIfPinAvailable` calls:
- `generateWalletWithMnemonic()` — write with the hex and words
- `importWalletFromMnemonic()` — write with hex and words
- `savePrivateKey()` — write with hex and null mnemonic
- `storeKeysForWallet()` — write with wallet ID
- `setMnemonicBackedUp()` / `setMnemonicBackedUpForWallet()` — use Room DAO's `updateMnemonicBackedUp` instead of full re-write
- `deleteWallet()` / `deleteWalletKeys()` — delete from Room

- [ ] **Step 2: Run tests**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "feat: dual-write key material to Room alongside ESP on every mutation"
```

---

### Task 9: Final Integration Test + Cleanup

- [ ] **Step 1: Run full test suite**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 2: Run debug build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Add ProGuard keep rules**

Add to `proguard-rules.pro`:

```
# Keep Room entities for key_material table
-keep class com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity { *; }
-keep class com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager { *; }
```

- [ ] **Step 4: Run release build to verify ProGuard**

Run: `cd android && ./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd android && git add -A && git commit -m "chore: add ProGuard keep rules for KeyMaterialEntity and KeystoreEncryptionManager"
```

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | KeystoreEncryptionManager (encrypt/decrypt) | KeystoreEncryptionManager.kt (new) |
| 2 | KeyMaterialEntity + KeyMaterialDao | KeyMaterialEntity.kt, KeyMaterialDao.kt (new) |
| 3 | MIGRATION_3_4 + AppDatabase v4 + DI | Migrations.kt, AppDatabase.kt, AppModule.kt |
| 4 | KeyStoreMigrationHelper (ESP → Room) | KeyStoreMigrationHelper.kt (new) |
| 5 | Wire migration helper into DI | AppModule.kt |
| 6 | KeyManager Room read paths + migration trigger | KeyManager.kt |
| 7 | Startup migration trigger | GatewayRepository.kt |
| 8 | KeyManager Room write paths (dual-write) | KeyManager.kt |
| 9 | Final integration + ProGuard | proguard-rules.pro |

**Dependencies:** Tasks 1, 2 are independent. Task 3 depends on 1+2. Task 4 depends on 1+3. Task 5 depends on 4. Tasks 6-7 depend on 5. Task 8 depends on 6. Task 9 is last.

**Parallelizable groups:**
- Group A: Tasks 1, 2
- Group B (after A): Task 3
- Group C (after B): Tasks 4, 5
- Group D (after C): Tasks 6, 7
- Group E (after D): Task 8
- Group F: Task 9
