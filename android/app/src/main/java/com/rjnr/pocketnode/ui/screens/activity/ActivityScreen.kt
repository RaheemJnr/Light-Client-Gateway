package com.rjnr.pocketnode.ui.screens.activity

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.util.formatBlockTimestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Color palette consistent with HomeScreen
private val Green = Color(0xFF1ED882)
private val SecondaryText = Color(0xFFA0A0A0)
private val ErrorRed = Color(0xFFFF4444)
private val AmberPending = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTransaction by remember { mutableStateOf<TransactionRecord?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val filtered = remember(uiState.transactions, uiState.filter) {
        viewModel.filteredTransactions(uiState)
    }
    val grouped = remember(filtered) {
        groupByDate(filtered)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Activity",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.loadTransactions() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Filter tabs
            FilterTabRow(
                currentFilter = uiState.filter,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Green)
                    }
                }

                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error,
                        onRetry = { viewModel.loadTransactions() }
                    )
                }

                grouped.isEmpty() -> {
                    EmptyState(filter = uiState.filter)
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        grouped.forEach { (label, txs) ->
                            item(key = "header_$label") {
                                DateGroupHeader(label = label)
                            }
                            items(items = txs, key = { it.txHash }) { tx ->
                                ActivityTransactionItem(
                                    transaction = tx,
                                    onClick = { selectedTransaction = tx }
                                )
                            }
                        }
                    }
                }
            }

            selectedTransaction?.let { tx ->
                TransactionDetailSheet(
                    transaction = tx,
                    network = uiState.currentNetwork,
                    onDismiss = { selectedTransaction = null },
                    onCopyTxHash = { hash ->
                        clipboardManager.setText(AnnotatedString(hash))
                    },
                    onOpenExplorer = { url ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: android.content.ActivityNotFoundException) {
                            // No browser available — silently ignore
                        }
                    }
                )
            }
        }
    }
}

// ─── Filter Tab Row ──────────────────────────────────────────────────────────

@Composable
private fun FilterTabRow(
    currentFilter: ActivityViewModel.Filter,
    onFilterSelected: (ActivityViewModel.Filter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActivityViewModel.Filter.entries.forEach { filter ->
            val label = when (filter) {
                ActivityViewModel.Filter.ALL -> "All"
                ActivityViewModel.Filter.RECEIVED -> "Received"
                ActivityViewModel.Filter.SENT -> "Sent"
            }
            FilterTab(
                label = label,
                selected = currentFilter == filter,
                onClick = { onFilterSelected(filter) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Green else SecondaryText
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) Green else Color.Transparent)
        )
    }
}

// ─── Date Group Header ────────────────────────────────────────────────────────

@Composable
private fun DateGroupHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = SecondaryText,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

// ─── Transaction Row ──────────────────────────────────────────────────────────

