package com.rjnr.pocketnode.data.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SyncForegroundService : Service() {

    @Inject lateinit var gatewayRepository: GatewayRepository
    @Inject lateinit var walletPreferences: WalletPreferences
    @Inject lateinit var syncNotificationManager: SyncNotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand (flags=$flags, startId=$startId)")
        startAsForeground()

        // Guard against duplicate coroutines on repeated onStartCommand
        if (observeJob?.isActive != true) {
            serviceScope.launch {
                // Re-init wallet if needed (e.g. after process restart via START_STICKY)
                if (gatewayRepository.walletInfo.value == null) {
                    Log.d(TAG, "Wallet not initialized, attempting re-init")
                    gatewayRepository.initializeWallet()
                }
                gatewayRepository.startSyncPolling()
            }
            observeProgress()
        }

        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = syncNotificationManager.buildSyncingNotification(0, "Starting...")
        ServiceCompat.startForeground(
            this,
            SyncNotificationManager.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun observeProgress() {
        observeJob = serviceScope.launch {
            gatewayRepository.syncProgress.collectLatest { progress ->
                val notification = if (progress.isSyncing) {
                    syncNotificationManager.buildSyncingNotification(
                        progress.percentage.toInt(),
                        progress.etaDisplay
                    )
                } else {
                    syncNotificationManager.buildSyncedNotification()
                }
                syncNotificationManager.notify(notification)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    companion object {
        private const val TAG = "SyncForegroundService"

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
