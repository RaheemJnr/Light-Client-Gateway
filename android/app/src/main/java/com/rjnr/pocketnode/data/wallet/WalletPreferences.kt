package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SyncStrategy { ACTIVE_ONLY, ALL_WALLETS, BALANCED }

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

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private fun readThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun getThemeMode(): ThemeMode = _themeMode.value

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    init {
        migrateIfNeeded()
    }

    // --- Network selection (global, not namespaced) ---

    fun getSelectedNetwork(): NetworkType {
        val name = prefs.getString(KEY_SELECTED_NETWORK, NetworkType.MAINNET.name)
        return try {
            NetworkType.valueOf(name ?: NetworkType.MAINNET.name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown network name '$name', defaulting to MAINNET", e)
            NetworkType.MAINNET
        }
    }

    fun setSelectedNetwork(network: NetworkType) {
        // commit() instead of apply() — must flush synchronously before Process.killProcess()
        prefs.edit().putString(KEY_SELECTED_NETWORK, network.name).commit()
    }

    // --- Per-network key helper ---

    private fun networkKey(key: String, network: NetworkType? = null): String {
        val net = network ?: getSelectedNetwork()
        return "${net.name.lowercase()}_$key"
    }

    private fun walletNetworkKey(walletId: String, network: String, key: String): String =
        "${walletId}_${network.lowercase()}_$key"

    // --- Sync mode ---

    fun getSyncMode(network: NetworkType? = null, walletId: String? = null): SyncMode {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_SYNC_MODE)
                  else networkKey(KEY_SYNC_MODE, net)
        val modeName = prefs.getString(key, SyncMode.RECENT.name)
        return try {
            SyncMode.valueOf(modeName ?: SyncMode.RECENT.name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown sync mode '$modeName', defaulting to RECENT", e)
            SyncMode.RECENT
        }
    }

    fun setSyncMode(mode: SyncMode, network: NetworkType? = null, walletId: String? = null) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_SYNC_MODE)
                  else networkKey(KEY_SYNC_MODE, net)
        prefs.edit().putString(key, mode.name).apply()
    }

    // --- Custom block height ---

    fun getCustomBlockHeight(network: NetworkType? = null, walletId: String? = null): Long? {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_CUSTOM_BLOCK_HEIGHT)
                  else networkKey(KEY_CUSTOM_BLOCK_HEIGHT, net)
        val height = prefs.getLong(key, -1L)
        return if (height >= 0) height else null
    }

    fun setCustomBlockHeight(height: Long?, network: NetworkType? = null, walletId: String? = null) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_CUSTOM_BLOCK_HEIGHT)
                  else networkKey(KEY_CUSTOM_BLOCK_HEIGHT, net)
        if (height != null) {
            prefs.edit().putLong(key, height).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    // --- Initial sync ---

    fun hasCompletedInitialSync(network: NetworkType? = null, walletId: String? = null): Boolean {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_INITIAL_SYNC_COMPLETED)
                  else networkKey(KEY_INITIAL_SYNC_COMPLETED, net)
        return prefs.getBoolean(key, false)
    }

    fun setInitialSyncCompleted(completed: Boolean, network: NetworkType? = null, walletId: String? = null) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_INITIAL_SYNC_COMPLETED)
                  else networkKey(KEY_INITIAL_SYNC_COMPLETED, net)
        prefs.edit().putBoolean(key, completed).apply()
    }

    // --- Last synced block ---

    fun getLastSyncedBlock(network: NetworkType? = null, walletId: String? = null): Long {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_LAST_SYNCED_BLOCK)
                  else networkKey(KEY_LAST_SYNCED_BLOCK, net)
        return prefs.getLong(key, 0L)
    }

    fun setLastSyncedBlock(blockNumber: Long, network: NetworkType? = null, walletId: String? = null) {
        val net = network ?: getSelectedNetwork()
        val key = if (walletId != null) walletNetworkKey(walletId, net.name, KEY_LAST_SYNCED_BLOCK)
                  else networkKey(KEY_LAST_SYNCED_BLOCK, net)
        prefs.edit().putLong(key, blockNumber).apply()
    }

    // --- Background sync (global, not per-network) ---

    fun isBackgroundSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_SYNC, true)
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_SYNC, enabled).commit()
    }

    // --- Database maintenance ---

    fun getLastVacuumAt(): Long = prefs.getLong(KEY_LAST_VACUUM_AT, 0L)

    fun setLastVacuumAt(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_VACUUM_AT, timestampMs).apply()
    }

    // --- Active wallet (M3 multi-wallet) ---

    fun getActiveWalletId(): String? = prefs.getString(KEY_ACTIVE_WALLET_ID, null)

    fun setActiveWalletId(walletId: String) {
        prefs.edit().putString(KEY_ACTIVE_WALLET_ID, walletId).apply()
    }

    // --- Sync strategy (M3 multi-wallet) ---

    fun getSyncStrategy(): SyncStrategy {
        val name = prefs.getString(KEY_SYNC_STRATEGY, SyncStrategy.ALL_WALLETS.name)
        return try {
            SyncStrategy.valueOf(name ?: SyncStrategy.ALL_WALLETS.name)
        } catch (_: Exception) {
            SyncStrategy.ALL_WALLETS
        }
    }

    fun setSyncStrategy(strategy: SyncStrategy) {
        prefs.edit().putString(KEY_SYNC_STRATEGY, strategy.name).apply()
    }

    // --- Utilities ---

    // Clearing prefs removes KEY_SELECTED_NETWORK, so migrateIfNeeded() re-runs on next startup.
    // That's benign: old un-namespaced keys are already gone, it just re-sets default to MAINNET.
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
        val mainnetPrefix = "${NetworkType.MAINNET.name.lowercase()}_"

        // Migrate sync_mode
        prefs.getString(KEY_SYNC_MODE, null)?.let { oldValue ->
            editor.putString("${mainnetPrefix}$KEY_SYNC_MODE", oldValue)
            editor.remove(KEY_SYNC_MODE)
        }

        // Migrate custom_block_height
        if (prefs.contains(KEY_CUSTOM_BLOCK_HEIGHT)) {
            val oldValue = prefs.getLong(KEY_CUSTOM_BLOCK_HEIGHT, -1L)
            if (oldValue >= 0) {
                editor.putLong("${mainnetPrefix}$KEY_CUSTOM_BLOCK_HEIGHT", oldValue)
            }
            editor.remove(KEY_CUSTOM_BLOCK_HEIGHT)
        }

        // Migrate initial_sync_completed
        if (prefs.contains(KEY_INITIAL_SYNC_COMPLETED)) {
            val oldValue = prefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
            editor.putBoolean("${mainnetPrefix}$KEY_INITIAL_SYNC_COMPLETED", oldValue)
            editor.remove(KEY_INITIAL_SYNC_COMPLETED)
        }

        // Migrate last_synced_block
        if (prefs.contains(KEY_LAST_SYNCED_BLOCK)) {
            val oldValue = prefs.getLong(KEY_LAST_SYNCED_BLOCK, 0L)
            editor.putLong("${mainnetPrefix}$KEY_LAST_SYNCED_BLOCK", oldValue)
            editor.remove(KEY_LAST_SYNCED_BLOCK)
        }

        // Set default network (always, even if no old keys existed)
        editor.putString(KEY_SELECTED_NETWORK, NetworkType.MAINNET.name)
        editor.commit() // Synchronous to ensure migration guard persists before process death
    }

    companion object {
        private const val TAG = "WalletPreferences"
        private const val PREFS_NAME = "ckb_wallet_prefs"
        private const val KEY_SELECTED_NETWORK = "selected_network"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_CUSTOM_BLOCK_HEIGHT = "custom_block_height"
        private const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
        private const val KEY_LAST_SYNCED_BLOCK = "last_synced_block"
        private const val KEY_ACTIVE_WALLET_ID = "active_wallet_id"
        private const val KEY_SYNC_STRATEGY = "sync_strategy"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BACKGROUND_SYNC = "background_sync_enabled"
        private const val KEY_LAST_VACUUM_AT = "last_vacuum_at"
    }
}
