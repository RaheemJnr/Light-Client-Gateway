package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.rjnr.pocketnode.data.database.entity.WalletEntity

@Composable
fun WalletSwitcherDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    wallets: List<WalletEntity>,
    onSwitchWallet: (String) -> Unit,
    onManageWallets: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        wallets.forEach { wallet ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = wallet.name,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (wallet.isActive) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Lucide.Check,
                                contentDescription = "Active",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                onClick = {
                    onSwitchWallet(wallet.walletId)
                    onDismiss()
                }
            )
        }

        if (wallets.isNotEmpty()) {
            HorizontalDivider()
        }

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Lucide.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Manage Wallets", style = MaterialTheme.typography.bodyMedium)
                }
            },
            onClick = {
                onManageWallets()
                onDismiss()
            }
        )
    }
}
