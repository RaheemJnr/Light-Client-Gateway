package com.rjnr.pocketnode

import android.app.Application
import com.rjnr.pocketnode.data.sync.BroadcastWatchdog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CkbWalletApp : Application() {
    @Inject lateinit var broadcastWatchdog: BroadcastWatchdog

    override fun onCreate() {
        super.onCreate()
        broadcastWatchdog.start()
    }
}
