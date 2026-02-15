package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
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

    private val prefs: SharedPreferences
        get() = testPrefs ?: encryptedPrefs

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true)
            .build()

        EncryptedSharedPreferences.create(
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
        val privateKey = BigInteger(1, privateKeyBytes)

        savePrivateKey(privateKey, WALLET_TYPE_MNEMONIC)
        storeMnemonic(words)
        return Pair(getWalletInfo(), words)
    }

    fun importWalletFromMnemonic(
        words: List<String>,
        passphrase: String = ""
    ): WalletInfo {
        require(mnemonicManager.validateMnemonic(words)) { "Invalid mnemonic" }

        val privateKeyBytes = mnemonicManager.mnemonicToPrivateKey(words, passphrase)
        val privateKey = BigInteger(1, privateKeyBytes)

        savePrivateKey(privateKey, WALLET_TYPE_MNEMONIC)
        storeMnemonic(words)
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
        prefs.edit().putBoolean(KEY_MNEMONIC_BACKED_UP, backedUp).apply()
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
            .apply()
    }

    private fun storeMnemonic(words: List<String>) {
        prefs.edit()
            .putString(KEY_MNEMONIC, words.joinToString(" "))
            .putBoolean(KEY_MNEMONIC_BACKED_UP, false)
            .apply()
    }

    companion object {
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
