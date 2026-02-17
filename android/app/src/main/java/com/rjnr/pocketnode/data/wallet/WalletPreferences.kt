package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages wallet preferences for persisting user settings like sync mode.
 * All per-network preferences are namespaced by network name to prevent cross-contamination.
 */
@Singleton
class WalletPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    init {
        migrateIfNeeded()
    }

    // --- Network selection (global, not namespaced) ---

    fun getSelectedNetwork(): NetworkType {
        val name = prefs.getString(KEY_SELECTED_NETWORK, NetworkType.MAINNET.name)
        return try {
            NetworkType.valueOf(name ?: NetworkType.MAINNET.name)
        } catch (e: IllegalArgumentException) {
            NetworkType.MAINNET
        }
    }

    fun setSelectedNetwork(network: NetworkType) {
        prefs.edit().putString(KEY_SELECTED_NETWORK, network.name).apply()
    }

    // --- Per-network key helper ---

    private fun networkKey(key: String, network: NetworkType? = null): String {
        val net = network ?: getSelectedNetwork()
        return "${net.name.lowercase()}_$key"
    }

    // --- Sync mode ---

    fun getSyncMode(network: NetworkType? = null): SyncMode {
        val modeName = prefs.getString(networkKey(KEY_SYNC_MODE, network), SyncMode.RECENT.name)
        return try {
            SyncMode.valueOf(modeName ?: SyncMode.RECENT.name)
        } catch (e: IllegalArgumentException) {
            SyncMode.RECENT
        }
    }

    fun setSyncMode(mode: SyncMode, network: NetworkType? = null) {
        prefs.edit().putString(networkKey(KEY_SYNC_MODE, network), mode.name).apply()
    }

    // --- Custom block height ---

    fun getCustomBlockHeight(network: NetworkType? = null): Long? {
        val height = prefs.getLong(networkKey(KEY_CUSTOM_BLOCK_HEIGHT, network), -1L)
        return if (height >= 0) height else null
    }

    fun setCustomBlockHeight(height: Long?, network: NetworkType? = null) {
        if (height != null) {
            prefs.edit().putLong(networkKey(KEY_CUSTOM_BLOCK_HEIGHT, network), height).apply()
        } else {
            prefs.edit().remove(networkKey(KEY_CUSTOM_BLOCK_HEIGHT, network)).apply()
        }
    }

    // --- Initial sync ---

    fun hasCompletedInitialSync(network: NetworkType? = null): Boolean {
        return prefs.getBoolean(networkKey(KEY_INITIAL_SYNC_COMPLETED, network), false)
    }

    fun setInitialSyncCompleted(completed: Boolean, network: NetworkType? = null) {
        prefs.edit().putBoolean(networkKey(KEY_INITIAL_SYNC_COMPLETED, network), completed).apply()
    }

    // --- Last synced block ---

    fun getLastSyncedBlock(network: NetworkType? = null): Long {
        return prefs.getLong(networkKey(KEY_LAST_SYNCED_BLOCK, network), 0L)
    }

    fun setLastSyncedBlock(blockNumber: Long, network: NetworkType? = null) {
        prefs.edit().putLong(networkKey(KEY_LAST_SYNCED_BLOCK, network), blockNumber).apply()
    }

    // --- Utilities ---

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * One-time migration: moves old un-namespaced keys to mainnet-namespaced keys.
     * Existing users upgrading from pre-testnet versions have un-namespaced sync prefs
     * that belong to mainnet. This copies them to "mainnet_" prefixed keys.
     */
    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_SELECTED_NETWORK)) return // already migrated

        val editor = prefs.edit()
        var needsMigration = false

        // Migrate sync_mode
        prefs.getString(KEY_SYNC_MODE, null)?.let { oldValue ->
            editor.putString("mainnet_$KEY_SYNC_MODE", oldValue)
            editor.remove(KEY_SYNC_MODE)
            needsMigration = true
        }

        // Migrate custom_block_height
        if (prefs.contains(KEY_CUSTOM_BLOCK_HEIGHT)) {
            val oldValue = prefs.getLong(KEY_CUSTOM_BLOCK_HEIGHT, -1L)
            if (oldValue >= 0) {
                editor.putLong("mainnet_$KEY_CUSTOM_BLOCK_HEIGHT", oldValue)
            }
            editor.remove(KEY_CUSTOM_BLOCK_HEIGHT)
            needsMigration = true
        }

        // Migrate initial_sync_completed
        if (prefs.contains(KEY_INITIAL_SYNC_COMPLETED)) {
            val oldValue = prefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
            editor.putBoolean("mainnet_$KEY_INITIAL_SYNC_COMPLETED", oldValue)
            editor.remove(KEY_INITIAL_SYNC_COMPLETED)
            needsMigration = true
        }

        // Migrate last_synced_block
        if (prefs.contains(KEY_LAST_SYNCED_BLOCK)) {
            val oldValue = prefs.getLong(KEY_LAST_SYNCED_BLOCK, 0L)
            editor.putLong("mainnet_$KEY_LAST_SYNCED_BLOCK", oldValue)
            editor.remove(KEY_LAST_SYNCED_BLOCK)
            needsMigration = true
        }

        // Set default network (always, even if no old keys existed)
        editor.putString(KEY_SELECTED_NETWORK, NetworkType.MAINNET.name)
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "ckb_wallet_prefs"
        private const val KEY_SELECTED_NETWORK = "selected_network"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_CUSTOM_BLOCK_HEIGHT = "custom_block_height"
        private const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
        private const val KEY_LAST_SYNCED_BLOCK = "last_synced_block"
    }
}
