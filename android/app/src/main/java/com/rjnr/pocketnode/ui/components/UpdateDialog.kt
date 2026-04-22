package com.rjnr.pocketnode.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.rjnr.pocketnode.data.update.UpdateInfo

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sizeText = if (updateInfo.fileSize > 0) {
        val sizeMb = updateInfo.fileSize / (1024.0 * 1024.0)
        "\n\nDownload size: %.1f MB".format(sizeMb)
    } else {
        ""
    }

    val messageText = "Version ${updateInfo.latestVersion} is available." +
        (if (updateInfo.releaseNotes.isNotBlank()) {
            "\n\n${updateInfo.releaseNotes.take(500)}"
        } else "") +
        sizeText

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Available") },
        text = { Text(messageText) },
        confirmButton = {
            Button(onClick = onUpdate) {
                Text("Update Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}
