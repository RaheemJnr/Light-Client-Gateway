package com.rjnr.pocketnode.data.migration

import android.util.Log
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Migrates existing single-wallet data into the multi-wallet schema.
 * Idempotent: no-ops if wallets table already has rows or no legacy wallet exists.
 */
@Singleton
class WalletMigrationHelper @Inject constructor(
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences,
    private val database: AppDatabase
) {
    suspend fun migrateIfNeeded() {
        if (walletDao.count() > 0) return  // already migrated
        if (!keyManager.hasWallet()) return // fresh install, nothing to migrate

        Log.d(TAG, "Migrating single wallet to multi-wallet schema...")

        val walletId = UUID.randomUUID().toString()
        val walletInfo = keyManager.getWalletInfo()
        val walletType = keyManager.getWalletType()
        val mnemonic = keyManager.getMnemonic()

        // Copy keys to wallet-scoped ESP
        val privateKey = keyManager.getPrivateKey()
        keyManager.storeKeysForWallet(walletId, privateKey, mnemonic)

        // Insert wallet entity
        walletDao.insert(
            WalletEntity(
                walletId = walletId,
                name = "My Wallet",
                type = walletType,
                derivationPath = if (walletType == KeyManager.WALLET_TYPE_MNEMONIC) "m/44'/309'/0'/0/0" else null,
                parentWalletId = null,
                accountIndex = 0,
                mainnetAddress = walletInfo.mainnetAddress,
                testnetAddress = walletInfo.testnetAddress,
                isActive = true,
                createdAt = System.currentTimeMillis()
            )
        )

        // Backfill walletId in existing Room rows
        val db = database.openHelper.writableDatabase
        db.execSQL("UPDATE transactions SET walletId = ? WHERE walletId = ''", arrayOf(walletId))
        db.execSQL("UPDATE balance_cache SET walletId = ? WHERE walletId = ''", arrayOf(walletId))
        db.execSQL("UPDATE dao_cells SET walletId = ? WHERE walletId = ''", arrayOf(walletId))

        walletPreferences.setActiveWalletId(walletId)

        Log.d(TAG, "Migration complete: wallet $walletId created")
    }

    companion object {
        private const val TAG = "WalletMigrationHelper"
    }
}
