package com.rjnr.pocketnode.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.displayName

private const val GITHUB_URL = "https://github.com/rjnr-dev/pocket-node"

private val ColorGreen = Color(0xFF1ED882)
private val ColorAmber = Color(0xFFF59E0B)
private val ColorSecondary = Color(0xFFA0A0A0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToNodeStatus: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToSecuritySettings: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Sync options dialog
    if (uiState.showSyncDialog) {
        SyncOptionsDialog(
            currentMode = uiState.syncMode,
            onDismiss = { viewModel.hideSyncDialog() },
            onSelectMode = { mode, customBlock -> viewModel.setSyncMode(mode, customBlock) }
        )
    }

    // Network switch confirmation dialog
    val pendingSwitch = uiState.pendingNetworkSwitch
    if (uiState.showNetworkSwitchDialog && pendingSwitch != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelNetworkSwitch() },
            title = { Text("Switch to CKB ${pendingSwitch.displayName}?") },
            text = {
                Text(
                    "The app will close and reopen on CKB ${pendingSwitch.displayName}. " +
                        "Your wallet and data on the current network are safe — " +
                        "you can switch back at any time."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmNetworkSwitch() }) {
                    Text("Switch & Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelNetworkSwitch() }) {
                    Text("Cancel")
                }
            }
        )
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── SECURITY ──────────────────────────────────────────────────
            item { SectionHeader("SECURITY") }

            item {
                SettingsRow(
                    icon = Icons.Default.Lock,
                    title = "PIN Lock",
                    trailingContent = {
                        StatusPill(
                            enabled = uiState.isPinEnabled,
                            enabledLabel = "Enabled",
                            disabledLabel = "Disabled"
                        )
                    },
                    onClick = onNavigateToSecuritySettings
                )
            }

            item {
                SettingsRow(
                    icon = Icons.Default.Fingerprint,
                    title = "Biometric Unlock",
                    trailingContent = {
                        StatusPill(
                            enabled = uiState.isBiometricEnabled,
                            enabledLabel = "Enabled",
                            disabledLabel = "Disabled"
                        )
                    },
                    onClick = onNavigateToSecuritySettings
                )
            }

            // ── WALLET ────────────────────────────────────────────────────
            item { SectionHeader("WALLET") }

            item {
                SettingsRow(
                    icon = Icons.Default.Backup,
                    title = "Backup Wallet",
                    trailingContent = { ChevronTrailing() },
                    onClick = onNavigateToBackup
                )
            }

            item {
                SettingsRow(
                    icon = Icons.Default.Download,
                    title = "Import Wallet",
                    trailingContent = { ChevronTrailing() },
                    onClick = onNavigateToImport
                )
            }

            item {
                SettingsRow(
                    icon = Icons.Default.Sync,
                    title = "Sync Options",
                    trailingContent = {
                        ValuePill(text = syncModeLabel(uiState.syncMode))
                    },
                    onClick = { viewModel.showSyncDialog() }
                )
            }

            // ── NETWORK ───────────────────────────────────────────────────
            item { SectionHeader("NETWORK") }

            item {
                val isTestnet = uiState.currentNetwork == NetworkType.TESTNET
                SettingsRow(
                    icon = Icons.Default.Language,
                    title = "Current Network",
                    trailingContent = {
                        ValuePill(
                            text = "CKB ${uiState.currentNetwork.displayName}",
                            color = if (isTestnet) ColorAmber else ColorGreen
                        )
                    },
                    onClick = {
                        val target = if (isTestnet) NetworkType.MAINNET else NetworkType.TESTNET
                        viewModel.requestNetworkSwitch(target)
                    }
                )
            }

            item {
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = "Node Status & Logs",
                    trailingContent = { ChevronTrailing() },
                    onClick = onNavigateToNodeStatus
                )
            }

            // ── ABOUT ─────────────────────────────────────────────────────
            item { SectionHeader("ABOUT") }

            item {
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = "Version",
                    trailingContent = {
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorSecondary
                        )
                    },
                    onClick = null
                )
            }

            item {
                SettingsRow(
                    icon = Icons.Default.OpenInNew,
                    title = "Open Source",
                    trailingContent = {
                        Text(
                            text = "GitHub \u2197",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorGreen
                        )
                    },
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                        context.startActivity(intent)
                    }
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── Section header ─────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                letterSpacing = 1.2.sp
            ),
            fontWeight = FontWeight.SemiBold,
            color = ColorSecondary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
        HorizontalDivider(color = Color(0xFF252525))
    }
}

