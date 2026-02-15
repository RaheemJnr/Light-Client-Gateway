package com.rjnr.pocketnode.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rjnr.pocketnode.data.crypto.Blake2b
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blake2b: Blake2b
) {
    @VisibleForTesting
    internal var testPrefs: SharedPreferences? = null

    private val prefs: SharedPreferences
        get() = testPrefs ?: encryptedPrefs

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @VisibleForTesting
    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    fun setPin(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must be exactly $PIN_LENGTH digits"
        }
        val hash = hashPin(pin)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
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

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun removePin() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_SALT)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    fun getRemainingAttempts(): Int {
        val failed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        return (MAX_ATTEMPTS - failed).coerceAtLeast(0)
    }

    fun isLockedOut(): Boolean {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil == 0L) return false

        return if (timeProvider() < lockoutUntil) {
            true
        } else {
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .remove(KEY_LOCKOUT_UNTIL)
                .apply()
            false
        }
    }

    fun getLockoutRemainingMs(): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil == 0L) return 0L
        return (lockoutUntil - timeProvider()).coerceAtLeast(0L)
    }

    private fun recordFailedAttempt() {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
        if (attempts >= MAX_ATTEMPTS) {
            editor.putLong(KEY_LOCKOUT_UNTIL, timeProvider() + LOCKOUT_DURATION_MS)
        }
        editor.apply()
    }

    private fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    private fun hashPin(pin: String): String {
        val salt = getOrCreateSalt()
        val input = salt + pin.toByteArray(Charsets.UTF_8)
        val hash = blake2b.hash(input)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getOrCreateSalt(): ByteArray {
        val existingHex = prefs.getString(KEY_SALT, null)
        if (existingHex != null) {
            return hexToBytes(existingHex)
        }
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)
        val hex = salt.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SALT, hex).apply()
        return salt
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        internal const val PREFS_NAME = "ckb_pin_prefs"
        internal const val PIN_LENGTH = 6
        internal const val MAX_ATTEMPTS = 5
        internal const val LOCKOUT_DURATION_MS = 30_000L
        internal const val SALT_SIZE = 32

        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    }
}
