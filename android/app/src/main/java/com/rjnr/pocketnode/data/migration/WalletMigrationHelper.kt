package com.rjnr.pocketnode.data.migration

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.KeyManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WalletMigrationHelper"

/**
 * Idempotent migration from single-wallet (global EncryptedSharedPreferences)
 * to multi-wallet (Room + wallet-scoped prefs).
 *
 * Runs once at startup. If the wallets table is empty but the legacy KeyManager
 * has a private key, it creates a WalletEntity and copies the keys to wallet-scoped prefs.
 */
@Singleton
class WalletMigrationHelper @Inject constructor(
    private val walletDao: WalletDao,
    private val keyManager: KeyManager
) {
    /**
     * Migrate the legacy single-wallet to the multi-wallet Room table.
     * No-op if migration has already run (wallets table is non-empty).
     */
    suspend fun migrateIfNeeded() {
        if (walletDao.count() > 0) return  // already migrated
        if (!keyManager.hasWallet()) return // no legacy wallet to migrate

        Log.d(TAG, "Migrating legacy single-wallet to multi-wallet schema")

        try {
            val info = keyManager.getWalletInfo()
            val privateKey = keyManager.getPrivateKey()
            val mnemonic = keyManager.getMnemonic()
            val walletType = keyManager.getWalletType()
            val walletId = UUID.randomUUID().toString()

            // Store keys in wallet-scoped prefs
            keyManager.storeKeysForWallet(walletId, privateKey, mnemonic)

            // Copy backup flag
            if (keyManager.hasMnemonicBackup()) {
                keyManager.setMnemonicBackedUpForWallet(walletId, true)
            }

            val entity = WalletEntity(
                walletId = walletId,
                name = "Primary Wallet",
                type = walletType,
                derivationPath = if (walletType == KeyManager.WALLET_TYPE_MNEMONIC) "m/44'/309'/0'/0/0" else null,
                parentWalletId = null,
                accountIndex = 0,
                mainnetAddress = info.mainnetAddress,
                testnetAddress = info.testnetAddress,
                isActive = true,
                createdAt = System.currentTimeMillis()
            )

            walletDao.insert(entity)
            Log.d(TAG, "Migration complete: created wallet ${entity.walletId}")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed — legacy wallet intact, will retry next launch", e)
        }
    }
}
