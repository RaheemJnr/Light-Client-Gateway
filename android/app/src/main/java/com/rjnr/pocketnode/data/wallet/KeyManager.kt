package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import org.nervos.ckb.crypto.Blake2b
import org.nervos.ckb.crypto.secp256k1.ECKeyPair
import org.nervos.ckb.crypto.secp256k1.Sign
import org.nervos.ckb.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mnemonicManager: MnemonicManager
) {
    @VisibleForTesting
    internal var testPrefs: SharedPreferences? = null

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

    @Inject
    fun setBackupManager(backupManager: KeyBackupManager) {
        this.keyBackupManager = backupManager
    }

    private var walletResetDueToCorruption = false

    fun wasResetDueToCorruption(): Boolean = walletResetDueToCorruption

    fun resetCorruptionFlag() {
        walletResetDueToCorruption = false
    }

    private val prefs: SharedPreferences
        get() = testPrefs ?: encryptedPrefs

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs(useStrongBox = true)
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox-backed prefs failed, trying without StrongBox", e)
            // NEVER delete the prefs file — it may contain the user's only copy of
            // their private key and mnemonic. Try without StrongBox instead.
            try {
                createEncryptedPrefs(useStrongBox = false)
            } catch (e2: Exception) {
                // Both attempts failed — the keystore or prefs file is genuinely corrupted.
                // Surface the error so the user sees a warning. Do NOT delete the file.
                Log.e(TAG, "EncryptedSharedPreferences completely unreadable", e2)
                walletResetDueToCorruption = true
                // Last resort: try StrongBox one more time (sometimes transient)
                createEncryptedPrefs(useStrongBox = true)
            }
        }
    }

    private fun createEncryptedPrefs(useStrongBox: Boolean = true): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(useStrongBox)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "ckb_wallet_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasWallet(): Boolean = prefs.contains(KEY_PRIVATE_KEY)

    // -- Existing methods (raw key) --

    fun generateWallet(): WalletInfo {
        val privateKeyBytes = ByteArray(32)
        SecureRandom().nextBytes(privateKeyBytes)
        val privateKey = BigInteger(1, privateKeyBytes)

        savePrivateKey(privateKey, WALLET_TYPE_RAW_KEY)
        return getWalletInfo()
    }

    fun importWallet(privateKeyHex: String): WalletInfo {
        val privateKeyBytes = Numeric.hexStringToByteArray(privateKeyHex)
        require(privateKeyBytes.size == 32) { "Private key must be 32 bytes" }
        val privateKey = BigInteger(1, privateKeyBytes)

        savePrivateKey(privateKey, WALLET_TYPE_RAW_KEY)
        return getWalletInfo()
    }

    // -- New mnemonic methods --

    fun generateWalletWithMnemonic(
        wordCount: MnemonicManager.WordCount = MnemonicManager.WordCount.TWELVE
    ): Pair<WalletInfo, List<String>> {
        val words = mnemonicManager.generateMnemonic(wordCount)
        val privateKeyBytes = mnemonicManager.mnemonicToPrivateKey(words)
        val hex = Numeric.toHexStringNoPrefixZeroPadded(BigInteger(1, privateKeyBytes), 64)

        // Single atomic write — commit() is synchronous, all-or-nothing.
        // Prevents race where app is killed between key save and mnemonic save.
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, hex)
            .putString(KEY_WALLET_TYPE, WALLET_TYPE_MNEMONIC)
            .putString(KEY_MNEMONIC, words.joinToString(" "))
            .putBoolean(KEY_MNEMONIC_BACKED_UP, false)
            .commit()

        writeBackupIfPinAvailable("default") {
            KeyMaterial(
                privateKey = hex,
                mnemonic = words.joinToString(" "),
                walletType = WALLET_TYPE_MNEMONIC,
                mnemonicBackedUp = false
            )
        }

        return Pair(getWalletInfo(), words)
    }

    fun importWalletFromMnemonic(
        words: List<String>,
        passphrase: String = ""
    ): WalletInfo {
        require(mnemonicManager.validateMnemonic(words)) { "Invalid mnemonic" }

        val privateKeyBytes = mnemonicManager.mnemonicToPrivateKey(words, passphrase)
        val hex = Numeric.toHexStringNoPrefixZeroPadded(BigInteger(1, privateKeyBytes), 64)

        // Single atomic write — mark as backed up since user already knows their mnemonic
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, hex)
            .putString(KEY_WALLET_TYPE, WALLET_TYPE_MNEMONIC)
            .putString(KEY_MNEMONIC, words.joinToString(" "))
            .putBoolean(KEY_MNEMONIC_BACKED_UP, true)
            .commit()

        writeBackupIfPinAvailable("default") {
            KeyMaterial(
                privateKey = hex,
                mnemonic = words.joinToString(" "),
                walletType = WALLET_TYPE_MNEMONIC,
                mnemonicBackedUp = true
            )
        }

        return getWalletInfo()
    }

    fun getMnemonic(): List<String>? {
        val joined = prefs.getString(KEY_MNEMONIC, null) ?: return null
        return joined.split(" ")
    }

    fun getWalletType(): String {
        return prefs.getString(KEY_WALLET_TYPE, WALLET_TYPE_RAW_KEY) ?: WALLET_TYPE_RAW_KEY
    }

    fun hasMnemonicBackup(): Boolean {
        return prefs.getBoolean(KEY_MNEMONIC_BACKED_UP, false)
    }

    fun setMnemonicBackedUp(backedUp: Boolean) {
        prefs.edit().putBoolean(KEY_MNEMONIC_BACKED_UP, backedUp).commit()

        val pk = prefs.getString(KEY_PRIVATE_KEY, null) ?: return
        writeBackupIfPinAvailable("default") {
            KeyMaterial(
                privateKey = pk,
                mnemonic = prefs.getString(KEY_MNEMONIC, null),
                walletType = getWalletType(),
                mnemonicBackedUp = backedUp
            )
        }
    }

    // -- Shared methods --

    fun getWalletInfo(): WalletInfo {
        val keyPair = getKeyPair()
        val publicKey = keyPair.getEncodedPublicKey(true) // compressed
        val script = deriveLockScript(publicKey)
        val testnetAddress = AddressUtils.encode(script, NetworkType.TESTNET)
        val mainnetAddress = AddressUtils.encode(script, NetworkType.MAINNET)

        return WalletInfo(
            publicKey = Numeric.toHexString(publicKey),
            script = script,
            testnetAddress = testnetAddress,
            mainnetAddress = mainnetAddress
        )
    }

    fun getPrivateKey(): ByteArray {
        val hex = prefs.getString(KEY_PRIVATE_KEY, null)
            ?: throw IllegalStateException("No wallet found")
        return Numeric.hexStringToByteArray(hex)
    }

    fun getKeyPair(): ECKeyPair {
        val hex = prefs.getString(KEY_PRIVATE_KEY, null)
            ?: throw IllegalStateException("No wallet found")
        val privateKey = BigInteger(hex, 16)
        return ECKeyPair.create(privateKey)
    }

    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val keyPair = ECKeyPair.create(BigInteger(1, privateKey))
        return keyPair.getEncodedPublicKey(true)
    }

    fun deriveLockScript(publicKey: ByteArray): Script {
        val pubKeyHash = Blake2b.digest(publicKey)
        val args = pubKeyHash.copyOfRange(0, 20)

        return Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = Numeric.toHexString(args)
        )
    }

    fun sign(message: ByteArray): ByteArray {
        val keyPair = getKeyPair()
        val signatureData = Sign.signMessage(message, keyPair)
        return signatureData.signature
    }

    fun deleteWallet() {
        keyBackupManager?.deleteBackup("default")
        prefs.edit()
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_MNEMONIC)
            .remove(KEY_MNEMONIC_BACKED_UP)
            .remove(KEY_WALLET_TYPE)
            .apply()
    }

    private fun savePrivateKey(privateKey: BigInteger, walletType: String) {
        val hex = Numeric.toHexStringNoPrefixZeroPadded(privateKey, 64)
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, hex)
            .putString(KEY_WALLET_TYPE, walletType)
            .commit()

        writeBackupIfPinAvailable("default") {
            KeyMaterial(
                privateKey = hex,
                mnemonic = null,
                walletType = walletType,
                mnemonicBackedUp = false
            )
        }
    }

    // -- Wallet-scoped key storage (multi-wallet support) --

    fun storeKeysForWallet(walletId: String, privateKey: ByteArray, mnemonic: List<String>?) {
        val walletPrefs = getWalletPrefs(walletId)
        walletPrefs.edit().apply {
            putString(KEY_PRIVATE_KEY, privateKey.joinToString("") { "%02x".format(it) })
            putString(KEY_WALLET_TYPE, if (mnemonic != null) WALLET_TYPE_MNEMONIC else WALLET_TYPE_RAW_KEY)
            if (mnemonic != null) {
                putString(KEY_MNEMONIC, mnemonic.joinToString(" "))
            }
        }.commit()

        writeBackupIfPinAvailable(walletId) {
            KeyMaterial(
                privateKey = privateKey.joinToString("") { "%02x".format(it) },
                mnemonic = mnemonic?.joinToString(" "),
                walletType = if (mnemonic != null) WALLET_TYPE_MNEMONIC else WALLET_TYPE_RAW_KEY,
                mnemonicBackedUp = false
            )
        }
    }

    private fun getWalletPrefs(walletId: String): SharedPreferences {
        val fileName = "ckb_wallet_keys_$walletId"
        return try {
            createEncryptedPrefsForWallet(fileName, useStrongBox = true)
        } catch (e: Exception) {
            Log.w(TAG, "Wallet prefs ($walletId) StrongBox failed, trying without", e)
            // NEVER delete — may contain user's only key material
            try {
                createEncryptedPrefsForWallet(fileName, useStrongBox = false)
            } catch (e2: Exception) {
                Log.e(TAG, "Wallet prefs ($walletId) completely unreadable", e2)
                createEncryptedPrefsForWallet(fileName, useStrongBox = true)
            }
        }
    }

    private fun createEncryptedPrefsForWallet(fileName: String, useStrongBox: Boolean): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .apply { if (useStrongBox) setRequestStrongBoxBacked(true) }
            .build()
        return EncryptedSharedPreferences.create(
            context, fileName, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getMnemonicForWallet(walletId: String): List<String>? {
        val joined = getWalletPrefs(walletId).getString(KEY_MNEMONIC, null) ?: return null
        return joined.split(" ")
    }

    fun getPrivateKeyForWallet(walletId: String): ByteArray {
        val hex = getWalletPrefs(walletId).getString(KEY_PRIVATE_KEY, null)
            ?: throw IllegalStateException("No wallet found for $walletId")
        return Numeric.hexStringToByteArray(hex)
    }

    fun setMnemonicBackedUpForWallet(walletId: String, backedUp: Boolean) {
        getWalletPrefs(walletId).edit().putBoolean(KEY_MNEMONIC_BACKED_UP, backedUp).commit()

        writeBackupIfPinAvailable(walletId) {
            val walletPrefs = getWalletPrefs(walletId)
            KeyMaterial(
                privateKey = walletPrefs.getString(KEY_PRIVATE_KEY, null) ?: "",
                mnemonic = walletPrefs.getString(KEY_MNEMONIC, null),
                walletType = walletPrefs.getString(KEY_WALLET_TYPE, WALLET_TYPE_RAW_KEY) ?: WALLET_TYPE_RAW_KEY,
                mnemonicBackedUp = backedUp
            )
        }
    }

    fun hasMnemonicBackupForWallet(walletId: String): Boolean {
        return getWalletPrefs(walletId).getBoolean(KEY_MNEMONIC_BACKED_UP, false)
    }

    fun deleteWalletKeys(walletId: String) {
        keyBackupManager?.deleteBackup(walletId)
        getWalletPrefs(walletId).edit()
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_MNEMONIC)
            .remove(KEY_MNEMONIC_BACKED_UP)
            .remove(KEY_WALLET_TYPE)
            .commit()
    }

    private fun writeBackupIfPinAvailable(walletId: String, buildMaterial: () -> KeyMaterial) {
        val pin = sessionPin ?: return
        val manager = keyBackupManager ?: return
        try {
            manager.writeBackup(walletId, buildMaterial(), pin)
        } catch (e: Exception) {
            Log.w(TAG, "Backup write failed for $walletId", e)
        }
    }

    companion object {
        private const val TAG = "KeyManager"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_MNEMONIC = "mnemonic_words"
        private const val KEY_MNEMONIC_BACKED_UP = "mnemonic_backed_up"
        private const val KEY_WALLET_TYPE = "wallet_type"
        const val WALLET_TYPE_MNEMONIC = "mnemonic"
        const val WALLET_TYPE_RAW_KEY = "raw_key"
    }
}

data class WalletInfo(
    val publicKey: String,
    val script: Script,
    val testnetAddress: String,
    val mainnetAddress: String
)
