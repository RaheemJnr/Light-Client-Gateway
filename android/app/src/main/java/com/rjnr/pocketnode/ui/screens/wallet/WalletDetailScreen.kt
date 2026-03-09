package com.rjnr.pocketnode.ui.screens.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDetailScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: WalletDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    var showSeedPhrase by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigateBack()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("Delete Wallet?") },
            text = { Text("This will permanently remove the wallet and its keys. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDelete() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!uiState.isEditing) {
                        IconButton(onClick = { viewModel.startEditing() }) {
                            Icon(Lucide.Pencil, contentDescription = "Edit Name")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val wallet = uiState.wallet ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            // Name section
            if (uiState.isEditing) {
                OutlinedTextField(
                    value = uiState.editName,
                    onValueChange = { viewModel.updateEditName(it) },
                    label = { Text("Wallet Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { viewModel.cancelEditing() }) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.saveName() },
                        enabled = uiState.editName.isNotBlank()
                    ) {
                        Text("Save")
                    }
                }
            } else {
                Text(
                    text = wallet.name,
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            Spacer(Modifier.height(16.dp))

            // Type
            DetailCard(label = "Type", value = when (wallet.type) {
                "mnemonic" -> if (wallet.parentWalletId != null) "Sub-Account" else "Seed Phrase Wallet"
                "raw_key" -> "Imported Key"
                else -> wallet.type
            })

            // Derivation path
            if (wallet.derivationPath != null) {
                DetailCard(label = "Derivation Path", value = wallet.derivationPath)
            }

            // Addresses
            AddressCard(
                label = "Mainnet Address",
                address = wallet.mainnetAddress,
                onCopy = { clipboardManager.setText(AnnotatedString(wallet.mainnetAddress)) }
            )
            AddressCard(
                label = "Testnet Address",
                address = wallet.testnetAddress,
                onCopy = { clipboardManager.setText(AnnotatedString(wallet.testnetAddress)) }
            )

            // Seed phrase
            if (viewModel.hasMnemonic()) {
                Spacer(Modifier.height(16.dp))
                if (showSeedPhrase) {
                    val words = viewModel.getMnemonic()
                    if (words != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Seed Phrase",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = words.joinToString(" "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { showSeedPhrase = false }) {
                                    Text("Hide")
                                }
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showSeedPhrase = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View Seed Phrase")
                    }
                }
            }

            // Delete button (only for non-active wallets)
            if (!wallet.isActive) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.requestDelete() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete Wallet")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailCard(label: String, value: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AddressCard(label: String, address: String, onCopy: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onCopy) {
                Icon(Lucide.Copy, contentDescription = "Copy", modifier = Modifier)
            }
        }
    }
}