@Composable
private fun ActivityTransactionItem(
    transaction: TransactionRecord,
    onClick: () -> Unit
) {
    val isIncoming = transaction.isIncoming()
    val isSelf = transaction.isSelfTransfer()
    val isPending = transaction.isPending()

    val (icon, iconBg, amountColor) = when {
        isIncoming -> Triple(Icons.Default.ArrowDownward, Green.copy(alpha = 0.15f), Green)
        isSelf -> Triple(Icons.Default.SwapHoriz, SecondaryText.copy(alpha = 0.15f), SecondaryText)
        else -> Triple(Icons.Default.ArrowUpward, ErrorRed.copy(alpha = 0.15f), ErrorRed)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direction icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = amountColor
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Middle: type label + tx hash + status badge
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        isIncoming -> "Received"
                        isSelf -> "Self Transfer"
                        else -> "Sent"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (isPending) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = AmberPending.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Pending",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberPending,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = transaction.shortTxHash(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Right: amount + time
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = transaction.formattedAmount(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = amountColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatBlockTimestamp(transaction.blockTimestampHex),
                style = MaterialTheme.typography.labelSmall,
                color = SecondaryText
            )
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

// ─── Empty / Error States ─────────────────────────────────────────────────────

@Composable
private fun EmptyState(filter: ActivityViewModel.Filter) {
    val message = when (filter) {
        ActivityViewModel.Filter.ALL -> "No transactions yet"
        ActivityViewModel.Filter.RECEIVED -> "No received transactions"
        ActivityViewModel.Filter.SENT -> "No sent transactions"
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Receipt,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = SecondaryText.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = SecondaryText,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorState(message: String?, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = message ?: "Failed to load transactions",
                style = MaterialTheme.typography.bodyMedium,
                color = ErrorRed,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Green,
                    contentColor = Color.Black
                )
            ) {
                Text("Retry", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─── Transaction Detail Bottom Sheet ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(
    transaction: TransactionRecord,
    network: NetworkType,
    onDismiss: () -> Unit,
    onCopyTxHash: (String) -> Unit,
    onOpenExplorer: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isIncoming = transaction.isIncoming()
    val isOutgoing = transaction.isOutgoing()
    val amountColor = when {
        isIncoming -> Green
        isOutgoing -> ErrorRed
        else -> SecondaryText
    }

    val explorerUrl = buildExplorerUrl(transaction.txHash, network)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
        ) {
            // Header row: title + status badge
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
                StatusBadge(confirmed = transaction.isConfirmed())
            }

            Spacer(modifier = Modifier.height(20.dp))

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
                        color = SecondaryText
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = transaction.formattedAmount(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = amountColor
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
                        color = SecondaryText
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
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy TX hash",
                        modifier = Modifier.size(18.dp),
                        tint = SecondaryText
                    )
                }
                IconButton(
                    onClick = { onOpenExplorer(explorerUrl) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "View on explorer",
                        modifier = Modifier.size(18.dp),
                        tint = Green
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Block number
            DetailRow(
                label = "Block Number",
                value = formatBlockNumber(transaction.blockNumber)
            )

            // Block hash (hidden if "0x0")
            if (transaction.blockHash.isNotBlank() && transaction.blockHash != "0x0") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                DetailRow(
                    label = "Block Hash",
                    value = truncateHash(transaction.blockHash)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Time
            DetailRow(
                label = "Time",
                value = formatBlockTimestamp(transaction.blockTimestampHex)
            )

            // Fee (if non-zero)
            val feeShannons = transaction.fee.removePrefix("0x").toLongOrNull(16) ?: 0L
            if (feeShannons > 0L) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                val feeCkb = feeShannons / 100_000_000.0
                val feeFormatted = "%.8f".format(feeCkb).trimEnd('0').trimEnd('.')
                DetailRow(
                    label = "Fee",
                    value = "$feeFormatted CKB"
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(confirmed: Boolean) {
    Surface(
        color = if (confirmed) Green.copy(alpha = 0.15f) else AmberPending.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = if (confirmed) "Confirmed" else "Pending",
            style = MaterialTheme.typography.labelMedium,
            color = if (confirmed) Green else AmberPending,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText,
            modifier = Modifier.weight(0.4f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Groups a list of transactions by a human-readable date label.
 * Returns an ordered list of (label, transactions) pairs so the
 * caller can render headers without re-sorting.
 *
 * Uses `blockTimestampHex` when available; falls back to TODAY for
 * zero/null timestamps (pending transactions that haven't been mined yet).
 */
private fun groupByDate(transactions: List<TransactionRecord>): List<Pair<String, List<TransactionRecord>>> {
    val today = LocalDate.now(ZoneId.systemDefault())
    val yesterday = today.minusDays(1)

    return transactions
        .groupBy { tx ->
            val hex = tx.blockTimestampHex
            if (hex.isNullOrBlank() || hex == "0x0" || hex == "0") {
                "Pending"
            } else {
                val millis = runCatching {
                    if (hex.startsWith("0x", ignoreCase = true))
                        hex.removePrefix("0x").removePrefix("0X").toLong(16)
                    else hex.toLong()
                }.getOrElse { 0L }

                if (millis <= 0L) {
                    "Pending"
                } else {
                    val date = Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    when (date) {
                        today -> "Today"
                        yesterday -> "Yesterday"
                        else -> "Earlier"
                    }
                }
            }
        }
        .entries
        // Preserve a sensible display order: Pending (unconfirmed) first,
        // then Today, Yesterday, Earlier.
        .sortedBy { (label, _) ->
            when (label) {
                "Pending" -> 0
                "Today" -> 1
                "Yesterday" -> 2
                else -> 3
            }
        }
        .map { it.key to it.value }
}

private fun buildExplorerUrl(txHash: String, network: NetworkType): String {
    val base = when (network) {
        NetworkType.MAINNET -> "https://explorer.nervos.org/transaction"
        NetworkType.TESTNET -> "https://testnet.explorer.nervos.org/transaction"
    }
    return "$base/$txHash"
}

/** Formats a hex block number (e.g. "0x1170ea8") into a comma-separated decimal string. */
private fun formatBlockNumber(raw: String): String {
    val decimal = raw.removePrefix("0x").toLongOrNull(16) ?: return raw
    return "%,d".format(decimal)
}

/** Truncates a long hash to "0xabc...xyz" form for compact display. */
private fun truncateHash(hash: String): String {
    return if (hash.length > 20) "${hash.take(10)}...${hash.takeLast(6)}" else hash
}
