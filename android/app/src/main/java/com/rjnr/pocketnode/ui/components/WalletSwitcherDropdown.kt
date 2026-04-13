package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import com.rjnr.pocketnode.data.database.entity.WalletEntity

// Temporary component — will be replaced by AccountSelectorSheet (imToken-style bottom sheet) in Phase 2, Task 16.
@Composable
fun WalletSwitcherDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    wallets: List<WalletEntity>,
    onSwitchWallet: (String) -> Unit,
    onManageWallets: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        wallets.forEach { wallet ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(wallet.name, modifier = Modifier.weight(1f))
                        if (wallet.isActive) {
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
                    onDismiss()
                    onSwitchWallet(wallet.walletId)
                }
            )
        }
        if (wallets.isNotEmpty()) {
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text("Manage Wallets") },
            onClick = {
                onDismiss()
                onManageWallets()
            }
        )
    }
}
