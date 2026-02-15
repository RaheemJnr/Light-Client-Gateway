package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Presents onboarding options and drives navigation and transient UI (snackbars) based on view model state.
 *
 * Observes the onboarding UI state: when a wallet is created it invokes the backup navigation callback,
 * and when an error appears it shows a snackbar with the error message and clears it from the view model.
 *
 * @param onNavigateToHome Callback invoked to navigate to the home screen.
 * @param onNavigateToBackup Callback invoked to navigate to the backup flow after wallet creation.
 * @param onNavigateToImport Callback invoked to navigate to the import/recover screen.
 */
@Composable
fun OnboardingScreen(
    onNavigateToHome: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToImport: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // After wallet creation, navigate to backup screen (not Home)
    LaunchedEffect(uiState.isWalletCreated) {
        if (uiState.isWalletCreated) {
            onNavigateToBackup()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon / Logo Placeholder
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to Pocket Node",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Secure, private, and localized CKB management.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Options
            OnboardingOption(
                title = "Create New Wallet",
                description = "Generate a new wallet with a 12-word recovery phrase.",
                icon = Icons.Default.Add,
                onClick = { viewModel.createNewWallet() },
                isLoading = uiState.isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            OnboardingOption(
                title = "Recover from Seed Phrase",
                description = "Import wallet using your 12-word recovery phrase.",
                icon = Icons.Default.Key,
                onClick = onNavigateToImport,
                isLoading = uiState.isLoading
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Footer
            Text(
                text = "Your keys, your crypto. Data stays on your device.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Renders a clickable onboarding option as a card containing an icon, title, and descriptive text.
 *
 * @param title The option title.
 * @param description The descriptive text shown below the title.
 * @param icon The leading `ImageVector` displayed inside a square surface.
 * @param onClick Callback invoked when the card is tapped.
 * @param isLoading When `true`, the card is disabled and interaction is prevented.
 */
@Composable
private fun OnboardingOption(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    isLoading: Boolean
) {
    Card(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = OutlinedCardTokens.ContainerShape
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1.0f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Dummy object to satisfy M3 shape tokens if they aren't directly available in this compose version
private object OutlinedCardTokens {
    val ContainerShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
}