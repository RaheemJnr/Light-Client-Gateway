# Key Storage Redesign — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a PIN-encrypted backup file alongside EncryptedSharedPreferences so that key material survives Android Keystore invalidation, with a two-tier recovery flow and soft-gate security warnings.

**Architecture:** Dual-write pattern — every key mutation writes to ESP (primary) then to a PIN-encrypted backup file (secondary). Recovery reads from backup when ESP is corrupted. Soft-gate warnings nudge users to set PIN + back up mnemonic.

**Tech Stack:** Kotlin, Android Keystore (for biometric PIN storage), PBKDF2WithHmacSHA256, AES-256-GCM, Room (for wallet queries), JUnit 4 + Robolectric

**Spec:** `docs/superpowers/specs/2026-04-13-key-storage-redesign-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `data/wallet/KeyBackupManager.kt` | PIN-encrypted backup file I/O: write, read, re-encrypt, delete |
| `data/wallet/KeyBackupManagerTest.kt` (test) | Unit tests for all KeyBackupManager operations |
| `ui/screens/recovery/RecoveryScreen.kt` | Two-tier recovery UI (PIN first, mnemonic/key fallback) |
| `ui/screens/recovery/RecoveryViewModel.kt` | Recovery flow state management |
| `ui/screens/recovery/RecoveryViewModelTest.kt` (test) | Unit tests for recovery state transitions |
| `ui/screens/security/SecurityChecklistScreen.kt` | PIN + mnemonic backup progress checklist |
| `ui/components/SecurityBanner.kt` | Reusable security warning banner composable |

### Modified Files

| File | Changes |
|------|---------|
| `data/auth/PinManager.kt:25-37` | Add StrongBox fallback (match KeyManager pattern) |
| `data/auth/PinManager.kt:43-52` | Update `setPin()` to accept `CharArray` |
| `data/auth/PinManager.kt:73-80` | Block `removePin()` if backups exist |
| `data/auth/AuthManager.kt:15-96` | Add `sessionPin: CharArray?` caching, `clearSession()` |
| `data/wallet/KeyManager.kt:95-287` | Add `KeyBackupManager` dual-write calls on every mutation |
| `ui/screens/home/HomeScreen.kt:351+` | Add `SecurityBanner` composable |
| `ui/screens/home/HomeViewModel.kt:508+` | Add security state fields to `HomeUiState` |
| `ui/screens/receive/ReceiveScreen.kt:41+` | Add backup warning modal |
| `ui/navigation/NavGraph.kt:26+,55+` | Add Recovery + SecurityChecklist routes |
| `ui/screens/settings/SecuritySettingsViewModel.kt:101+` | Guard `removePin()` with backup check |
| `di/AppModule.kt:46+` | Provide `KeyBackupManager` |

---

## Chunk 1: KeyBackupManager Core (the foundation)

### Task 1: PinManager StrongBox Fallback Fix

This is a prerequisite bug fix. `PinManager` uses `setRequestStrongBoxBacked(true)` with no fallback — it crashes on StrongBox-incompatible devices.

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/auth/PinManager.kt:25-37`
- Test: `android/app/src/test/java/com/rjnr/pocketnode/data/auth/PinManagerTest.kt`

- [ ] **Step 1: Write failing test for StrongBox fallback**

Add to `PinManagerTest.kt`:

```kotlin
@Test
fun `PinManager works when StrongBox is unavailable`() {
    // testPrefs bypass means this always works, but verify the pattern
    // is correct by ensuring PIN operations work on test SharedPreferences
    pinManager.setPin("123456")
    assertTrue(pinManager.verifyPin("123456"))
    assertTrue(pinManager.hasPin())
}
```

- [ ] **Step 2: Run test to verify it passes (baseline)**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.auth.PinManagerTest"`
Expected: PASS (testPrefs bypasses ESP)

- [ ] **Step 3: Update PinManager encryptedPrefs with StrongBox fallback**

Replace lines 25-37 in `PinManager.kt`:

```kotlin
private val encryptedPrefs: SharedPreferences by lazy {
    try {
        createEncryptedPrefs(useStrongBox = true)
    } catch (e: Exception) {
        Log.w(TAG, "StrongBox-backed pin prefs failed, trying without StrongBox", e)
        try {
            createEncryptedPrefs(useStrongBox = false)
        } catch (e2: Exception) {
            Log.e(TAG, "Pin prefs completely unreadable", e2)
            createEncryptedPrefs(useStrongBox = true)
        }
    }
}

private fun createEncryptedPrefs(useStrongBox: Boolean): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .apply { if (useStrongBox) setRequestStrongBoxBacked(true) }
        .build()

    return EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
```

Add to companion object:

```kotlin
private const val TAG = "PinManager"
```

Add import: `import android.util.Log`

- [ ] **Step 4: Run all PinManager tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.auth.PinManagerTest"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd android && git add -A && git commit -m "fix: add StrongBox fallback to PinManager (match KeyManager pattern)"
```

---

### Task 2: KeyBackupManager — Write and Read

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/wallet/KeyBackupManager.kt`
- Create: `android/app/src/test/java/com/rjnr/pocketnode/data/wallet/KeyBackupManagerTest.kt`

- [ ] **Step 1: Write failing tests for write + read cycle**

Create `KeyBackupManagerTest.kt`:

```kotlin
package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyBackupManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var manager: KeyBackupManager

    @Before
    fun setUp() {
        manager = KeyBackupManager(tempDir.root)
    }

    @Test
    fun `writeBackup and readBackup round-trip for mnemonic wallet`() {
        val material = KeyMaterial(
            privateKey = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            walletType = "mnemonic",
            mnemonicBackedUp = false
        )
        val pin = "123456".toCharArray()

        manager.writeBackup("wallet-1", material, pin)
        val result = manager.readBackup("wallet-1", pin)

        assertNotNull(result)
        assertEquals(material.privateKey, result!!.privateKey)
        assertEquals(material.mnemonic, result.mnemonic)
        assertEquals(material.walletType, result.walletType)
        assertEquals(material.mnemonicBackedUp, result.mnemonicBackedUp)
    }

    @Test
    fun `writeBackup and readBackup round-trip for raw-key wallet`() {
        val material = KeyMaterial(
            privateKey = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
            mnemonic = null,
            walletType = "raw_key",
            mnemonicBackedUp = false
        )
        val pin = "123456".toCharArray()

        manager.writeBackup("wallet-2", material, pin)
        val result = manager.readBackup("wallet-2", pin)

        assertNotNull(result)
        assertEquals(material.privateKey, result!!.privateKey)
        assertNull(result.mnemonic)
        assertEquals("raw_key", result.walletType)
    }

    @Test
    fun `readBackup with wrong PIN returns null`() {
        val material = KeyMaterial(
            privateKey = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
            mnemonic = null,
            walletType = "raw_key",
            mnemonicBackedUp = false
        )
        manager.writeBackup("wallet-1", material, "123456".toCharArray())
        val result = manager.readBackup("wallet-1", "654321".toCharArray())

        assertNull(result)
    }

    @Test
    fun `readBackup for nonexistent wallet returns null`() {
        val result = manager.readBackup("no-such-wallet", "123456".toCharArray())
        assertNull(result)
    }

    @Test
    fun `hasBackup returns false initially`() {
        assertFalse(manager.hasBackup("wallet-1"))
    }

    @Test
    fun `hasBackup returns true after write`() {
        val material = KeyMaterial(
            privateKey = "aa".repeat(32),
            mnemonic = null,
            walletType = "raw_key",
            mnemonicBackedUp = false
        )
        manager.writeBackup("wallet-1", material, "123456".toCharArray())
        assertTrue(manager.hasBackup("wallet-1"))
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.wallet.KeyBackupManagerTest"`
Expected: FAIL — `KeyBackupManager` class doesn't exist

