package com.rjnr.pocketnode.data.wallet

import android.util.Log
import androidx.annotation.VisibleForTesting
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
    val mnemonic: String? = null,
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

    @VisibleForTesting
    internal var kdfIterations: Int = KDF_ITERATIONS

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
            out.write(ciphertext)
        }

        tmpFile.renameTo(file)
    }

    fun readBackup(walletId: String, pin: CharArray): KeyMaterial? {
        val file = backupFile(walletId)
        if (!file.exists()) return null

        return try {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_SIZE || !bytes.sliceArray(0 until 4).contentEquals(MAGIC)) {
                Log.w(TAG, "Backup file for $walletId has invalid magic header")
                return null
            }
            val salt = bytes.sliceArray(HEADER_SIZE until HEADER_SIZE + SALT_SIZE)
            val iv = bytes.sliceArray(HEADER_SIZE + SALT_SIZE until HEADER_SIZE + SALT_SIZE + IV_SIZE)
            val ciphertext = bytes.sliceArray(HEADER_SIZE + SALT_SIZE + IV_SIZE until bytes.size)

            val key = deriveKey(pin, salt)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)

            json.decodeFromString<KeyMaterial>(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
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
        val tmpFiles = mutableListOf<Pair<File, File>>()
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
        val spec = PBEKeySpec(pin, salt, kdfIterations, KEY_SIZE_BITS)
        val secret = factory.generateSecret(spec)
        val key = SecretKeySpec(secret.encoded, "AES")
        spec.clearPassword()
        return key
    }

    companion object {
        private const val TAG = "KeyBackupManager"
        val MAGIC = byteArrayOf('P'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_SIZE = 5
        const val SALT_SIZE = 16
        const val IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val KDF_ITERATIONS = 600_000
        const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
    }
}
