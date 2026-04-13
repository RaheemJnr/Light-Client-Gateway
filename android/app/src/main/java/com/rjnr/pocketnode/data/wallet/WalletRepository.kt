package com.rjnr.pocketnode.data.wallet

import android.util.Log
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WalletRepository"

@Singleton
class WalletRepository @Inject constructor(
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences,
    private val mnemonicManager: MnemonicManager,
    private val appDatabase: AppDatabase
) {
    val walletsFlow: Flow<List<WalletEntity>> = walletDao.getAllFlow()

    fun getActiveWallet(): Flow<WalletEntity?> = walletDao.getActiveWallet()

    fun getAllWallets(): Flow<List<WalletEntity>> = walletDao.getAllWallets()

    fun getSubAccounts(parentWalletId: String): Flow<List<WalletEntity>> =
        walletDao.getSubAccounts(parentWalletId)

    suspend fun getAll(): List<WalletEntity> = walletDao.getAll()

    suspend fun getActive(): WalletEntity? = walletDao.getActive()

    suspend fun getById(walletId: String): WalletEntity? = walletDao.getById(walletId)

    /**
     * Create a new mnemonic wallet. Stores keys in wallet-scoped encrypted prefs.
     */
    suspend fun createWallet(
        name: String,
        wordCount: MnemonicManager.WordCount = MnemonicManager.WordCount.TWELVE
    ): Pair<WalletEntity, List<String>> {
        val (info, words) = keyManager.generateWalletWithMnemonic(wordCount)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        // Store keys in wallet-scoped prefs
        keyManager.storeKeysForWallet(walletId, keyManager.getPrivateKey(), words)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_MNEMONIC,
            derivationPath = "m/44'/309'/0'/0/0",
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = info.mainnetAddress,
            testnetAddress = info.testnetAddress,
            isActive = true,
            createdAt = now,
            lastActiveAt = now,
            colorIndex = colorIndex
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)
        Log.d(TAG, "Created wallet: ${entity.walletId} (${entity.name})")
        return Pair(entity, words)
    }

    /**
     * Import a wallet from a mnemonic phrase.
     */
    suspend fun importWallet(
        name: String,
        words: List<String>,
        passphrase: String = ""
    ): WalletEntity {
        val info = keyManager.importWalletFromMnemonic(words, passphrase)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        keyManager.storeKeysForWallet(walletId, keyManager.getPrivateKey(), words)
        keyManager.setMnemonicBackedUpForWallet(walletId, true)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_MNEMONIC,
            derivationPath = "m/44'/309'/0'/0/0",
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = info.mainnetAddress,
            testnetAddress = info.testnetAddress,
            isActive = true,
            createdAt = now,
            lastActiveAt = now,
            colorIndex = colorIndex
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)
        Log.d(TAG, "Imported wallet: ${entity.walletId} (${entity.name})")
        return entity
    }

    /**
     * Import a wallet from a raw private key hex string.
     */
    suspend fun importRawKey(
        name: String,
        privateKeyHex: String
    ): WalletEntity {
        val info = keyManager.importWallet(privateKeyHex)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        keyManager.storeKeysForWallet(walletId, keyManager.getPrivateKey(), null)
        keyManager.setMnemonicBackedUpForWallet(walletId, true)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_RAW_KEY,
            derivationPath = null,
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = info.mainnetAddress,
            testnetAddress = info.testnetAddress,
            isActive = true,
            createdAt = now,
            lastActiveAt = now,
            colorIndex = colorIndex
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)
        Log.d(TAG, "Imported raw key wallet: ${entity.walletId} (${entity.name})")
        return entity
    }

    /**
     * Create a sub-account derived from a parent mnemonic wallet.
     * Derives a new key at the next account index from the parent's mnemonic.
     */
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
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        // Sub-accounts don't store mnemonic — only the parent holds it
        keyManager.storeKeysForWallet(walletId, privateKey, null)

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
            createdAt = now,
            lastActiveAt = now,
            colorIndex = colorIndex
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)
        Log.d(TAG, "Created sub-account: $walletId (parent: $parentWalletId, index: $nextIndex)")

        return entity
    }

    /**
     * Switch active wallet. Updates Room, preferences, and last-active timestamp.
     */
    suspend fun switchActiveWallet(walletId: String) {
        val wallet = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        walletDao.deactivateAll()
        walletDao.activate(walletId)
        walletDao.updateLastActiveAt(walletId, System.currentTimeMillis())
        walletPreferences.setActiveWalletId(walletId)
        Log.d(TAG, "Switched to wallet: $walletId")
    }

    /**
     * Rename a wallet.
     */
    suspend fun renameWallet(walletId: String, newName: String) {
        walletDao.updateName(walletId, newName)
    }

    /**
     * Delete a wallet and its keys. Runs VACUUM afterward to reclaim freed pages.
     */
    suspend fun deleteWallet(walletId: String) {
        keyManager.deleteWalletKeys(walletId)
        walletDao.delete(walletId)
        DatabaseMaintenanceUtil.vacuum(appDatabase)
        Log.d(TAG, "Deleted wallet: $walletId")
    }

    suspend fun walletCount(): Int = walletDao.count()
}