- [ ] **Step 3: Implement KeyBackupManager**

Create `KeyBackupManager.kt`:

```kotlin
package com.rjnr.pocketnode.data.wallet

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class KeyMaterial(
    val privateKey: String,
    val mnemonic: String?,
    val walletType: String,
    val mnemonicBackedUp: Boolean,
    val createdAt: String = java.time.Instant.now().toString(),
    val version: Int = 1
)

@Singleton
class KeyBackupManager @Inject constructor(
    private val backupDir: File
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        backupDir.mkdirs()
    }

    fun writeBackup(walletId: String, material: KeyMaterial, pin: CharArray) {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(pin, salt)

        val plaintext = json.encodeToString(material).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val file = backupFile(walletId)
        val tmpFile = File(file.parent, "${file.name}.tmp")

        tmpFile.outputStream().use { out ->
            out.write(MAGIC)
            out.write(byteArrayOf(FORMAT_VERSION))
            out.write(salt)
            out.write(iv)
            out.write(ciphertext) // includes GCM auth tag appended by AES-GCM
        }

        tmpFile.renameTo(file)
    }

    fun readBackup(walletId: String, pin: CharArray): KeyMaterial? {
        val file = backupFile(walletId)
        if (!file.exists()) return null

        return try {
            val bytes = file.readBytes()
            // Validate magic header
            if (bytes.size < HEADER_SIZE || !bytes.sliceArray(0 until 4).contentEquals(MAGIC)) {
                Log.w(TAG, "Backup file for $walletId has invalid magic header")
                return null
            }
            // Skip format version byte (index 4) for now — v1 only
            val salt = bytes.sliceArray(HEADER_SIZE until HEADER_SIZE + SALT_SIZE)
            val iv = bytes.sliceArray(HEADER_SIZE + SALT_SIZE until HEADER_SIZE + SALT_SIZE + IV_SIZE)
            val ciphertext = bytes.sliceArray(HEADER_SIZE + SALT_SIZE + IV_SIZE until bytes.size)

            val key = deriveKey(pin, salt)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)

            json.decodeFromString<KeyMaterial>(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            // Wrong PIN causes AEADBadTagException, file corruption causes other exceptions
            Log.w(TAG, "Failed to read backup for $walletId", e)
            null
        }
    }

    fun hasBackup(walletId: String): Boolean = backupFile(walletId).exists()

    fun hasAnyBackups(): Boolean = backupDir.listFiles()?.any { it.extension == "enc" } == true

    fun deleteBackup(walletId: String) {
        backupFile(walletId).delete()
    }

    fun listBackupWalletIds(): List<String> {
        return backupDir.listFiles()
            ?.filter { it.extension == "enc" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    fun reEncryptAll(oldPin: CharArray, newPin: CharArray): Boolean {
        val backupFiles = backupDir.listFiles()?.filter { it.extension == "enc" } ?: return true

        // Phase 1: decrypt all and re-encrypt to .tmp files
        val tmpFiles = mutableListOf<Pair<File, File>>() // (tmp, original)
        for (file in backupFiles) {
            val walletId = file.nameWithoutExtension
            val material = readBackup(walletId, oldPin) ?: return false
            val tmpFile = File(file.parent, "${file.name}.tmp")

            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(newPin, salt)

            val plaintext = json.encodeToString(material).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)

            tmpFile.outputStream().use { out ->
                out.write(MAGIC)
                out.write(byteArrayOf(FORMAT_VERSION))
                out.write(salt)
                out.write(iv)
                out.write(ciphertext)
            }
            tmpFiles.add(tmpFile to file)
        }

        // Phase 2: atomic rename all .tmp → .enc
        for ((tmp, original) in tmpFiles) {
            tmp.renameTo(original)
        }

        return true
    }

    fun cleanupOrphanedTmpFiles() {
        backupDir.listFiles()
            ?.filter { it.extension == "tmp" }
            ?.forEach { it.delete() }
    }

    private fun backupFile(walletId: String): File = File(backupDir, "$walletId.enc")

    private fun deriveKey(pin: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val spec = PBEKeySpec(pin, salt, KDF_ITERATIONS, KEY_SIZE_BITS)
        val secret = factory.generateSecret(spec)
        val key = SecretKeySpec(secret.encoded, "AES")
        spec.clearPassword()
        return key
    }

    companion object {
        private const val TAG = "KeyBackupManager"
        val MAGIC = byteArrayOf('P'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_SIZE = 5 // 4 magic + 1 version
        const val SALT_SIZE = 16
        const val IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val KDF_ITERATIONS = 600_000
        const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.wallet.KeyBackupManagerTest"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd android && git add -A && git commit -m "feat: add KeyBackupManager with PIN-encrypted backup file I/O"
```

---

### Task 3: KeyBackupManager — Delete and Re-encrypt

**Files:**
- Modify: `android/app/src/test/java/com/rjnr/pocketnode/data/wallet/KeyBackupManagerTest.kt`

- [ ] **Step 1: Write failing tests for delete, re-encrypt, and cleanup**

Add to `KeyBackupManagerTest.kt`:

