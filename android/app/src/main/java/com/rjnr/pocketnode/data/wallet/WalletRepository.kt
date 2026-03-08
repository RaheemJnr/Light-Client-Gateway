package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletRepository @Inject constructor(
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences,
    private val mnemonicManager: MnemonicManager
) {
    fun getActiveWallet(): Flow<WalletEntity?> = walletDao.getActiveWallet()

    fun getAllWallets(): Flow<List<WalletEntity>> = walletDao.getAllWallets()

    fun getSubAccounts(parentWalletId: String): Flow<List<WalletEntity>> =
        walletDao.getSubAccounts(parentWalletId)

    suspend fun createWallet(
        name: String,
        mnemonic: List<String>? = null,
        wordCount: MnemonicManager.WordCount = MnemonicManager.WordCount.TWELVE
    ): WalletEntity {
        val words = mnemonic ?: mnemonicManager.generateMnemonic(wordCount)
        require(mnemonicManager.validateMnemonic(words)) { "Invalid mnemonic" }

        val walletId = UUID.randomUUID().toString()
        val seed = mnemonicManager.mnemonicToSeed(words)
        val privateKey = mnemonicManager.derivePrivateKey(seed, accountIndex = 0)
        val publicKey = keyManager.derivePublicKey(privateKey)
        val lockScript = keyManager.deriveLockScript(publicKey)
        val mainnetAddress = AddressUtils.encode(lockScript, NetworkType.MAINNET)
        val testnetAddress = AddressUtils.encode(lockScript, NetworkType.TESTNET)

        keyManager.storeKeysForWallet(walletId, privateKey, words)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_MNEMONIC,
            derivationPath = "m/44'/309'/0'/0/0",
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = mainnetAddress,
            testnetAddress = testnetAddress,
            isActive = true,
            createdAt = System.currentTimeMillis()
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)

        return entity
    }

    suspend fun importWallet(name: String, mnemonic: List<String>): WalletEntity {
        require(mnemonicManager.validateMnemonic(mnemonic)) { "Invalid mnemonic" }
        return createWallet(name, mnemonic)
    }

    suspend fun importRawKey(name: String, privateKeyHex: String): WalletEntity {
        val privateKey = privateKeyHex.removePrefix("0x").chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        require(privateKey.size == 32) { "Private key must be 32 bytes" }

        val walletId = UUID.randomUUID().toString()
        val publicKey = keyManager.derivePublicKey(privateKey)
        val lockScript = keyManager.deriveLockScript(publicKey)
        val mainnetAddress = AddressUtils.encode(lockScript, NetworkType.MAINNET)
        val testnetAddress = AddressUtils.encode(lockScript, NetworkType.TESTNET)

        keyManager.storeKeysForWallet(walletId, privateKey, null)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_RAW_KEY,
            derivationPath = null,
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = mainnetAddress,
            testnetAddress = testnetAddress,
            isActive = true,
            createdAt = System.currentTimeMillis()
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)

        return entity
    }

    suspend fun createSubAccount(parentWalletId: String, name: String): WalletEntity {
        val parent = walletDao.getById(parentWalletId)
            ?: throw IllegalArgumentException("Parent wallet not found")
        require(parent.type == KeyManager.WALLET_TYPE_MNEMONIC) {
            "Sub-accounts require a mnemonic wallet"
        }

        val parentMnemonic = keyManager.getMnemonicForWallet(parentWalletId)
            ?: throw IllegalStateException("Parent mnemonic not found")

        val nextIndex = walletDao.count() // simple incrementing index
        val seed = mnemonicManager.mnemonicToSeed(parentMnemonic)
        val privateKey = mnemonicManager.derivePrivateKey(seed, accountIndex = nextIndex)
        val publicKey = keyManager.derivePublicKey(privateKey)
        val lockScript = keyManager.deriveLockScript(publicKey)
        val mainnetAddress = AddressUtils.encode(lockScript, NetworkType.MAINNET)
        val testnetAddress = AddressUtils.encode(lockScript, NetworkType.TESTNET)

        val walletId = UUID.randomUUID().toString()
        keyManager.storeKeysForWallet(walletId, privateKey, null) // sub-accounts don't store mnemonic

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_MNEMONIC,
            derivationPath = "m/44'/309'/$nextIndex'/0/0",
            parentWalletId = parentWalletId,
            accountIndex = nextIndex,
            mainnetAddress = mainnetAddress,
            testnetAddress = testnetAddress,
            isActive = true,
            createdAt = System.currentTimeMillis()
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)

        return entity
    }

    suspend fun switchActiveWallet(walletId: String) {
        val wallet = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        walletDao.deactivateAll()
        walletDao.activate(walletId)
        walletPreferences.setActiveWalletId(walletId)
    }

    suspend fun renameWallet(walletId: String, newName: String) {
        walletDao.rename(walletId, newName)
    }

    suspend fun deleteWallet(walletId: String) {
        val wallet = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        require(!wallet.isActive) { "Cannot delete the active wallet" }

        keyManager.deleteKeysForWallet(walletId)
        walletDao.delete(walletId)
    }

    suspend fun count(): Int = walletDao.count()
}
