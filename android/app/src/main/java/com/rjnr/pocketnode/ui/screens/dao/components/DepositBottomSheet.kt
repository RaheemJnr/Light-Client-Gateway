package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.gateway.DaoConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositBottomSheet(
    availableBalance: Long,
    currentApc: Double,
    onDeposit: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by remember { mutableStateOf("") }
    var reserveEnabled by remember { mutableStateOf(true) }
    var showRules by remember { mutableStateOf(false) }

    val amountCkb = amountText.toDoubleOrNull() ?: 0.0
    val amountShannons = (amountCkb * 100_000_000).toLong()
    val maxDepositable = if (reserveEnabled) {
        (availableBalance - DaoConstants.RESERVE_SHANNONS).coerceAtLeast(0L)
    } else {
        availableBalance
    }
    val isValid = amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS
        && amountShannons <= maxDepositable

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Deposit to Nervos DAO",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Available: ${formatCkb(maxDepositable)} CKB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (CKB)") },
                placeholder = { Text("Min 102 CKB") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = amountText.isNotEmpty() && !isValid,
                supportingText = {
                    if (amountText.isNotEmpty() && amountShannons < DaoConstants.MIN_DEPOSIT_SHANNONS) {
                        Text("Minimum deposit is 102 CKB")
                    } else if (amountText.isNotEmpty() && amountShannons > maxDepositable) {
                        Text("Exceeds available balance")
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = reserveEnabled,
                    onCheckedChange = { reserveEnabled = it }
                )
                Text(
                    text = "Reserve 62 CKB for future fees",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Reward estimates
            if (amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val reward30d = amountCkb * currentApc / 100 / 12
                        val reward360d = amountCkb * currentApc / 100
                        Text(
                            text = "Estimated rewards",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "30 days: ~${String.format("%.4f", reward30d)} CKB",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "360 days: ~${String.format("%.4f", reward360d)} CKB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Expandable rules
            TextButton(onClick = { showRules = !showRules }) {
                Text(if (showRules) "Hide Nervos DAO Rules" else "Nervos DAO Rules")
            }
            AnimatedVisibility(visible = showRules) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val rules = listOf(
                            "Minimum deposit: 102 CKB",
                            "Lock period: 180 epochs (~30 days)",
                            "You can withdraw anytime, but funds stay locked until the cycle ends",
                            "Unlock becomes available after the full lock cycle completes"
                        )
                        rules.forEach { rule ->
                            Text(
                                text = "\u2022 $rule",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onDeposit(amountShannons) },
                    enabled = isValid,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Deposit")
                }
            }
        }
    }
}

private fun formatCkb(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%.2f", ckb)
}