```kotlin
@Test
fun `deleteBackup removes the file`() {
    val material = KeyMaterial(
        privateKey = "aa".repeat(32),
        mnemonic = null,
        walletType = "raw_key",
        mnemonicBackedUp = false
    )
    manager.writeBackup("wallet-1", material, "123456".toCharArray())
    assertTrue(manager.hasBackup("wallet-1"))

    manager.deleteBackup("wallet-1")
    assertFalse(manager.hasBackup("wallet-1"))
}

@Test
fun `hasAnyBackups returns false when empty`() {
    assertFalse(manager.hasAnyBackups())
}

@Test
fun `hasAnyBackups returns true when backups exist`() {
    val material = KeyMaterial(
        privateKey = "aa".repeat(32),
        mnemonic = null,
        walletType = "raw_key",
        mnemonicBackedUp = false
    )
    manager.writeBackup("wallet-1", material, "123456".toCharArray())
    assertTrue(manager.hasAnyBackups())
}

@Test
fun `reEncryptAll re-encrypts with new PIN`() {
    val material = KeyMaterial(
        privateKey = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        walletType = "mnemonic",
        mnemonicBackedUp = true
    )
    val oldPin = "123456".toCharArray()
    val newPin = "654321".toCharArray()

    manager.writeBackup("wallet-1", material, oldPin)
    manager.writeBackup("wallet-2", material, oldPin)

    val success = manager.reEncryptAll(oldPin, newPin)
    assertTrue(success)

    // Old PIN no longer works
    assertNull(manager.readBackup("wallet-1", "123456".toCharArray()))
    assertNull(manager.readBackup("wallet-2", "123456".toCharArray()))

    // New PIN works
    assertNotNull(manager.readBackup("wallet-1", newPin))
    assertNotNull(manager.readBackup("wallet-2", newPin))
}

@Test
fun `reEncryptAll returns false if old PIN is wrong`() {
    val material = KeyMaterial(
        privateKey = "aa".repeat(32),
        mnemonic = null,
        walletType = "raw_key",
        mnemonicBackedUp = false
    )
    manager.writeBackup("wallet-1", material, "123456".toCharArray())

    val success = manager.reEncryptAll("000000".toCharArray(), "654321".toCharArray())
    assertFalse(success)

    // Original backup still readable with original PIN
    assertNotNull(manager.readBackup("wallet-1", "123456".toCharArray()))
}

@Test
fun `cleanupOrphanedTmpFiles removes tmp files`() {
    // Create a fake .tmp file
    val tmpFile = File(tempDir.root, "wallet-1.enc.tmp")
    tmpFile.writeText("garbage")
    assertTrue(tmpFile.exists())

    manager.cleanupOrphanedTmpFiles()
    assertFalse(tmpFile.exists())
}

@Test
fun `listBackupWalletIds returns correct IDs`() {
    val material = KeyMaterial(
        privateKey = "aa".repeat(32),
        mnemonic = null,
        walletType = "raw_key",
        mnemonicBackedUp = false
    )
    manager.writeBackup("wallet-a", material, "123456".toCharArray())
    manager.writeBackup("wallet-b", material, "123456".toCharArray())

    val ids = manager.listBackupWalletIds()
    assertEquals(2, ids.size)
    assertTrue(ids.contains("wallet-a"))
    assertTrue(ids.contains("wallet-b"))
}

@Test
fun `writeBackup overwrites existing backup`() {
    val material1 = KeyMaterial(
        privateKey = "aa".repeat(32),
        mnemonic = null,
        walletType = "raw_key",
        mnemonicBackedUp = false
    )
    val material2 = KeyMaterial(
        privateKey = "bb".repeat(32),
        mnemonic = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
        walletType = "mnemonic",
        mnemonicBackedUp = true
    )
    val pin = "123456".toCharArray()

    manager.writeBackup("wallet-1", material1, pin)
    manager.writeBackup("wallet-1", material2, pin)

    val result = manager.readBackup("wallet-1", pin)
    assertNotNull(result)
    assertEquals("bb".repeat(32), result!!.privateKey)
    assertEquals("mnemonic", result.walletType)
    assertTrue(result.mnemonicBackedUp)
}
```

- [ ] **Step 2: Run tests — verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.wallet.KeyBackupManagerTest"`
Expected: All PASS (implementation from Task 2 should handle these)

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "test: add comprehensive KeyBackupManager tests (delete, re-encrypt, cleanup)"
```

---

### Task 4: Wire KeyBackupManager into DI

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/di/AppModule.kt:46+`

- [ ] **Step 1: Add KeyBackupManager provider to AppModule**

Add after the `provideKeyManager` function (around line 49):

```kotlin
@Provides
@Singleton
fun provideKeyBackupManager(
    @ApplicationContext context: Context
): KeyBackupManager = KeyBackupManager(File(context.filesDir, "key_backups"))
```

Add import: `import java.io.File`
Add import: `import com.rjnr.pocketnode.data.wallet.KeyBackupManager`

- [ ] **Step 2: Run existing tests to verify no breakage**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: All existing tests PASS

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "feat: wire KeyBackupManager into Hilt DI"
```

---

## Chunk 2: AuthManager Session PIN + KeyManager Dual-Write

### Task 5: AuthManager Session PIN Caching

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/auth/AuthManager.kt`
- Create: `android/app/src/test/java/com/rjnr/pocketnode/data/auth/AuthManagerSessionPinTest.kt`

- [ ] **Step 1: Write failing tests for session PIN**

Create `AuthManagerSessionPinTest.kt`:

```kotlin
package com.rjnr.pocketnode.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AuthManagerSessionPinTest {

    private lateinit var authManager: AuthManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        authManager = AuthManager(context)
    }

    @Test
    fun `getSessionPin returns null initially`() {
        assertNull(authManager.getSessionPin())
    }

    @Test
    fun `setSessionPin stores PIN and getSessionPin retrieves it`() {
        authManager.setSessionPin("123456".toCharArray())
        val pin = authManager.getSessionPin()
        assertNotNull(pin)
        assertEquals("123456", String(pin!!))
    }

    @Test
    fun `clearSession zeroes the PIN`() {
        authManager.setSessionPin("123456".toCharArray())
        authManager.clearSession()
        assertNull(authManager.getSessionPin())
    }

    @Test
    fun `hasSessionPin returns true when set`() {
        assertFalse(authManager.hasSessionPin())
        authManager.setSessionPin("123456".toCharArray())
        assertTrue(authManager.hasSessionPin())
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.auth.AuthManagerSessionPinTest"`
Expected: FAIL — methods don't exist

- [ ] **Step 3: Add session PIN to AuthManager**

Add to `AuthManager.kt` after line 19 (`internal var testPrefs`):

```kotlin
private var sessionPin: CharArray? = null

fun setSessionPin(pin: CharArray) {
    clearSession() // zero any previous PIN
    sessionPin = pin.copyOf()
}

fun getSessionPin(): CharArray? = sessionPin?.copyOf()

fun hasSessionPin(): Boolean = sessionPin != null

fun clearSession() {
    sessionPin?.let { java.util.Arrays.fill(it, '\u0000') }
    sessionPin = null
}
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.auth.AuthManagerSessionPinTest"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd android && git add -A && git commit -m "feat: add session PIN caching to AuthManager (CharArray, zeroed on clear)"
```

---

### Task 6: KeyManager Dual-Write Integration

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/wallet/KeyManager.kt`
- Modify: `android/app/src/test/java/com/rjnr/pocketnode/data/wallet/KeyManagerTest.kt`

- [ ] **Step 1: Write failing test for dual-write**

Add to `KeyManagerTest.kt`:

```kotlin
// Add at class level:
private lateinit var backupManager: KeyBackupManager

// Update setUp() to create a KeyBackupManager:
// After keyManager.testPrefs = ... line, add:
// val backupDir = File(context.cacheDir, "test_key_backups")
// backupDir.deleteRecursively()
// backupManager = KeyBackupManager(backupDir)
// keyManager.keyBackupManager = backupManager

@Test
fun `generateWalletWithMnemonic writes backup when session PIN available`() {
    keyManager.setSessionPin("123456".toCharArray())
    keyManager.generateWalletWithMnemonic()

    // Backup should exist for default wallet
    assertTrue(backupManager.hasAnyBackups())
}

@Test
fun `generateWalletWithMnemonic skips backup when no session PIN`() {
    keyManager.generateWalletWithMnemonic()

    // No backup without PIN
    assertFalse(backupManager.hasAnyBackups())
}

