package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SecurityBannerState(
    val hasPinOrBiometrics: Boolean,
    val hasMnemonicBackup: Boolean
) {
    val isVisible: Boolean get() = !hasPinOrBiometrics || !hasMnemonicBackup
    val allComplete: Boolean get() = hasPinOrBiometrics && hasMnemonicBackup

    val message: String get() = when {
        !hasPinOrBiometrics && !hasMnemonicBackup -> "Secure your wallet"
        !hasPinOrBiometrics -> "Set up a PIN to protect your wallet"
        !hasMnemonicBackup -> "Back up your recovery phrase"
        else -> ""
    }

    val actionLabel: String get() = when {
        !hasPinOrBiometrics && !hasMnemonicBackup -> "Set up security"
        !hasPinOrBiometrics -> "Set up PIN"
        !hasMnemonicBackup -> "Back up now"
        else -> ""
    }
}

@Composable
fun SecurityBanner(
    state: SecurityBannerState,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            FilledTonalButton(onClick = onActionClick) {
                Text(state.actionLabel)
            }
        }
    }
}
