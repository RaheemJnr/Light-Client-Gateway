package com.example.ckbwallet.ui.screens.home

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.ckbwallet.data.gateway.models.SyncMode
import androidx.compose.runtime.rememberCoroutineScope
import com.example.ckbwallet.data.gateway.models.TransactionRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSend: () -> Unit = {},
    onNavigateToReceive: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTransaction by remember { mutableStateOf<TransactionRecord?>(null) }
    var showSyncMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Sync options dialog
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
            onDismiss = { selectedTransaction = null },
            onCopyTxHash = { txHash ->
                clipboardManager.setText(AnnotatedString(txHash))
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Transaction hash copied",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CKB Wallet") },
                actions = {
                    // Sync options menu
                    Box {
                        IconButton(onClick = { showSyncMenu = true }) {
                            Icon(Icons.Outlined.History, "Sync Options")
                        }
                        DropdownMenu(
                            expanded = showSyncMenu,
                            onDismissRequest = { showSyncMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sync Options...") },
                                onClick = {
                                    showSyncMenu = false
                                    viewModel.showSyncOptions()
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Sync, contentDescription = null)
                                }
                            )
                        }
                    }

                    // Refresh button
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !uiState.isRefreshing
                    ) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Sync Status Banner (if syncing)
                if (uiState.isSyncing) {
                    item {
                        SyncStatusBanner(
                            syncProgress = uiState.syncProgress,
                            showDataWarning = uiState.syncProgress < 0.999
                        )
                    }
                }

                // Balance Card
                item {
                    BalanceCard(
                        balanceCkb = uiState.balanceCkb,
                        address = uiState.walletInfo?.testnetAddress ?: "",
                        isRefreshing = uiState.isRefreshing,
                        onCopyAddress = {
                            uiState.walletInfo?.testnetAddress?.let {
                                clipboardManager.setText(AnnotatedString(it))
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = "Address copied to clipboard",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        }
                    )
                }

                // Action Buttons
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActionButton(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.ArrowUpward,
                            label = "Send",
                            onClick = onNavigateToSend,
                            isPrimary = true
                        )
                        ActionButton(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.ArrowDownward,
                            label = "Receive",
                            onClick = onNavigateToReceive,
                            isPrimary = false
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
                        if (uiState.transactions.isNotEmpty()) {
                            Text(
                                text = "${uiState.transactions.size} transactions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Transaction List
                if (uiState.transactions.isEmpty()) {
                    item {
                        EmptyTransactionState()
                    }
                } else {
                    items(
                        items = uiState.transactions,
                        key = { it.txHash }
                    ) { tx ->
                        TransactionItem(
                            transaction = tx,
                            onClick = { selectedTransaction = tx }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusBanner(
    syncProgress: Double,
    showDataWarning: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (showDataWarning) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showDataWarning) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (showDataWarning) {
                        "Syncing in progress..."
                    } else {
                        "Syncing transaction history..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (showDataWarning) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { syncProgress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (showDataWarning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                trackColor = if (showDataWarning) {
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f)
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (showDataWarning) {
                    "${(syncProgress * 100).toInt()}% complete • Balance and transactions may be inaccurate until sync completes"
                } else {
                    "${(syncProgress * 100).toInt()}% complete • Almost done..."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (showDataWarning) {
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                }
            )
        }
    }
}

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
        title = {
            Text("Sync Options", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Choose how much transaction history to sync:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // New Wallet option
                SyncOptionItem(
                    title = "New Wallet",
                    description = "No history - fastest startup",
                    icon = Icons.Default.FiberNew,
                    isSelected = selectedMode == SyncMode.NEW_WALLET,
                    onClick = {
                        selectedMode = SyncMode.NEW_WALLET
                        showCustomInput = false
                    }
                )

                // Recent option
                SyncOptionItem(
                    title = "Recent (~30 days)",
                    description = "Last ~200k blocks - recommended",
                    icon = Icons.Default.Schedule,
                    isSelected = selectedMode == SyncMode.RECENT,
                    onClick = {
                        selectedMode = SyncMode.RECENT
                        showCustomInput = false
                    }
                )

                // Full History option
                SyncOptionItem(
                    title = "Full History",
                    description = "From genesis - complete but slow",
                    icon = Icons.Default.History,
                    isSelected = selectedMode == SyncMode.FULL_HISTORY,
                    onClick = {
                        selectedMode = SyncMode.FULL_HISTORY
                        showCustomInput = false
                    }
                )

                // Custom option
                SyncOptionItem(
                    title = "Custom Block Height",
                    description = "Start from a specific block",
                    icon = Icons.Default.Tune,
                    isSelected = selectedMode == SyncMode.CUSTOM,
                    onClick = {
                        selectedMode = SyncMode.CUSTOM
                        showCustomInput = true
                    }
                )

                // Custom block height input
                if (showCustomInput) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Warning about missing history
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Transactions before this block won't appear in history, but your balance will still be correct.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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

                Spacer(modifier = Modifier.height(8.dp))

                // Warning for full history
                if (selectedMode == SyncMode.FULL_HISTORY) {
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Full sync can take several hours depending on network conditions.",
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
                    val customBlock = if (selectedMode == SyncMode.CUSTOM) {
                        customBlockHeight.toLongOrNull()
                    } else null
                    onSelectMode(selectedMode, customBlock)
                },
                enabled = selectedMode != SyncMode.CUSTOM || customBlockHeight.isNotBlank()
            ) {
                Text("Apply")
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
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant
            )
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun BalanceCard(
    balanceCkb: Double,
    address: String,
    isRefreshing: Boolean,
    onCopyAddress: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Total Balance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = String.format("%,.2f", balanceCkb),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CKB",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (isRefreshing) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Address section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onCopyAddress)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy address",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isPrimary: Boolean
) {
    if (isPrimary) {
        Button(
            onClick = onClick,
            modifier = modifier.height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
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
                imageVector = Icons.Default.Receipt,
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

@Composable
private fun TransactionItem(
    transaction: TransactionRecord,
    onClick: () -> Unit
) {
    val isIncoming = transaction.isIncoming()
    val isOutgoing = transaction.isOutgoing()
    val isSelf = transaction.isSelfTransfer()
    val isPending = transaction.isPending()

    val backgroundColor by animateColorAsState(
        targetValue = if (isPending) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "bgColor"
    )

    val (icon, iconBgColor, amountColor) = when {
        isIncoming -> Triple(
            Icons.Default.ArrowDownward,
            Color(0xFF4CAF50).copy(alpha = 0.15f),
            Color(0xFF4CAF50)
        )
        isOutgoing -> Triple(
            Icons.Default.ArrowUpward,
            Color(0xFFF44336).copy(alpha = 0.15f),
            Color(0xFFF44336)
        )
        isSelf -> Triple(
            Icons.Default.SwapHoriz,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.onSurface
        )
        else -> Triple(
            Icons.Default.QuestionMark,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurface
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = amountColor
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Transaction details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            isIncoming -> "Received"
                            isOutgoing -> "Sent"
                            isSelf -> "Self Transfer"
                            else -> "Transaction"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    if (isPending) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Pending",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = transaction.getRelativeTimeString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (transaction.isConfirmed()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${transaction.compactConfirmations()} conf",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Amount and hash
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = transaction.formattedAmount(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = amountColor
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Show hash preview
                Text(
                    text = transaction.shortTxHash(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(
    transaction: TransactionRecord,
    onDismiss: () -> Unit,
    onCopyTxHash: (String) -> Unit
) {
    val isIncoming = transaction.isIncoming()
    val isOutgoing = transaction.isOutgoing()

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
                        Color(0xFF4CAF50).copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (transaction.isConfirmed()) "Confirmed" else "Pending",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (transaction.isConfirmed()) {
                            Color(0xFF4CAF50)
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
                            isIncoming -> Color(0xFF4CAF50)
                            isOutgoing -> Color(0xFFF44336)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Details list
            DetailRow(
                label = "Transaction Hash",
                value = transaction.txHash,
                isMonospace = true,
                showCopy = true,
                onCopy = { onCopyTxHash(transaction.txHash) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Block Number",
                value = transaction.blockNumber.removePrefix("0x").toLongOrNull(16)?.toString() ?: transaction.blockNumber
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Time",
                value = transaction.getRelativeTimeString()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Confirmations",
                value = "${transaction.confirmations}"
            )

            if (transaction.blockHash.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                DetailRow(
                    label = "Block Hash",
                    value = transaction.blockHash,
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
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
