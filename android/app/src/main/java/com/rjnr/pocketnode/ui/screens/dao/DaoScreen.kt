package com.rjnr.pocketnode.ui.screens.dao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.DaoOverview
import com.rjnr.pocketnode.data.gateway.models.DaoTab
import com.rjnr.pocketnode.ui.screens.dao.components.DaoDepositCard
import com.rjnr.pocketnode.ui.screens.dao.components.DepositBottomSheet

private val DaoGreen = Color(0xFF1ED882)

@Composable
fun DaoScreen(viewModel: DaoViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val availableBalance by viewModel.availableBalance.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDepositSheet by remember { mutableStateOf(false) }
    var withdrawTarget by remember { mutableStateOf<DaoDeposit?>(null) }
    var unlockTarget by remember { mutableStateOf<DaoDeposit?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            DaoOverviewCard(
                overview = uiState.overview,
                onDepositClick = { showDepositSheet = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            DaoTabRow(
                selectedTab = uiState.selectedTab,
                activeCount = uiState.overview.activeCount,
                completedCount = uiState.overview.completedCount,
                onTabSelected = viewModel::selectTab
            )

            Spacer(modifier = Modifier.height(8.dp))

            val deposits = when (uiState.selectedTab) {
                DaoTab.ACTIVE -> uiState.activeDeposits
                DaoTab.COMPLETED -> uiState.completedDeposits
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (deposits.isEmpty() && uiState.selectedTab == DaoTab.ACTIVE) {
                DaoEmptyState(onDepositClick = { showDepositSheet = true })
            } else if (deposits.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No completed deposits yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(deposits, key = { "${it.outPoint.txHash}:${it.outPoint.index}" }) { deposit ->
                        DaoDepositCard(
                            deposit = deposit,
                            onWithdraw = { withdrawTarget = deposit },
                            onUnlock = { unlockTarget = deposit }
                        )
                    }
                }
            }
        }
    }

    // Snackbar for errors
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Deposit bottom sheet
    if (showDepositSheet) {
        DepositBottomSheet(
            availableBalance = availableBalance,
            currentApc = uiState.overview.currentApc,
            onDeposit = { amount ->
                showDepositSheet = false
                viewModel.deposit(amount)
            },
            onDismiss = { showDepositSheet = false }
        )
    }

    // Withdraw confirmation
    withdrawTarget?.let { deposit ->
        AlertDialog(
            onDismissRequest = { withdrawTarget = null },
            title = { Text("Withdraw from DAO") },
            text = {
                Column {
                    Text("Deposit: ${formatCkb(deposit.capacity)} CKB")
                    Text("Earned: +${formatCkb(deposit.compensation)} CKB")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your funds will remain locked until the current 180-epoch cycle ends.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.withdraw(deposit)
                    withdrawTarget = null
                }) {
                    Text("Withdraw")
                }
            },
            dismissButton = {
                TextButton(onClick = { withdrawTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Unlock confirmation
    unlockTarget?.let { deposit ->
        val totalReceived = deposit.capacity + deposit.compensation
        AlertDialog(
            onDismissRequest = { unlockTarget = null },
            title = { Text("Unlock DAO Deposit") },
            text = {
                Column {
                    Text("Deposit: ${formatCkb(deposit.capacity)} CKB")
                    Text("Compensation: +${formatCkb(deposit.compensation)} CKB")
                    Text("Total received: ${formatCkb(totalReceived)} CKB")
                    Text("Fee: ~0.001 CKB")
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.unlock(deposit)
                    unlockTarget = null
                }) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { unlockTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DaoOverviewCard(
    overview: DaoOverview,
    onDepositClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Nervos DAO",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = formatCkb(overview.totalLocked) + " CKB",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "+${formatCkb(overview.totalCompensation)} CKB",
                style = MaterialTheme.typography.bodyMedium,
                color = DaoGreen
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "APC ~${String.format("%.2f", overview.currentApc)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDepositClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Deposit")
            }
        }
    }
}

@Composable
private fun DaoTabRow(
    selectedTab: DaoTab,
    activeCount: Int,
    completedCount: Int,
    onTabSelected: (DaoTab) -> Unit
) {
    TabRow(
        selectedTabIndex = if (selectedTab == DaoTab.ACTIVE) 0 else 1
    ) {
        Tab(
            selected = selectedTab == DaoTab.ACTIVE,
            onClick = { onTabSelected(DaoTab.ACTIVE) },
            text = { Text("Active ($activeCount)") }
        )
        Tab(
            selected = selectedTab == DaoTab.COMPLETED,
            onClick = { onTabSelected(DaoTab.COMPLETED) },
            text = { Text("Completed ($completedCount)") }
        )
    }
}

@Composable
private fun DaoEmptyState(onDepositClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Earn rewards with Nervos DAO",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Deposit CKB to earn ~2.5% annual compensation. 180-epoch lock cycles.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onDepositClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Make First Deposit")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Not seeing expected deposits? Try resyncing from an earlier block in Settings.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

internal fun formatCkb(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%.2f", ckb)
}
