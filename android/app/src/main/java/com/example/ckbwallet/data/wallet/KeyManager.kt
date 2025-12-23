package com.example.ckbwallet.data.wallet

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.ckbwallet.data.gateway.models.NetworkType
import com.example.ckbwallet.data.gateway.models.Script
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
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
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

    fun generateWallet(): WalletInfo {
        val privateKeyBytes = ByteArray(32)
        SecureRandom().nextBytes(privateKeyBytes)
        val privateKey = BigInteger(1, privateKeyBytes)

        savePrivateKey(privateKey)
        return getWalletInfo()
    }

    fun importWallet(privateKeyHex: String): WalletInfo {
        val privateKeyBytes = Numeric.hexStringToByteArray(privateKeyHex)
        require(privateKeyBytes.size == 32) { "Private key must be 32 bytes" }
        val privateKey = BigInteger(1, privateKeyBytes)

        savePrivateKey(privateKey)
        return getWalletInfo()
    }

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

    /**
     * Get the ECKeyPair for signing operations.
     */
    fun getKeyPair(): ECKeyPair {
        val hex = prefs.getString(KEY_PRIVATE_KEY, null)
            ?: throw IllegalStateException("No wallet found")
        val privateKey = BigInteger(hex, 16)
        return ECKeyPair.create(privateKey)
    }

    /**
     * Derive public key from private key using secp256k1.
     * Returns compressed public key (33 bytes).
     */
    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val keyPair = ECKeyPair.create(BigInteger(1, privateKey))
        return keyPair.getEncodedPublicKey(true)
    }

    /**
     * Derive lock script from public key using official CKB SDK Blake2b.
     * This creates a secp256k1_blake160 lock script.
     */
    fun deriveLockScript(publicKey: ByteArray): Script {
        // Use official CKB SDK Blake2b to hash public key
        val pubKeyHash = Blake2b.digest(publicKey)
        // Take first 20 bytes (blake160)
        val args = pubKeyHash.copyOfRange(0, 20)

        return Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = Numeric.toHexString(args)
        )
    }

    /**
     * Sign a message using secp256k1 with recoverable signature.
     * Returns 65-byte signature (64 bytes compact + 1 byte recovery ID).
     */
    fun sign(message: ByteArray): ByteArray {
        val keyPair = getKeyPair()
        val signatureData = Sign.signMessage(message, keyPair)

        // Get 65-byte signature (r[32] + s[32] + v[1])
        return signatureData.signature
    }

    fun deleteWallet() {
        prefs.edit().remove(KEY_PRIVATE_KEY).apply()
    }

    private fun savePrivateKey(privateKey: BigInteger) {
        // Store as hex without leading zeros stripped
        val hex = Numeric.toHexStringNoPrefixZeroPadded(privateKey, 64)
        prefs.edit().putString(KEY_PRIVATE_KEY, hex).apply()
    }

    companion object {
        private const val KEY_PRIVATE_KEY = "private_key"
    }
}

data class WalletInfo(
    val publicKey: String,
    val script: Script,
    val testnetAddress: String,
    val mainnetAddress: String
)
