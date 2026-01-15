package com.rjnr.pocketnode.ui.screens.send

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    onNavigateBack: () -> Unit,
    onNavigateToScanner: () -> Unit = {},
    scannedAddress: String? = null,
    viewModel: SendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Handle scanned address
    LaunchedEffect(scannedAddress) {
        scannedAddress?.let { address ->
            if (address.isNotBlank()) {
                viewModel.updateRecipient(address)
            }
        }
    }

    // Show transaction status dialog when transaction is in progress
    if (uiState.txHash != null) {
        TransactionStatusDialog(
            txHash = uiState.txHash!!,
            transactionState = uiState.transactionState,
            statusMessage = uiState.statusMessage,
            confirmations = uiState.confirmations,
            onDismiss = {
                viewModel.clearTxHash()
                if (uiState.transactionState == TransactionState.CONFIRMED) {
                    onNavigateBack()
                }
            }
        )
    }

    // Show error dialog with better UX
    if (uiState.error != null && uiState.transactionState != TransactionState.SENDING) {
        ErrorDialog(
            errorMessage = uiState.error ?: "An unknown error occurred",
            onDismiss = { viewModel.clearError() },
            onRetry = if (uiState.transactionState == TransactionState.FAILED) {
                { viewModel.clearError() }
            } else null
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send CKB") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Available balance
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Available Balance",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = String.format("%,.2f CKB", uiState.availableBalance / 100_000_000.0),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            // Recipient address with scan button
            OutlinedTextField(
                value = uiState.recipientAddress,
                onValueChange = { viewModel.updateRecipient(it) },
                label = { Text("Recipient Address") },
                placeholder = { Text("ckt1q...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                enabled = !uiState.isLoading,
                trailingIcon = {
                    IconButton(
                        onClick = onNavigateToScanner,
                        enabled = !uiState.isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR Code"
                        )
                    }
                }
            )

            // Amount
            OutlinedTextField(
                value = uiState.amountCkb,
                onValueChange = { viewModel.updateAmount(it) },
                label = { Text("Amount (CKB)") },
                placeholder = { Text("0.0") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !uiState.isLoading,
                supportingText = { Text("Minimum: 61 CKB") }
            )

            // Burn Warning
            if (uiState.burnWarning != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = uiState.burnWarning!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Send button
            Button(
                onClick = { viewModel.sendTransaction() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !uiState.isLoading &&
                          uiState.recipientAddress.isNotBlank() &&
                          uiState.amountCkb.isNotBlank()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(uiState.statusMessage.ifEmpty { "Sending..." })
                } else {
                    Text("Send CKB")
                }
            }
        }
    }
}

@Composable
fun ErrorDialog(
    errorMessage: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        title = {
            Text(
                text = "Transaction Failed",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show helpful tips for common errors
                if (errorMessage.contains("Insufficient", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(12.dp))
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
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Make sure you have enough CKB to cover the amount plus network fees.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                if (errorMessage.contains("Minimum", ignoreCase = true) || errorMessage.contains("61", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(12.dp))
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
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "CKB requires a minimum of 61 CKB per transaction output.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        },
        dismissButton = if (onRetry != null) {
            {
                OutlinedButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        } else null
    )
}

@Composable
fun TransactionStatusDialog(
    txHash: String,
    transactionState: TransactionState,
    statusMessage: String,
    confirmations: Int,
    onDismiss: () -> Unit
) {
    val isConfirmed = transactionState == TransactionState.CONFIRMED
    val isFailed = transactionState == TransactionState.FAILED

    AlertDialog(
        onDismissRequest = {
            // Only allow dismissing if confirmed or failed
            if (isConfirmed || isFailed) {
                onDismiss()
            }
        },
        icon = {
            when {
                isConfirmed -> {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            modifier = Modifier.size(40.dp),
                            tint = Color(0xFF4CAF50)
                        )
                    }
                }
                isFailed -> {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Failed",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    // Animated progress for pending states
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                }
            }
        },
        title = {
            Text(
                text = when (transactionState) {
                    TransactionState.CONFIRMED -> "Transaction Confirmed!"
                    TransactionState.FAILED -> "Transaction Failed"
                    TransactionState.SENDING -> "Sending..."
                    TransactionState.PENDING -> "Waiting for Confirmation"
                    TransactionState.PROPOSED -> "Processing..."
                    else -> "Transaction Submitted"
                },
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status message
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Progress steps (only show when not confirmed/failed)
                if (!isConfirmed && !isFailed) {
                    StatusSteps(currentState = transactionState)
                }

                // Transaction hash card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "Transaction Hash",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = txHash,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                    }
                }

                // Confirmations badge (if confirmed)
                if (isConfirmed && confirmations > 0) {
                    Surface(
                        color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "✓ $confirmations confirmation${if (confirmations > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isConfirmed) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("Done")
                }
            } else if (isFailed) {
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (!isConfirmed && !isFailed) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text("Hide")
                }
            }
        }
    )
}

@Composable
fun StatusSteps(
    currentState: TransactionState
) {
    val steps = listOf(
        "Submitted" to (currentState != TransactionState.SENDING),
        "Pending" to (currentState == TransactionState.PENDING ||
                      currentState == TransactionState.PROPOSED ||
                      currentState == TransactionState.CONFIRMED),
        "Confirmed" to (currentState == TransactionState.CONFIRMED)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, (label, isComplete) ->
            StatusStep(
                label = label,
                isComplete = isComplete,
                isCurrent = when (index) {
                    0 -> currentState == TransactionState.SENDING
                    1 -> currentState == TransactionState.PENDING || currentState == TransactionState.PROPOSED
                    2 -> currentState == TransactionState.CONFIRMED
                    else -> false
                }
            )

            if (index < steps.size - 1) {
                // Connector line
                val lineColor by animateColorAsState(
                    targetValue = if (steps[index + 1].second) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    label = "lineColor"
                )

                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .padding(horizontal = 4.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = lineColor
                    ) {}
                }
            }
        }
    }
}

@Composable
fun StatusStep(
    label: String,
    isComplete: Boolean,
    isCurrent: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(12.dp)
                .alpha(if (isCurrent) alpha else 1f),
            shape = MaterialTheme.shapes.small,
            color = when {
                isComplete -> MaterialTheme.colorScheme.primary
                isCurrent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        ) {}

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                isComplete -> MaterialTheme.colorScheme.primary
                isCurrent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
        )
    }
}
