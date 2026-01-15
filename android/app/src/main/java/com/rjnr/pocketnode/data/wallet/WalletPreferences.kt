package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages wallet preferences for persisting user settings like sync mode.
 */
@Singleton
class WalletPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Get the saved sync mode
     */
    fun getSyncMode(): SyncMode {
        val modeName = prefs.getString(KEY_SYNC_MODE, SyncMode.RECENT.name)
        return try {
            SyncMode.valueOf(modeName ?: SyncMode.RECENT.name)
        } catch (e: IllegalArgumentException) {
            SyncMode.RECENT
        }
    }

    /**
     * Save the sync mode
     */
    fun setSyncMode(mode: SyncMode) {
        prefs.edit().putString(KEY_SYNC_MODE, mode.name).apply()
    }

    /**
     * Get the custom block height (for CUSTOM sync mode)
     */
    fun getCustomBlockHeight(): Long? {
        val height = prefs.getLong(KEY_CUSTOM_BLOCK_HEIGHT, -1L)
        return if (height >= 0) height else null
    }

    /**
     * Save the custom block height
     */
    fun setCustomBlockHeight(height: Long?) {
        if (height != null) {
            prefs.edit().putLong(KEY_CUSTOM_BLOCK_HEIGHT, height).apply()
        } else {
            prefs.edit().remove(KEY_CUSTOM_BLOCK_HEIGHT).apply()
        }
    }

    /**
     * Check if the user has completed initial sync setup
     */
    fun hasCompletedInitialSync(): Boolean {
        return prefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
    }

    /**
     * Mark initial sync as completed
     */
    fun setInitialSyncCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_INITIAL_SYNC_COMPLETED, completed).apply()
    }

    /**
     * Get the last synced block number for tracking
     */
    fun getLastSyncedBlock(): Long {
        return prefs.getLong(KEY_LAST_SYNCED_BLOCK, 0L)
    }

    /**
     * Save the last synced block number
     */
    fun setLastSyncedBlock(blockNumber: Long) {
        prefs.edit().putLong(KEY_LAST_SYNCED_BLOCK, blockNumber).apply()
    }

    /**
     * Clear all preferences (for testing or wallet reset)
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "ckb_wallet_prefs"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_CUSTOM_BLOCK_HEIGHT = "custom_block_height"
        private const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
        private const val KEY_LAST_SYNCED_BLOCK = "last_synced_block"
    }
}
