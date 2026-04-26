package com.rjnr.pocketnode.data.migration

import android.util.Log
import androidx.room.withTransaction
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WalletMigrationHelper"

/**
 * Idempotent migration from single-wallet (global EncryptedSharedPreferences)
 * to multi-wallet (Room + wallet-scoped prefs).
 *
 * Runs once at startup. If the wallets table is empty but the legacy KeyManager
 * has a private key, it creates a WalletEntity, copies keys to wallet-scoped prefs,
 * and backfills walletId in existing cached data.
 */
@Singleton
class WalletMigrationHelper @Inject constructor(
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences,
    private val database: AppDatabase,
    private val syncProgressDao: SyncProgressDao
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

            // Backfill walletId in existing cached data
            val db = database.openHelper.writableDatabase
            db.execSQL("UPDATE transactions SET walletId = ? WHERE walletId = ''", arrayOf(walletId))
            db.execSQL("UPDATE balance_cache SET walletId = ? WHERE walletId = ''", arrayOf(walletId))
            db.execSQL("UPDATE dao_cells SET walletId = ? WHERE walletId = ''", arrayOf(walletId))

            // Set active wallet ID in preferences
            walletPreferences.setActiveWalletId(walletId)

            Log.d(TAG, "Migration complete: created wallet $walletId")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed — legacy wallet intact, will retry next launch", e)
        }
    }

    /**
     * Idempotent migration: copy per-wallet `lastSyncedBlock` from SharedPreferences
     * to the v7 `sync_progress` Room table, then delete the prefs keys.
     * Returns true if the migration ran (regardless of whether rows were written),
     * false if the guard flag was already set.
     *
     * Key format read from SharedPrefs: "${walletId}_${network.lowercase()}_last_synced_block"
     * (matches WalletPreferences.walletNetworkKey at WalletPreferences.kt:78-79, 144).
     *
     * Migrated rows seed `lightStartBlockNumber = localSavedBlockNumber` because
     * the original start block was never recorded.
     */
    suspend fun migrateSyncProgressToRoomIfNeeded(): Boolean {
        val rawPrefs = walletPreferences.rawPrefs
        if (rawPrefs.getBoolean(KEY_SYNC_PROGRESS_MIGRATED, false)) return false

        val now = System.currentTimeMillis()
        val wallets = walletDao.getAll()
        val networks = listOf(NetworkType.MAINNET, NetworkType.TESTNET)

        database.withTransaction {
            for (wallet in wallets) {
                for (net in networks) {
                    val key = "${wallet.walletId}_${net.name.lowercase()}_last_synced_block"
                    if (!rawPrefs.contains(key)) continue
                    val block = rawPrefs.getLong(key, 0L)
                    if (block <= 0L) continue

                    syncProgressDao.upsert(
                        SyncProgressEntity(
                            walletId = wallet.walletId,
                            network = net.name,
                            lightStartBlockNumber = block,
                            localSavedBlockNumber = block,
                            updatedAt = now
                        )
                    )
                }
            }
        }

        // Always remove every legacy key (even those skipped above for block <= 0):
        // the entire `*_last_synced_block` prefs namespace is being retired.
        val editor = rawPrefs.edit()
        for (wallet in wallets) {
            for (net in networks) {
                editor.remove("${wallet.walletId}_${net.name.lowercase()}_last_synced_block")
            }
        }
        editor.putBoolean(KEY_SYNC_PROGRESS_MIGRATED, true).commit()

        Log.d(TAG, "sync_progress migration complete: ${wallets.size} wallets x ${networks.size} networks")
        return true
    }

    companion object {
        private const val KEY_SYNC_PROGRESS_MIGRATED = "sync_progress_migrated_to_room_v7"
    }
}
