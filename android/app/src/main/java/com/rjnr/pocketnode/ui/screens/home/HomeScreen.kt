package com.rjnr.pocketnode.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.gateway.models.displayName
import com.rjnr.pocketnode.ui.components.SyncOptionsDialog
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme
import com.rjnr.pocketnode.ui.theme.ErrorRed
import com.rjnr.pocketnode.ui.theme.SuccessGreen
import com.rjnr.pocketnode.ui.theme.TestnetOrange
import com.rjnr.pocketnode.ui.theme.TestnetOrangeDark
import com.rjnr.pocketnode.util.formatBlockTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSend: () -> Unit = {},
    onNavigateToReceive: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTransaction by remember { mutableStateOf<TransactionRecord?>(null) }
    val scope = rememberCoroutineScope()

    // Sync options dialog (settings path)
    if (uiState.showSyncOptionsDialog) {
        SyncOptionsDialog(
            currentMode = uiState.currentSyncMode,
            onDismiss = { viewModel.hideSyncOptions() },
            onSelectMode = { mode, customBlock ->
                viewModel.hideSyncOptions()
                viewModel.changeSyncMode(mode, customBlock)
            }
        )
    }

    // Post-import sync mode dialog (mainnet only)
    if (uiState.showPostImportSyncDialog) {
        SyncOptionsDialog(
            currentMode = SyncMode.RECENT,
            title = "Choose Sync Start Point",
            description = "Select how far back to sync your imported wallet's history. If your wallet is older than 30 days, choose Custom.",
            availableModes = listOf(SyncMode.RECENT, SyncMode.CUSTOM),
            onDismiss = { viewModel.hidePostImportSyncDialog() },
            onSelectMode = { mode, customBlock ->
                viewModel.hidePostImportSyncDialog()
                if (mode != SyncMode.RECENT) {
                    viewModel.changeSyncMode(mode, customBlock)
                }
            }
        )
    }

    // Network switch confirmation dialog
    val pendingSwitch = uiState.pendingNetworkSwitch
    if (uiState.showNetworkSwitchDialog && pendingSwitch != null) {
        val targetName = pendingSwitch.displayName
        AlertDialog(
            onDismissRequest = { viewModel.cancelNetworkSwitch() },
            title = { Text("Switch to $targetName?") },
            text = {
                Text(
                    "The app will close and reopen on $targetName. " +
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

    // Backup dialog
    if (uiState.showBackupDialog && uiState.privateKeyHex != null) {
        BackupWalletDialog(
            privateKeyHex = uiState.privateKeyHex!!,
            onDismiss = { viewModel.hideBackup() },
            onCopy = {
                clipboardManager.setText(AnnotatedString(uiState.privateKeyHex!!))
                scope.launch {
                    snackbarHostState.showSnackbar("Private key copied to clipboard")
                }
            }
        )
    }

    // Import dialog
    if (uiState.showImportDialog) {
        ImportWalletDialog(
            onDismiss = { viewModel.hideImport() },
            onImport = { viewModel.importWallet(it) }
        )
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            Log.e("HomeScreen", "Error: $error")
            viewModel.clearError()
        }
    }

    // Transaction detail bottom sheet
    if (selectedTransaction != null) {
        TransactionDetailSheet(
            transaction = selectedTransaction!!,
            network = uiState.currentNetwork,
            onDismiss = { selectedTransaction = null },
            onCopyTxHash = { txHash ->
                clipboardManager.setText(AnnotatedString(txHash))
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Transaction hash copied",
                        duration = SnackbarDuration.Short
                    )
                }
            },
            onOpenExplorer = { url ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: android.content.ActivityNotFoundException) {
                    // No browser available
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Pocket Node", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (uiState.isSyncing) {
                        SyncingChip(syncedToBlock = uiState.syncedToBlock)
                    } else {
                        SyncedChip()
                    }
                    Spacer(Modifier.width(4.dp))
                    // Gear icon — navigates to Settings tab via bottom nav
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Lucide.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isSwitchingNetwork) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Switching network...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Loading wallet...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            HomeScreenUI(
                uiState = uiState,
                refresh = { viewModel.refresh() },
                padding = padding,
                onNavigateToBackup = onNavigateToBackup,
                onNavigateToSend = onNavigateToSend,
                onNavigateToReceive = onNavigateToReceive,
                dismissBackupReminder = { viewModel.dismissBackupReminder() },
                clipboardManager = clipboardManager,
                snackbarHostState = snackbarHostState,
                scope = scope,
                selectedTransaction = { selectedTransaction = it }
            )
        }
    }
}

