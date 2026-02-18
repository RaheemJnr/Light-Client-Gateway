package com.rjnr.pocketnode.ui.screens.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun SettingsScreen(
    onNavigateToNodeStatus: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToSecuritySettings: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
) {
    Text("Settings — Coming soon")
}