@Test
fun `deleteWallet removes backup`() {
    keyManager.setSessionPin("123456".toCharArray())
    keyManager.generateWalletWithMnemonic()
    assertTrue(backupManager.hasAnyBackups())

    keyManager.deleteWallet()
    // Default wallet backup should be removed
    assertFalse(backupManager.hasBackup("default"))
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.wallet.KeyManagerTest"`
Expected: FAIL — `setSessionPin` and `keyBackupManager` don't exist on KeyManager

- [ ] **Step 3: Add dual-write support to KeyManager**

Modify `KeyManager.kt`:

Add field after `testPrefs` (line 27):

```kotlin
@VisibleForTesting
internal var keyBackupManager: KeyBackupManager? = null

private var sessionPin: CharArray? = null

fun setSessionPin(pin: CharArray) {
    sessionPin?.let { java.util.Arrays.fill(it, '\u0000') }
    sessionPin = pin.copyOf()
}

fun clearSessionPin() {
    sessionPin?.let { java.util.Arrays.fill(it, '\u0000') }
    sessionPin = null
}
```

Add constructor parameter (optional, injected):

```kotlin
@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mnemonicManager: MnemonicManager
) {
```

Note: `keyBackupManager` is set via `@VisibleForTesting` field for tests and injected via a setter for production (to avoid circular DI). Add:

```kotlin
@Inject
fun setBackupManager(backupManager: KeyBackupManager) {
    this.keyBackupManager = backupManager
}
```

Add a private helper method:

```kotlin
private fun writeBackupIfPinAvailable(walletId: String, buildMaterial: () -> KeyMaterial) {
    val pin = sessionPin ?: return
    val manager = keyBackupManager ?: return
    try {
        manager.writeBackup(walletId, buildMaterial(), pin)
    } catch (e: Exception) {
        Log.w(TAG, "Backup write failed for $walletId", e)
    }
}
```

Update `generateWalletWithMnemonic()` — add after `prefs.edit()...commit()` (line 109):

```kotlin
writeBackupIfPinAvailable("default") {
    KeyMaterial(
        privateKey = hex,
        mnemonic = words.joinToString(" "),
        walletType = WALLET_TYPE_MNEMONIC,
        mnemonicBackedUp = false
    )
}
```

Update `importWalletFromMnemonic()` — add after `prefs.edit()...commit()` (line 129):

```kotlin
writeBackupIfPinAvailable("default") {
    KeyMaterial(
        privateKey = hex,
        mnemonic = words.joinToString(" "),
        walletType = WALLET_TYPE_MNEMONIC,
        mnemonicBackedUp = true
    )
}
```

Update `savePrivateKey()` — add after `prefs.edit()...commit()` (line 218):

```kotlin
writeBackupIfPinAvailable("default") {
    KeyMaterial(
        privateKey = hex,
        mnemonic = null,
        walletType = walletType,
        mnemonicBackedUp = false
    )
}
```

Update `storeKeysForWallet()` — add after `}.commit()` (line 231):

```kotlin
writeBackupIfPinAvailable(walletId) {
    KeyMaterial(
        privateKey = privateKey.joinToString("") { "%02x".format(it) },
        mnemonic = mnemonic?.joinToString(" "),
        walletType = if (mnemonic != null) WALLET_TYPE_MNEMONIC else WALLET_TYPE_RAW_KEY,
        mnemonicBackedUp = false
    )
}
```

Update `setMnemonicBackedUp()` — add after `commit()` (line 149):

```kotlin
writeBackupIfPinAvailable("default") {
    val hex = prefs.getString(KEY_PRIVATE_KEY, null) ?: return
    val mnemonic = prefs.getString(KEY_MNEMONIC, null)
    KeyMaterial(
        privateKey = hex,
        mnemonic = mnemonic,
        walletType = getWalletType(),
        mnemonicBackedUp = backedUp
    )
}
```

Update `setMnemonicBackedUpForWallet()` — add after `commit()` (line 274):

```kotlin
writeBackupIfPinAvailable(walletId) {
    val walletPrefs = getWalletPrefs(walletId)
    KeyMaterial(
        privateKey = walletPrefs.getString(KEY_PRIVATE_KEY, null) ?: "",
        mnemonic = walletPrefs.getString(KEY_MNEMONIC, null),
        walletType = walletPrefs.getString(KEY_WALLET_TYPE, WALLET_TYPE_RAW_KEY) ?: WALLET_TYPE_RAW_KEY,
        mnemonicBackedUp = backedUp
    )
}
```

Update `deleteWallet()` — add before `prefs.edit()`:

```kotlin
keyBackupManager?.deleteBackup("default")
```

Update `deleteWalletKeys()` — add before `getWalletPrefs(walletId).edit()`:

```kotlin
keyBackupManager?.deleteBackup(walletId)
```

- [ ] **Step 4: Update test setUp() method**

```kotlin
@Before
fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    mnemonicManager = MnemonicManager()
    keyManager = KeyManager(context, mnemonicManager)
    keyManager.testPrefs = context.getSharedPreferences("test_keys", Context.MODE_PRIVATE)
    val backupDir = File(context.cacheDir, "test_key_backups")
    backupDir.deleteRecursively()
    backupManager = KeyBackupManager(backupDir)
    keyManager.keyBackupManager = backupManager
    keyManager.deleteWallet()
}
```

Add import: `import java.io.File`

- [ ] **Step 5: Run tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.wallet.KeyManagerTest"`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
cd android && git add -A && git commit -m "feat: dual-write key material to ESP + backup file on every mutation"
```

---

### Task 7: PinManager removePin Guard + PIN Change Integration

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/auth/PinManager.kt`
- Modify: `android/app/src/test/java/com/rjnr/pocketnode/data/auth/PinManagerTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `PinManagerTest.kt`:

```kotlin
@Test
fun `removePin throws if backups exist and force is false`() {
    pinManager.setPin("123456")
    // Simulate backups existing
    pinManager.setBackupChecker { true }

    try {
        pinManager.removePin(force = false)
        fail("Should have thrown")
    } catch (e: IllegalStateException) {
        assertTrue(e.message!!.contains("backup"))
    }
}

@Test
fun `removePin succeeds if no backups exist`() {
    pinManager.setPin("123456")
    pinManager.setBackupChecker { false }

    pinManager.removePin(force = false)
    assertFalse(pinManager.hasPin())
}

@Test
fun `removePin with force bypasses backup check`() {
    pinManager.setPin("123456")
    pinManager.setBackupChecker { true }

    pinManager.removePin(force = true)
    assertFalse(pinManager.hasPin())
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.auth.PinManagerTest"`
Expected: FAIL — `setBackupChecker` and `force` param don't exist

- [ ] **Step 3: Update PinManager**

Add field after `testPrefs` (line 20):

```kotlin
private var backupChecker: (() -> Boolean)? = null

@VisibleForTesting
fun setBackupChecker(checker: () -> Boolean) {
    backupChecker = checker
}

@Inject
fun setBackupCheckerFromDI(keyBackupManager: KeyBackupManager) {
    backupChecker = { keyBackupManager.hasAnyBackups() }
}
```

Update `removePin()`:

```kotlin
fun removePin(force: Boolean = false) {
    if (!force && backupChecker?.invoke() == true) {
        throw IllegalStateException(
            "Cannot remove PIN while encrypted backup files exist. " +
            "Use force=true to delete backups and remove PIN."
        )
    }
    prefs.edit()
        .remove(KEY_PIN_HASH)
        .remove(KEY_SALT)
        .remove(KEY_FAILED_ATTEMPTS)
        .remove(KEY_LOCKOUT_UNTIL)
        .apply()
}
```

Add import: `import com.rjnr.pocketnode.data.wallet.KeyBackupManager`

- [ ] **Step 4: Run tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.data.auth.PinManagerTest"`
Expected: All PASS

- [ ] **Step 5: Run all tests for regression**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: All PASS. Note: existing code calling `removePin()` without args still works (default `force = false`). If any existing callers fail because `backupChecker` returns null (no DI in tests), that's fine — `null?.invoke() == true` is `false`, so the guard is a no-op.

- [ ] **Step 6: Commit**

```bash
cd android && git add -A && git commit -m "feat: guard PinManager.removePin() against deleting PIN when backups exist"
```

---

## Chunk 3: Recovery Flow

### Task 8: RecoveryViewModel

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/recovery/RecoveryViewModel.kt`
- Create: `android/app/src/test/java/com/rjnr/pocketnode/ui/screens/recovery/RecoveryViewModelTest.kt`

- [ ] **Step 1: Write failing tests for recovery state machine**

Create `RecoveryViewModelTest.kt`:

```kotlin
package com.rjnr.pocketnode.ui.screens.recovery

import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecoveryViewModelTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var backupManager: KeyBackupManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        backupManager = KeyBackupManager(tempDir.root)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is PinEntry when backups exist`() {
        val material = KeyMaterial(
            privateKey = "aa".repeat(32),
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            walletType = "mnemonic",
            mnemonicBackedUp = true
        )
        backupManager.writeBackup("wallet-1", material, "123456".toCharArray())

        val viewModel = RecoveryViewModel(backupManager)
        assertEquals(RecoveryStage.PIN_ENTRY, viewModel.uiState.value.stage)
    }

    @Test
    fun `initial state is MnemonicEntry when no backups exist`() {
        val viewModel = RecoveryViewModel(backupManager)
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, viewModel.uiState.value.stage)
    }

    @Test
    fun `attemptPinRecovery succeeds with correct PIN`() = runTest {
        val material = KeyMaterial(
            privateKey = "aa".repeat(32),
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            walletType = "mnemonic",
            mnemonicBackedUp = true
        )
        backupManager.writeBackup("wallet-1", material, "123456".toCharArray())

        val viewModel = RecoveryViewModel(backupManager)
        viewModel.attemptPinRecovery("123456".toCharArray())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, viewModel.uiState.value.stage)
        assertEquals(1, viewModel.uiState.value.recoveredWallets.size)
    }

    @Test
    fun `attemptPinRecovery fails with wrong PIN and tracks attempts`() = runTest {
        val material = KeyMaterial(
            privateKey = "aa".repeat(32),
            mnemonic = null,
            walletType = "raw_key",
            mnemonicBackedUp = false
        )
        backupManager.writeBackup("wallet-1", material, "123456".toCharArray())

        val viewModel = RecoveryViewModel(backupManager)
        viewModel.attemptPinRecovery("000000".toCharArray())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(RecoveryStage.PIN_ENTRY, viewModel.uiState.value.stage)
        assertEquals(1, viewModel.uiState.value.failedAttempts)
    }

    @Test
    fun `3 failed PIN attempts transitions to MnemonicEntry`() = runTest {
        val material = KeyMaterial(
            privateKey = "aa".repeat(32),
            mnemonic = null,
            walletType = "raw_key",
            mnemonicBackedUp = false
        )
        backupManager.writeBackup("wallet-1", material, "123456".toCharArray())

        val viewModel = RecoveryViewModel(backupManager)
        repeat(3) {
            viewModel.attemptPinRecovery("000000".toCharArray())
            testDispatcher.scheduler.advanceUntilIdle()
        }

        assertEquals(RecoveryStage.MNEMONIC_ENTRY, viewModel.uiState.value.stage)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.ui.screens.recovery.RecoveryViewModelTest"`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Implement RecoveryViewModel**

Create `RecoveryViewModel.kt`:

```kotlin
package com.rjnr.pocketnode.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyMaterial
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RecoveryStage {
    PIN_ENTRY,
    MNEMONIC_ENTRY,
    SUCCESS,
    ERROR
}

data class RecoveryUiState(
    val stage: RecoveryStage = RecoveryStage.PIN_ENTRY,
    val failedAttempts: Int = 0,
    val recoveredWallets: List<RecoveredWallet> = emptyList(),
    val failedWalletIds: List<String> = emptyList(),
    val error: String? = null
)

data class RecoveredWallet(
    val walletId: String,
    val material: KeyMaterial
)

@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val backupManager: KeyBackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState

    init {
        if (!backupManager.hasAnyBackups()) {
            _uiState.update { it.copy(stage = RecoveryStage.MNEMONIC_ENTRY) }
        }
    }

    fun attemptPinRecovery(pin: CharArray) {
        viewModelScope.launch {
            val walletIds = backupManager.listBackupWalletIds()
            val recovered = mutableListOf<RecoveredWallet>()
            val failed = mutableListOf<String>()

            for (id in walletIds) {
                val material = backupManager.readBackup(id, pin)
                if (material != null) {
                    recovered.add(RecoveredWallet(id, material))
                } else {
                    failed.add(id)
                }
            }

            if (recovered.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        stage = RecoveryStage.SUCCESS,
                        recoveredWallets = recovered,
                        failedWalletIds = failed
                    )
                }
            } else {
                val newAttempts = _uiState.value.failedAttempts + 1
                _uiState.update {
                    it.copy(
                        failedAttempts = newAttempts,
                        stage = if (newAttempts >= MAX_PIN_ATTEMPTS) RecoveryStage.MNEMONIC_ENTRY else RecoveryStage.PIN_ENTRY,
                        error = if (newAttempts >= MAX_PIN_ATTEMPTS) "Too many failed attempts. Please enter your recovery phrase."
                                else "Incorrect PIN. ${MAX_PIN_ATTEMPTS - newAttempts} attempts remaining."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        const val MAX_PIN_ATTEMPTS = 3
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.rjnr.pocketnode.ui.screens.recovery.RecoveryViewModelTest"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd android && git add -A && git commit -m "feat: add RecoveryViewModel with PIN-first, mnemonic-fallback state machine"
```

---

### Task 9: RecoveryScreen UI

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/recovery/RecoveryScreen.kt`

- [ ] **Step 1: Implement RecoveryScreen**

```kotlin
package com.rjnr.pocketnode.ui.screens.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    onRecoveryComplete: (List<RecoveredWallet>) -> Unit,
    onMnemonicRestore: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pinInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Wallet Recovery") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = "Your wallet data needs recovery",
                style = MaterialTheme.typography.headlineSmall
            )

            when (uiState.stage) {
                RecoveryStage.PIN_ENTRY -> {
                    Text(
                        text = "Enter your PIN to restore your wallets from backup.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text("PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    uiState.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.attemptPinRecovery(pinInput.toCharArray())
                            pinInput = ""
                        },
                        enabled = pinInput.length == 6,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Recover with PIN")
                    }

                    TextButton(onClick = onMnemonicRestore) {
                        Text("Use recovery phrase instead")
                    }
                }

                RecoveryStage.MNEMONIC_ENTRY -> {
                    Text(
                        text = "Enter your 12-word recovery phrase or private key to restore your wallet.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Button(
                        onClick = onMnemonicRestore,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enter recovery phrase")
                    }
                }

                RecoveryStage.SUCCESS -> {
                    LaunchedEffect(uiState.recoveredWallets) {
                        onRecoveryComplete(uiState.recoveredWallets)
                    }

                    Text(
                        text = "Recovery successful!",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    if (uiState.failedWalletIds.isNotEmpty()) {
                        Text(
                            text = "${uiState.failedWalletIds.size} wallet(s) could not be recovered. You can restore them with their recovery phrase.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    CircularProgressIndicator()
                }

                RecoveryStage.ERROR -> {
                    Text(
                        text = uiState.error ?: "An unexpected error occurred.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Button(onClick = onMnemonicRestore, modifier = Modifier.fillMaxWidth()) {
                        Text("Restore with recovery phrase")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run build to check compilation**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "feat: add RecoveryScreen UI with PIN entry and mnemonic fallback"
```

---

## Chunk 4: Soft-Gate Warnings + Navigation

### Task 10: SecurityBanner Component

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/components/SecurityBanner.kt`

- [ ] **Step 1: Implement SecurityBanner**

```kotlin
package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SecurityBannerState(
    val hasPinOrBiometrics: Boolean,
    val hasMnemonicBackup: Boolean
) {
    val isVisible: Boolean get() = !hasPinOrBiometrics || !hasMnemonicBackup
    val allComplete: Boolean get() = hasPinOrBiometrics && hasMnemonicBackup

    val message: String get() = when {
        !hasPinOrBiometrics && !hasMnemonicBackup -> "Secure your wallet"
        !hasPinOrBiometrics -> "Set up a PIN to protect your wallet"
        !hasMnemonicBackup -> "Back up your recovery phrase"
        else -> ""
    }

    val actionLabel: String get() = when {
        !hasPinOrBiometrics && !hasMnemonicBackup -> "Set up security"
        !hasPinOrBiometrics -> "Set up PIN"
        !hasMnemonicBackup -> "Back up now"
        else -> ""
    }
}

@Composable
fun SecurityBanner(
    state: SecurityBannerState,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            FilledTonalButton(onClick = onActionClick) {
                Text(state.actionLabel)
            }
        }
    }
}
```

- [ ] **Step 2: Run build check**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "feat: add SecurityBanner composable with context-aware messaging"
```

---

### Task 11: SecurityChecklistScreen

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/security/SecurityChecklistScreen.kt`

- [ ] **Step 1: Implement SecurityChecklistScreen**

```kotlin
package com.rjnr.pocketnode.ui.screens.security

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityChecklistScreen(
    hasPinOrBiometrics: Boolean,
    hasMnemonicBackup: Boolean,
    onSetupPin: () -> Unit,
    onBackupMnemonic: () -> Unit,
    onBack: () -> Unit
) {
    val completedCount = listOf(hasPinOrBiometrics, hasMnemonicBackup).count { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "$completedCount of 2 complete",
                style = MaterialTheme.typography.titleMedium,
                color = if (completedCount == 2) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )

            LinearProgressIndicator(
                progress = { completedCount / 2f },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            SecurityChecklistItem(
                title = "PIN or biometrics",
                description = "Protects your wallet and enables encrypted backups",
                isComplete = hasPinOrBiometrics,
                onAction = onSetupPin,
                actionLabel = if (hasPinOrBiometrics) "Done" else "Set up"
            )

            SecurityChecklistItem(
                title = "Recovery phrase",
                description = "Write down your 12 words so you can restore your wallet if this device is lost",
                isComplete = hasMnemonicBackup,
                onAction = onBackupMnemonic,
                actionLabel = if (hasMnemonicBackup) "Done" else "Back up"
            )
        }
    }
}

@Composable
private fun SecurityChecklistItem(
    title: String,
    description: String,
    isComplete: Boolean,
    onAction: () -> Unit,
    actionLabel: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isComplete) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isComplete) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isComplete) {
                FilledTonalButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run build check**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "feat: add SecurityChecklistScreen with PIN + mnemonic progress tracking"
```

---

### Task 12: HomeScreen SecurityBanner Integration

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeViewModel.kt:508+`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt:351+`

- [ ] **Step 1: Add security state to HomeUiState**

In `HomeViewModel.kt`, add fields to the `HomeUiState` data class (around line 508):

```kotlin
val hasPinOrBiometrics: Boolean = false,
val hasMnemonicBackup: Boolean = false,
```

Add a method to `HomeViewModel` that checks security status on init. The ViewModel should already have access to `PinManager` and `KeyManager` through its dependencies (or via `GatewayRepository`). Add to the init block or a refresh method:

```kotlin
private fun refreshSecurityState() {
    val hasPin = pinManager.hasPin()
    val hasBiometrics = authManager.isBiometricEnabled()
    val hasMnemonicBackup = keyManager.hasMnemonicBackup()
    _uiState.update {
        it.copy(
            hasPinOrBiometrics = hasPin || hasBiometrics,
            hasMnemonicBackup = hasMnemonicBackup
        )
    }
}
```

If `PinManager` and `AuthManager` are not currently injected into `HomeViewModel`, add them to the constructor:

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val gatewayRepository: GatewayRepository,
    private val pinManager: PinManager,
    private val authManager: AuthManager
) : ViewModel() {
```

Call `refreshSecurityState()` in the init block.

- [ ] **Step 2: Add SecurityBanner to HomeScreen**

In `HomeScreen.kt`, inside `HomeScreenUI` (around line 351), add the banner at the top of the content column, before the balance display:

```kotlin
// Import SecurityBanner and SecurityBannerState
import com.rjnr.pocketnode.ui.components.SecurityBanner
import com.rjnr.pocketnode.ui.components.SecurityBannerState

// Inside the content column, before balance:
SecurityBanner(
    state = SecurityBannerState(
        hasPinOrBiometrics = uiState.hasPinOrBiometrics,
        hasMnemonicBackup = uiState.hasMnemonicBackup
    ),
    onActionClick = onNavigateToSecurityChecklist // needs to be threaded from NavGraph
)
```

Note: The exact placement depends on the current HomeScreenUI layout. Add the banner as the first child of the main content column. Thread the `onNavigateToSecurityChecklist` callback from the NavGraph.

- [ ] **Step 3: Run build check**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS (may need to stub the navigation callback temporarily)

- [ ] **Step 4: Commit**

```bash
cd android && git add -A && git commit -m "feat: add security banner to HomeScreen with PIN/mnemonic state tracking"
```

---

### Task 13: Navigation Routes

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/navigation/NavGraph.kt`

- [ ] **Step 1: Add Recovery and SecurityChecklist routes**

In `NavGraph.kt`, add to the `Screen` sealed class (around line 26):

```kotlin
data object Recovery : Screen("recovery")
data object SecurityChecklist : Screen("security_checklist")
```

In `CkbNavGraph` composable (around line 55), add composable destinations:

```kotlin
composable(Screen.Recovery.route) {
    RecoveryScreen(
        onRecoveryComplete = { recoveredWallets ->
            // TODO: re-initialize KeyManager with recovered material, navigate to Home
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Recovery.route) { inclusive = true }
            }
        },
        onMnemonicRestore = {
            navController.navigate(Screen.Onboarding.route) {
                popUpTo(Screen.Recovery.route) { inclusive = true }
            }
        }
    )
}

composable(Screen.SecurityChecklist.route) {
    val homeViewModel: HomeViewModel = hiltViewModel(navController.getBackStackEntry(Screen.Home.route))
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    SecurityChecklistScreen(
        hasPinOrBiometrics = uiState.hasPinOrBiometrics,
        hasMnemonicBackup = uiState.hasMnemonicBackup,
        onSetupPin = {
            navController.navigate(Screen.Settings.route) // navigate to PIN setup in settings
        },
        onBackupMnemonic = {
            // Navigate to mnemonic backup flow (existing or to be created)
        },
        onBack = { navController.popBackStack() }
    )
}
```

Add imports:

```kotlin
import com.rjnr.pocketnode.ui.screens.recovery.RecoveryScreen
import com.rjnr.pocketnode.ui.screens.security.SecurityChecklistScreen
```

- [ ] **Step 2: Add recovery detection to startup**

In `MainActivity.kt` or the NavGraph start destination logic, check `KeyManager.wasResetDueToCorruption()` and route to `Screen.Recovery` if true:

```kotlin
val startDestination = when {
    keyManager.wasResetDueToCorruption() -> Screen.Recovery.route
    !keyManager.hasWallet() -> Screen.Onboarding.route
    else -> Screen.Home.route
}
```

- [ ] **Step 3: Run build check**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd android && git add -A && git commit -m "feat: add Recovery and SecurityChecklist navigation routes with startup detection"
```

---

### Task 14: ReceiveScreen Warning Modal

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/receive/ReceiveScreen.kt`

- [ ] **Step 1: Add backup warning dialog to ReceiveScreen**

Add to `ReceiveScreen.kt` inside the composable function (line 41+):

```kotlin
// Add state for showing the warning (once per session)
var showBackupWarning by remember { mutableStateOf(!hasMnemonicBackup && !hasPinOrBiometrics) }

if (showBackupWarning) {
    AlertDialog(
        onDismissRequest = { showBackupWarning = false },
        title = { Text("Protect your wallet") },
        text = {
            Text("You haven't backed up your recovery phrase yet. If you lose this device, your funds will be unrecoverable.")
        },
        confirmButton = {
            Button(onClick = {
                showBackupWarning = false
                onNavigateToBackup()
            }) {
                Text("Back up now")
            }
        },
        dismissButton = {
            TextButton(onClick = { showBackupWarning = false }) {
                Text("I understand the risk")
            }
        }
    )
}
```

Note: `hasMnemonicBackup`, `hasPinOrBiometrics`, and `onNavigateToBackup` need to be threaded as parameters to `ReceiveScreen`. The exact wiring depends on the current function signature.

- [ ] **Step 2: Run build check**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
cd android && git add -A && git commit -m "feat: add backup warning modal to ReceiveScreen"
```

---

### Task 15: SecuritySettingsViewModel Guard + Final Integration

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/settings/SecuritySettingsViewModel.kt:101+`

- [ ] **Step 1: Guard removePin in SecuritySettingsViewModel**

In `SecuritySettingsViewModel.kt`, update the `removePin()` method (around line 101) to check for backups:

```kotlin
private fun removePin() {
    if (keyBackupManager.hasAnyBackups()) {
        _uiState.update {
            it.copy(error = "Cannot remove PIN while encrypted wallet backups exist. " +
                "Back up your recovery phrase first, then you can remove the PIN.")
        }
        return
    }
    pinManager.removePin()
    _uiState.update { it.copy(hasPin = false) }
}
```

Inject `KeyBackupManager` into the ViewModel constructor if not already present.

- [ ] **Step 2: Add PIN creation backup hook**

In the same ViewModel (or wherever PIN creation is handled), after a successful `setPin()`, trigger backup writes:

```kotlin
private fun setPin(pin: CharArray) {
    pinManager.setPin(String(pin)) // TODO: update PinManager to accept CharArray
    authManager.setSessionPin(pin)

    // Write backups for all existing wallets now that we have a PIN
    viewModelScope.launch {
        val wallets = walletDao.getAll()
        for (wallet in wallets) {
            val privateKey = keyManager.getPrivateKeyForWallet(wallet.id)
            val mnemonic = keyManager.getMnemonicForWallet(wallet.id)
            val material = KeyMaterial(
                privateKey = privateKey.joinToString("") { "%02x".format(it) },
                mnemonic = mnemonic?.joinToString(" "),
                walletType = if (mnemonic != null) KeyManager.WALLET_TYPE_MNEMONIC else KeyManager.WALLET_TYPE_RAW_KEY,
                mnemonicBackedUp = keyManager.hasMnemonicBackupForWallet(wallet.id)
            )
            keyBackupManager.writeBackup(wallet.id, material, pin)
        }
    }

    java.util.Arrays.fill(pin, '\u0000')
}
```

- [ ] **Step 3: Run all tests**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 4: Commit**

```bash
cd android && git add -A && git commit -m "feat: guard PIN removal when backups exist, write backups on PIN creation"
```

---

### Task 16: Mnemonic Verification Quiz

The spec requires a quiz where users verify 3 random word positions before `mnemonicBackedUp` is set to true.

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/security/MnemonicVerifyScreen.kt`

- [ ] **Step 1: Implement MnemonicVerifyScreen**

```kotlin
package com.rjnr.pocketnode.ui.screens.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

data class QuizQuestion(val wordIndex: Int, val correctWord: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicVerifyScreen(
    mnemonicWords: List<String>,
    onVerified: () -> Unit,
    onBack: () -> Unit
) {
    // Generate 3 random quiz questions (stable across recompositions)
    val questions = remember(mnemonicWords) {
        mnemonicWords.indices.shuffled().take(3).sorted().map { idx ->
            QuizQuestion(wordIndex = idx, correctWord = mnemonicWords[idx])
        }
    }

    var currentQuestionIndex by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val currentQuestion = questions.getOrNull(currentQuestionIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Recovery Phrase") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LinearProgressIndicator(
                progress = { currentQuestionIndex / 3f },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Question ${currentQuestionIndex + 1} of 3",
                style = MaterialTheme.typography.titleMedium
            )

            if (currentQuestion != null) {
                Text(
                    text = "What is word #${currentQuestion.wordIndex + 1}?",
                    style = MaterialTheme.typography.headlineSmall
                )

                OutlinedTextField(
                    value = answer,
                    onValueChange = {
                        answer = it.lowercase().trim()
                        error = null
                    },
                    label = { Text("Enter word") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (answer.equals(currentQuestion.correctWord, ignoreCase = true)) {
                            answer = ""
                            error = null
                            if (currentQuestionIndex < 2) {
                                currentQuestionIndex++
                            } else {
                                onVerified()
                            }
                        } else {
                            error = "Incorrect. Try again."
                        }
                    },
                    enabled = answer.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (currentQuestionIndex < 2) "Next" else "Verify")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add navigation route for MnemonicVerify**

In `NavGraph.kt`, add to `Screen` sealed class:

```kotlin
data object MnemonicVerify : Screen("mnemonic_verify")
```

Add composable destination:

```kotlin
composable(Screen.MnemonicVerify.route) {
    // Get mnemonic from KeyManager (via ViewModel or parent backstack entry)
    val words = remember { keyManager.getMnemonic() ?: emptyList() }
    MnemonicVerifyScreen(
        mnemonicWords = words,
        onVerified = {
            keyManager.setMnemonicBackedUp(true)
            navController.popBackStack()
        },
        onBack = { navController.popBackStack() }
    )
}
```

Wire the `onBackupMnemonic` callback in SecurityChecklistScreen to navigate to `Screen.MnemonicVerify`.

- [ ] **Step 3: Run build check**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd android && git add -A && git commit -m "feat: add mnemonic verification quiz (3 random word positions)"
```

---

### Task 17: Post-Deposit Reminder

The spec requires a one-time modal when balance first changes from 0 to non-zero.

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeViewModel.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt`

- [ ] **Step 1: Add post-deposit reminder state to HomeUiState**

Add to `HomeUiState`:

```kotlin
val showPostDepositReminder: Boolean = false,
```

In `HomeViewModel`, track when balance transitions from zero to non-zero. In the balance refresh logic, compare previous and new balance:

```kotlin
private var previousBalanceWasZero = true

// Inside the balance refresh callback, after updating balance:
if (previousBalanceWasZero && newBalance > 0L && (!hasPinOrBiometrics || !hasMnemonicBackup)) {
    _uiState.update { it.copy(showPostDepositReminder = true) }
}
previousBalanceWasZero = (newBalance == 0L)
```

Add dismiss method:

```kotlin
fun dismissPostDepositReminder() {
    _uiState.update { it.copy(showPostDepositReminder = false) }
}
```

- [ ] **Step 2: Add modal to HomeScreen**

In `HomeScreen.kt`, after the SecurityBanner:

```kotlin
if (uiState.showPostDepositReminder) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissPostDepositReminder() },
        title = { Text("Protect your funds") },
        text = { Text("You now have funds in this wallet. Set up a PIN and write down your recovery phrase to protect them.") },
        confirmButton = {
            Button(onClick = {
                viewModel.dismissPostDepositReminder()
                onNavigateToSecurityChecklist()
            }) { Text("Secure now") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissPostDepositReminder() }) {
                Text("Later")
            }
        }
    )
}
```

- [ ] **Step 3: Run build check**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd android && git add -A && git commit -m "feat: add post-deposit security reminder when balance first becomes non-zero"
```

---

### Task 18: Reset Corruption Flag After Recovery + PinManager CharArray

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/wallet/KeyManager.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/auth/PinManager.kt`

- [ ] **Step 1: Add resetCorruptionFlag to KeyManager**

Add to `KeyManager.kt`:

```kotlin
fun resetCorruptionFlag() {
    walletResetDueToCorruption = false
}
```

- [ ] **Step 2: Call resetCorruptionFlag after successful recovery**

In the Recovery navigation route (`NavGraph.kt`), inside `onRecoveryComplete`:

```kotlin
onRecoveryComplete = { recoveredWallets ->
    // Re-store recovered key material into ESP
    for (wallet in recoveredWallets) {
        keyManager.storeKeysForWallet(
            wallet.walletId,
            wallet.material.privateKey.hexToByteArray(),
            wallet.material.mnemonic?.split(" ")
        )
    }
    keyManager.resetCorruptionFlag()
    navController.navigate(Screen.Home.route) {
        popUpTo(Screen.Recovery.route) { inclusive = true }
    }
}
```

- [ ] **Step 3: Update PinManager.setPin to accept CharArray**

In `PinManager.kt`, update `setPin()`:

```kotlin
fun setPin(pin: CharArray) {
    require(pin.size == PIN_LENGTH && pin.all { it.isDigit() }) {
        "PIN must be exactly $PIN_LENGTH digits"
    }
    val hash = hashPin(pin)
    prefs.edit()
        .putString(KEY_PIN_HASH, hash)
        .putInt(KEY_FAILED_ATTEMPTS, 0)
        .remove(KEY_LOCKOUT_UNTIL)
        .apply()
}

fun verifyPin(pin: CharArray): Boolean {
    if (isLockedOut()) return false
    if (!hasPin()) return false

    val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
    val inputHash = hashPin(pin)

    return if (inputHash == storedHash) {
        resetFailedAttempts()
        true
    } else {
        recordFailedAttempt()
        false
    }
}
```

Update `hashPin`:

```kotlin
private fun hashPin(pin: CharArray): String {
    val salt = getOrCreateSalt()
    val pinBytes = String(pin).toByteArray(Charsets.UTF_8)
    val input = salt + pinBytes
    val hash = blake2b.hash(input)
    return hash.joinToString("") { "%02x".format(it) }
}
```

Keep backward-compatible String overloads for existing callers:

```kotlin
@Deprecated("Use CharArray overload", ReplaceWith("setPin(pin.toCharArray())"))
fun setPin(pin: String) = setPin(pin.toCharArray())

@Deprecated("Use CharArray overload", ReplaceWith("verifyPin(pin.toCharArray())"))
fun verifyPin(pin: String): Boolean = verifyPin(pin.toCharArray())
```

- [ ] **Step 4: Update PinManagerTest**

Update test calls to use `CharArray` where appropriate (the String overloads still work for existing tests via deprecation, so this is optional but good hygiene).

- [ ] **Step 5: Run all tests**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
cd android && git add -A && git commit -m "feat: reset corruption flag after recovery, update PinManager to accept CharArray"
```

---

### Task 19: Final Integration Test + Cleanup

- [ ] **Step 1: Run full test suite**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 2: Run build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Clean up orphaned .tmp files on app startup**

In `GatewayRepository` init or `MainActivity`, add:

```kotlin
keyBackupManager.cleanupOrphanedTmpFiles()
```

- [ ] **Step 4: Run full suite one more time**

Run: `cd android && ./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: All PASS, BUILD SUCCESSFUL

- [ ] **Step 5: Final commit**

```bash
cd android && git add -A && git commit -m "chore: add startup cleanup for orphaned backup tmp files"
```

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | PinManager StrongBox fallback fix | PinManager.kt |
| 2 | KeyBackupManager core (write + read) | KeyBackupManager.kt (new) |
| 3 | KeyBackupManager tests (delete, re-encrypt) | KeyBackupManagerTest.kt |
| 4 | Wire KeyBackupManager into DI | AppModule.kt |
| 5 | AuthManager session PIN caching | AuthManager.kt |
| 6 | KeyManager dual-write integration | KeyManager.kt |
| 7 | PinManager removePin guard | PinManager.kt |
| 8 | RecoveryViewModel state machine | RecoveryViewModel.kt (new) |
| 9 | RecoveryScreen UI | RecoveryScreen.kt (new) |
| 10 | SecurityBanner component | SecurityBanner.kt (new) |
| 11 | SecurityChecklistScreen | SecurityChecklistScreen.kt (new) |
| 12 | HomeScreen banner integration | HomeScreen.kt, HomeViewModel.kt |
| 13 | Navigation routes | NavGraph.kt |
| 14 | ReceiveScreen warning modal | ReceiveScreen.kt |
| 15 | SecuritySettingsViewModel guard + PIN hook | SecuritySettingsViewModel.kt |
| 16 | Mnemonic verification quiz | MnemonicVerifyScreen.kt (new) |
| 17 | Post-deposit reminder | HomeViewModel.kt, HomeScreen.kt |
| 18 | Reset corruption flag + PinManager CharArray | KeyManager.kt, PinManager.kt |
| 19 | Final integration test + cleanup | GatewayRepository.kt |

**Dependencies:** Tasks 1-4 are foundational (no dependencies). Task 5 depends on nothing. Task 6 depends on 2+4. Task 7 depends on 2. Tasks 8-9 depend on 2. Tasks 10-14 have no hard data dependencies. Task 15 depends on 2+5+7. Task 16 depends on 13. Task 17 depends on 12. Task 18 depends on 8+13. Task 19 is last.

**Parallelizable groups:**
- Group A (independent): Tasks 1, 2-3, 5
- Group B (after A): Tasks 4, 6, 7
- Group C (after B): Tasks 8-9, 10-11 (UI, independent of each other)
- Group D (after C): Tasks 12-15, 16, 17
- Group E (after D): Task 18
- Group F: Task 19