// ── Settings row ───────────────────────────────────────────────────────────────

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    trailingContent: @Composable () -> Unit,
    onClick: (() -> Unit)?
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = modifier.padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = ColorSecondary
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        trailingContent()
    }
}

// ── Trailing content helpers ───────────────────────────────────────────────────

@Composable
private fun StatusPill(
    enabled: Boolean,
    enabledLabel: String = "Enabled",
    disabledLabel: String = "Disabled"
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) ColorGreen.copy(alpha = 0.12f) else Color(0xFF252525)
    ) {
        Text(
            text = if (enabled) enabledLabel else disabledLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (enabled) ColorGreen else ColorSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ValuePill(
    text: String,
    color: Color = ColorSecondary
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color
    )
}

@Composable
private fun ChevronTrailing() {
    Icon(
        imageVector = Icons.Default.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = ColorSecondary
    )
}

// ── Sync options dialog ─────────────────────────────────────────────────────────

@Composable
private fun SyncOptionsDialog(
    currentMode: SyncMode,
    onDismiss: () -> Unit,
    onSelectMode: (SyncMode, Long?) -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }
    var customBlockHeight by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(currentMode == SyncMode.CUSTOM) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Options", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Choose how much transaction history to sync:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))

                SyncOptionItem(
                    title = "New Wallet",
                    description = "No history — fastest startup",
                    icon = Icons.Default.FiberNew,
                    isSelected = selectedMode == SyncMode.NEW_WALLET,
                    onClick = { selectedMode = SyncMode.NEW_WALLET; showCustomInput = false }
                )
                SyncOptionItem(
                    title = "Recent (~30 days)",
                    description = "Last ~200k blocks — recommended",
                    icon = Icons.Default.Schedule,
                    isSelected = selectedMode == SyncMode.RECENT,
                    onClick = { selectedMode = SyncMode.RECENT; showCustomInput = false }
                )
                SyncOptionItem(
                    title = "Full History",
                    description = "From genesis — complete but slow",
                    icon = Icons.Default.History,
                    isSelected = selectedMode == SyncMode.FULL_HISTORY,
                    onClick = { selectedMode = SyncMode.FULL_HISTORY; showCustomInput = false }
                )
                SyncOptionItem(
                    title = "Custom Block Height",
                    description = "Start from a specific block",
                    icon = Icons.Default.Tune,
                    isSelected = selectedMode == SyncMode.CUSTOM,
                    onClick = { selectedMode = SyncMode.CUSTOM; showCustomInput = true }
                )

                if (showCustomInput) {
                    Spacer(Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Transactions before this block won't appear in history, but your balance will still be correct.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customBlockHeight,
                        onValueChange = { customBlockHeight = it.filter { c -> c.isDigit() } },
                        label = { Text("Block Height") },
                        placeholder = { Text("e.g., 12000000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                if (selectedMode == SyncMode.FULL_HISTORY) {
                    Spacer(Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Full history sync may take a long time on mainnet (18M+ blocks).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val custom = if (selectedMode == SyncMode.CUSTOM) {
                        customBlockHeight.toLongOrNull()
                    } else null
                    onSelectMode(selectedMode, custom)
                },
                enabled = selectedMode != SyncMode.CUSTOM || customBlockHeight.isNotBlank()
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SyncOptionItem(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) ColorGreen.copy(alpha = 0.08f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) ColorGreen else ColorSecondary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) ColorGreen else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorSecondary
                )
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun syncModeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.NEW_WALLET -> "New Wallet"
    SyncMode.RECENT -> "Recent"
    SyncMode.FULL_HISTORY -> "Full History"
    SyncMode.CUSTOM -> "Custom"
}
