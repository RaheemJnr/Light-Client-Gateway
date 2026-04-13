package com.rjnr.pocketnode.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateDownloader"
private const val APK_FILE_NAME = "pocket-node-update.apk"

@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var downloadId: Long = -1L
    private var receiver: BroadcastReceiver? = null

    /**
     * Whether the user has granted permission to install from unknown sources.
     */
    fun canInstallPackages(): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * Returns an intent that opens the system settings for allowing installs from this app.
     */
    fun getInstallPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
    }

    /**
     * Downloads the APK from [apkUrl] using DownloadManager and installs it when complete.
     */
    fun downloadAndInstall(apkUrl: String) {
        cleanup()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Pocket Node Update")
            .setDescription("Downloading latest version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)

        downloadId = downloadManager.enqueue(request)
        Log.d(TAG, "Download enqueued: id=$downloadId")

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    downloadManager.query(query).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = cursor.getInt(statusIndex)
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                Log.d(TAG, "Download complete, installing APK")
                                installApk()
                            } else {
                                Log.w(TAG, "Download finished with status: $status")
                            }
                        }
                    }
                } finally {
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: IllegalArgumentException) {
                        // Already unregistered
                    }
                    receiver = null
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * Installs the downloaded APK via a FileProvider content URI.
     */
    private fun installApk() {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found at ${apkFile.absolutePath}")
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    /**
     * Cleans up any previous download artifacts and unregisters the receiver.
     */
    fun cleanup() {
        // Unregister any pending receiver
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered
            }
            receiver = null
        }

        // Delete any existing APK file
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        if (apkFile.exists()) {
            val deleted = apkFile.delete()
            Log.d(TAG, "Cleaned up previous APK: deleted=$deleted")
        }
    }
}
