package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit

private val DaoGreen = Color(0xFF1ED882)
private val StatusAmber = Color(0xFFF59E0B)
private val StatusGray = Color(0xFFA0A0A0)
private val MutedGreen = Color(0xFF4ADE80)

@Composable
fun DaoDepositCard(
    deposit: DaoDeposit,
    onWithdraw: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Amount + compensation row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${formatCkb(deposit.capacity)} CKB",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (deposit.compensation > 0) {
                        Text(
                            text = "+${formatCkb(deposit.compensation)} CKB",
                            style = MaterialTheme.typography.bodySmall,
                            color = DaoGreen
                        )
                    }
                }

                StatusBadge(deposit.status, deposit.lockRemainingHours)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            CompensationProgressBar(
                progress = deposit.compensationCycleProgress,
                phase = deposit.cyclePhase
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Countdown + action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val countdownText = when (deposit.status) {
                    DaoCellStatus.LOCKED -> {
                        val hours = deposit.lockRemainingHours ?: 0
                        val days = hours / 24
                        val remainingHours = hours % 24
                        "Unlockable in ${days}d ${remainingHours}h"
                    }
                    DaoCellStatus.UNLOCKABLE -> "Ready to unlock!"
                    DaoCellStatus.DEPOSITING, DaoCellStatus.WITHDRAWING, DaoCellStatus.UNLOCKING ->
                        "Confirming..."
                    else -> ""
                }

                Text(
                    text = countdownText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (deposit.status) {
                    DaoCellStatus.DEPOSITED -> {
                        OutlinedButton(
                            onClick = onWithdraw,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Withdraw")
                        }
                    }
                    DaoCellStatus.UNLOCKABLE -> {
                        Button(
                            onClick = onUnlock,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Unlock")
                        }
                    }
                    DaoCellStatus.DEPOSITING, DaoCellStatus.WITHDRAWING, DaoCellStatus.UNLOCKING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    else -> { /* no action button */ }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DaoCellStatus, lockRemainingHours: Int?) {
    val (color, label) = when (status) {
        DaoCellStatus.DEPOSITING -> StatusAmber to "Depositing..."
        DaoCellStatus.DEPOSITED -> DaoGreen to "Active"
        DaoCellStatus.WITHDRAWING -> StatusAmber to "Withdrawing..."
        DaoCellStatus.LOCKED -> {
            val hours = lockRemainingHours ?: 0
            val days = hours / 24
            val h = hours % 24
            StatusGray to "Locked \u2014 ${days}d ${h}h"
        }
        DaoCellStatus.UNLOCKABLE -> DaoGreen to "Ready to Unlock"
        DaoCellStatus.UNLOCKING -> StatusAmber to "Unlocking..."
        DaoCellStatus.COMPLETED -> MutedGreen to "Completed"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun formatCkb(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%.2f", ckb)
}
