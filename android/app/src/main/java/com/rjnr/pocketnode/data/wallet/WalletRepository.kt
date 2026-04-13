package com.rjnr.pocketnode.data.wallet

import android.util.Log
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
    private val keyManager: KeyManager
) {
    val walletsFlow: Flow<List<WalletEntity>> = walletDao.getAllFlow()

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
            createdAt = System.currentTimeMillis()
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
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
            createdAt = System.currentTimeMillis()
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
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
            createdAt = System.currentTimeMillis()
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        Log.d(TAG, "Imported raw key wallet: ${entity.walletId} (${entity.name})")
        return entity
    }

    /**
     * Switch active wallet.
     */
    suspend fun switchWallet(walletId: String) {
        walletDao.deactivateAll()
        walletDao.activate(walletId)
        Log.d(TAG, "Switched to wallet: $walletId")
    }

    /**
     * Rename a wallet.
     */
    suspend fun renameWallet(walletId: String, newName: String) {
        walletDao.updateName(walletId, newName)
    }

    /**
     * Delete a wallet and its keys.
     */
    suspend fun deleteWallet(walletId: String) {
        keyManager.deleteWalletKeys(walletId)
        walletDao.delete(walletId)
        Log.d(TAG, "Deleted wallet: $walletId")
    }

    suspend fun walletCount(): Int = walletDao.count()
}