@Composable
fun HomeScreenUI(
    uiState: HomeUiState,
    refresh: () -> Unit,
    padding: PaddingValues,
    onNavigateToBackup: () -> Unit,
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    dismissBackupReminder: () -> Unit,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    selectedTransaction: (tx: TransactionRecord) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { refresh() },
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    )
    {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mnemonic Backup Reminder
            if (uiState.showBackupReminder) {
                item {
                    BackupReminderBanner(
                        onDismiss = { dismissBackupReminder() },
                        onBackup = onNavigateToBackup
                    )
                }
            }

            item {
                NetworkBadge(network = uiState.currentNetwork)
                Spacer(Modifier.width(4.dp))
            }

            // Balance Hero Card
            item {
                WalletBalanceCard(
                    balanceCkb = uiState.balanceCkb,
                    fiatBalance = uiState.fiatBalance,
                    address = uiState.address,
                    peerCount = uiState.peerCount,
                    onCopyAddress = {
                        clipboardManager.setText(AnnotatedString(uiState.address))
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Address copied to clipboard",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
            }

            // Quick Actions Row
            item {
                ActionRow(
                    onSend = onNavigateToSend,
                    onReceive = onNavigateToReceive
                )
            }

            // Sync Progress Bar — only when actively syncing
            if (uiState.isSyncing) {
                item {
                    SyncProgressBar(
                        syncProgress = uiState.syncProgress,
                        syncedToBlock = uiState.syncedToBlock,
                        tipBlockNumber = uiState.tipBlockNumber
                    )
                }
            }

            // Transactions Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Transactions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = {}) {
                        Text(
                            text = "See All",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Transaction List (last 5)
            if (uiState.transactions.isEmpty()) {
                item {
                    EmptyTransactionState()
                }
            } else {
                items(
                    items = uiState.transactions.take(5),
                    key = { it.txHash }
                ) { tx ->
                    TransactionItems(
                        transaction = tx,
                        onClick = { selectedTransaction(tx) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupWalletDialog(
    privateKeyHex: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup Wallet", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This is your private key. Anyone with this key can access your funds. Store it securely!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = privateKeyHex,
                        modifier = Modifier.padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onCopy) {
                Text("Copy Key")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ImportWalletDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var privateKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Wallet", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Warning: Importing a new wallet will REPLACE your current one. Make sure you have backed up your current key!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it.trim() },
                    label = { Text("Private Key (Hex)") },
                    placeholder = { Text("0x...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(privateKey) },
                enabled = privateKey.length >= 64,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Import & Replace")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


@Composable
private fun BackupReminderBanner(
    onDismiss: () -> Unit,
    onBackup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Lucide.TriangleAlert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Back Up Your Wallet",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your recovery phrase hasn't been backed up yet. Back it up now to protect your funds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onBackup,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Backup Now")
            }
        }
    }
}

@Composable
private fun SyncProgressBar(
    syncProgress: Double,
    syncedToBlock: String?,
    tipBlockNumber: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { syncProgress.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        val blockLabel = if (tipBlockNumber.isNotEmpty()) {
            "Block ${syncedToBlock ?: "—"} / ~$tipBlockNumber"
        } else {
            "Block ${syncedToBlock ?: "—"}"
        }
        Text(
            text = blockLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SyncingChip(syncedToBlock: String?) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Block ${syncedToBlock ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SyncedChip() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(
            text = "✓ Synced",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EmptyTransactionState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No transactions yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your transaction history will appear here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(
    transaction: TransactionRecord,
    network: NetworkType,
    onDismiss: () -> Unit,
    onCopyTxHash: (String) -> Unit,
    onOpenExplorer: (String) -> Unit
) {
    val isIncoming = transaction.isIncoming()
    val isOutgoing = transaction.isOutgoing()
    val explorerUrl = buildExplorerUrl(transaction.txHash, network)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transaction Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // Status badge
                Surface(
                    color = if (transaction.isConfirmed()) {
                        SuccessGreen.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (transaction.isConfirmed()) "Confirmed" else "Pending",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (transaction.isConfirmed()) {
                            SuccessGreen
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Amount card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            isIncoming -> "Received"
                            isOutgoing -> "Sent"
                            else -> "Amount"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = transaction.formattedAmount(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isIncoming -> SuccessGreen
                            isOutgoing -> ErrorRed
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // TX Hash row with copy + explorer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TX Hash",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = transaction.shortTxHash(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = { onCopyTxHash(transaction.txHash) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Lucide.Copy,
                        contentDescription = "Copy TX hash",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onOpenExplorer(explorerUrl) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Lucide.ExternalLink,
                        contentDescription = "View on explorer",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Block Number",
                value = transaction.blockNumber.removePrefix("0x").toLongOrNull(16)?.toString()
                    ?: transaction.blockNumber
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Time",
                value = formatBlockTimestamp(transaction.blockTimestampHex)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Confirmations",
                value = "${transaction.confirmations}"
            )

            val displayBlockHash = transaction.blockHash
                .takeIf { it.isNotBlank() && it != "0x0" }
                ?.let { "${it.take(10)}...${it.takeLast(8)}" }
            if (displayBlockHash != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                DetailRow(
                    label = "Block Hash",
                    value = displayBlockHash,
                    isMonospace = true
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    isMonospace: Boolean = false,
    showCopy: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )

        Row(
            modifier = Modifier.weight(0.65f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                textAlign = TextAlign.End,
                maxLines = if (isMonospace) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (showCopy && onCopy != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Lucide.Copy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkBadge(network: NetworkType) {
    val isTestnet = network == NetworkType.TESTNET
    val backgroundColor = if (isTestnet) TestnetOrange else MaterialTheme.colorScheme.primary
    val textColor = if (isTestnet) Color.White else MaterialTheme.colorScheme.onPrimary
    val dotColor =
        if (isTestnet) TestnetOrangeDark else SuccessGreen

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        modifier = Modifier.padding(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = "CKB ${network.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenUIPreview() {
    CkbWalletTheme {
        HomeScreenUI(
            uiState = HomeUiState(
                balanceCkb = 1234.56,
                fiatBalance = "≈ $12.34 USD",
                address = "ckt1qzda0cr08m85hc8jve3z9rcr97760lg6xl6llx",
                peerCount = 8,
                transactions = listOf(
                    TransactionRecord(
                        txHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                        blockNumber = "0x123456",
                        blockHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
                        timestamp = System.currentTimeMillis() - 3600000,
                        balanceChange = "0x3b9aca00", // 10 CKB in shannons (0x3b9aca00 = 1000000000)
                        direction = "in",
                        fee = "0x2710",
                        confirmations = 12,
                        blockTimestampHex = "0x18c8d0a7a00"
                    ),
                    TransactionRecord(
                        txHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
                        blockNumber = "0x123457",
                        blockHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                        timestamp = System.currentTimeMillis() - 7200000,
                        balanceChange = "0x1dcd6500", // 5 CKB in shannons
                        direction = "out",
                        fee = "0x2710",
                        confirmations = 5,
                        blockTimestampHex = "0x18c8d0a7a00"
                    )
                ),
                showBackupReminder = true
            ),
            refresh = {},
            padding = PaddingValues(0.dp),
            onNavigateToBackup = {},
            onNavigateToSend = {},
            onNavigateToReceive = {},
            dismissBackupReminder = {},
            clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current,
            snackbarHostState = remember { SnackbarHostState() },
            scope = rememberCoroutineScope(),
            selectedTransaction = {}
        )
    }
}

private fun buildExplorerUrl(txHash: String, network: NetworkType): String {
    val base = when (network) {
        NetworkType.MAINNET -> "https://explorer.nervos.org/transaction"
        NetworkType.TESTNET -> "https://testnet.explorer.nervos.org/transaction"
    }
    return "$base/$txHash"
}

@Preview(showBackground = true)
@Composable
private fun TransactionItemPreview() {
    CkbWalletTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sampleTxIn = TransactionRecord(
                txHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                blockNumber = "0x123456",
                blockHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
                timestamp = System.currentTimeMillis() - 3600000,
                balanceChange = "0x3b9aca00", // 10 CKB in shannons (0x3b9aca00 = 1000000000)
                direction = "in",
                fee = "0x2710",
                confirmations = 12,
                blockTimestampHex = "0x18c8d0a7a00"
            )
            val sampleTxOut = TransactionRecord(
                txHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
                blockNumber = "0x123457",
                blockHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                timestamp = System.currentTimeMillis() - 7200000,
                balanceChange = "0x1dcd6500", // 5 CKB in shannons
                direction = "out",
                fee = "0x2710",
                confirmations = 0,
                blockTimestampHex = "0x18c8d0a7a00"
            )

            TransactionItems(
                transaction = sampleTxIn,
                onClick = {}
            )
            TransactionItems(
                transaction = sampleTxOut,
                onClick = {}
            )
        }
    }
}
