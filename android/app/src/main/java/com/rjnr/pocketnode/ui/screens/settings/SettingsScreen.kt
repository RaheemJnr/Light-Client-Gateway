package com.rjnr.pocketnode.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Github
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Network
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Terminal
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.displayName
import com.rjnr.pocketnode.ui.components.SyncOptionsDialog
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme
import com.rjnr.pocketnode.ui.theme.PendingAmber

private const val GITHUB_URL = "https://github.com/RaheemJnr/pocket-node/"

private val ColorAmber = PendingAmber

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

    SettingsScreenUI(
        snackbarHostState,
        onNavigateToSecuritySettings,
        onNavigateToBackup,
        onNavigateToImport,
        uiState,
        onNavigateToNodeStatus,
        context,
        showSyncDialog = { viewModel.showSyncDialog() },
        requestNetworkSwitch = {
            viewModel.requestNetworkSwitch(it)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenUI(
    snackbarHostState: SnackbarHostState,
    onNavigateToSecuritySettings: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToImport: () -> Unit,
    uiState: SettingsViewModel.UiState,
    onNavigateToNodeStatus: () -> Unit,
    context: Context,
    showSyncDialog: () -> Unit,
    requestNetworkSwitch: (NetworkType) -> Unit

) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        )
        {
            // ── SECURITY ──────────────────────────────────────────────────
            item { SectionHeader("SECURITY") }

            item {
                SettingsLinkRow(
                    icon = Lucide.Shield,
                    title = "Pin & Biometrics",
                    onClick = onNavigateToSecuritySettings
                )
            }

            // ── WALLET ────────────────────────────────────────────────────
            item { SectionHeader("WALLET") }

            item {
                SettingsLinkRow(
                    icon = Lucide.ShieldCheck,
                    title = "Backup Wallet",
                    onClick = { onNavigateToBackup() }
                )

            }

            item {
                SettingsLinkRow(
                    icon = Lucide.Download,
                    title = "Import Wallet",
                    onClick = onNavigateToImport
                )
            }

            item {
                SettingsLinkRow(
                    icon = Lucide.RefreshCw,
                    title = "Sync Options",
                    badgeText = syncModeLabel(uiState.syncMode),
                    onClick = { showSyncDialog() }
                )
            }

            // ── NETWORK ───────────────────────────────────────────────────
            item { SectionHeader("NETWORK") }

            item {
                val isTestnet = uiState.currentNetwork == NetworkType.TESTNET
                SettingsValueRow(
                    icon = Lucide.Network,
                    title = "Current Network",
                    value = "CKB ${uiState.currentNetwork.displayName}",
                    valueColor = if (isTestnet) ColorAmber else MaterialTheme.colorScheme.primary,
                    onClick = {
                        val target = if (isTestnet) NetworkType.MAINNET else NetworkType.TESTNET
                        requestNetworkSwitch(target)
                    }
                )
            }

            item {
                SettingsLinkRow(
                    icon = Lucide.Terminal,
                    title = "Node Status & Logs",
                    onClick = onNavigateToNodeStatus
                )
            }

            // ── ABOUT ─────────────────────────────────────────────────────
            item { SectionHeader("ABOUT") }

            item {
                SettingsValueRow(
                    icon = Lucide.Info,
                    title = "Version",
                    value = BuildConfig.VERSION_NAME,
                    onClick = null
                )
            }

            item {
                SettingsLinkRow(
                    icon = Lucide.Github,
                    title = "Open Source",
                    badgeText = "Github",
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
fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
fun SettingsLinkRow(
    icon: ImageVector,
    title: String,
    badgeText: String? = null,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick?.invoke() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badgeText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        text = badgeText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)
}

@Composable
fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    valueColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = { onClick?.invoke() })
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)
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
        color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = if (enabled) enabledLabel else disabledLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ValuePill(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
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
        imageVector = Lucide.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun syncModeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.NEW_WALLET -> "New Wallet"
    SyncMode.RECENT -> "Recent"
    SyncMode.FULL_HISTORY -> "Full History"
    SyncMode.CUSTOM -> "Custom"
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenUIPreview() {
    CkbWalletTheme {
        SettingsScreenUI(
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateToSecuritySettings = {},
            onNavigateToBackup = {},
            onNavigateToImport = {},
            uiState = SettingsViewModel.UiState(
                isPinEnabled = true,
                syncMode = SyncMode.RECENT,
                currentNetwork = NetworkType.MAINNET
            ),
            onNavigateToNodeStatus = {},
            context = LocalContext.current,
            showSyncDialog = {},
            requestNetworkSwitch = {}
        )
    }
}
