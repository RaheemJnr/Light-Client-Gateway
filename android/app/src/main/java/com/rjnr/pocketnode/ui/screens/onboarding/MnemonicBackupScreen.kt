package com.rjnr.pocketnode.ui.screens.onboarding

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// -- ViewModel --

data class MnemonicBackupUiState(
    val currentStep: Int = 1,
    val words: List<String> = emptyList(),
    val verifyPositions: List<Int> = emptyList(),
    val verifyOptions: Map<Int, List<String>> = emptyMap(),
    val userSelections: Map<Int, String> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class MnemonicBackupViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MnemonicBackupUiState())
    val uiState: StateFlow<MnemonicBackupUiState> = _uiState.asStateFlow()

    init {
        loadMnemonic()
    }

    private fun loadMnemonic() {
        val words = repository.getMnemonic()
        if (words.isNullOrEmpty()) {
            _uiState.update { it.copy(error = "No mnemonic found") }
            return
        }
        val positions = (words.indices).shuffled().take(3).sorted()
        val options = positions.associateWith { pos ->
            val correct = words[pos]
            val decoys = words.filterIndexed { i, _ -> i != pos }.shuffled().take(3)
            (decoys + correct).shuffled()
        }
        _uiState.update {
            it.copy(words = words, verifyPositions = positions, verifyOptions = options)
        }
    }

    fun advanceToVerify() {
        _uiState.update { it.copy(currentStep = 2, error = null) }
    }

    fun selectWord(position: Int, word: String) {
        _uiState.update {
            it.copy(userSelections = it.userSelections + (position to word), error = null)
        }
    }

    fun verify() {
        val state = _uiState.value
        val allCorrect = state.verifyPositions.all { pos ->
            state.userSelections[pos] == state.words[pos]
        }
        if (allCorrect) {
            repository.setMnemonicBackedUp(true)
            _uiState.update { it.copy(currentStep = 3) }
        } else {
            _uiState.update {
                it.copy(error = "Some words are incorrect. Please try again.", userSelections = emptyMap())
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

// -- Screen --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicBackupScreen(
    onNavigateToHome: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: MnemonicBackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // FLAG_SECURE to prevent screenshots of mnemonic
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (uiState.currentStep) {
                            1 -> "Back Up Your Wallet"
                            2 -> "Verify Your Backup"
                            else -> "Backup Complete"
                        }
                    )
                },
                navigationIcon = {
                    if (uiState.currentStep < 3) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (uiState.currentStep) {
            1 -> MnemonicDisplayStep(
                words = uiState.words,
                onNext = { viewModel.advanceToVerify() },
                modifier = Modifier.padding(padding)
            )
            2 -> MnemonicVerifyStep(
                verifyPositions = uiState.verifyPositions,
                verifyOptions = uiState.verifyOptions,
                userSelections = uiState.userSelections,
                onSelectWord = { pos, word -> viewModel.selectWord(pos, word) },
                onVerify = { viewModel.verify() },
                modifier = Modifier.padding(padding)
            )
            3 -> MnemonicSuccessStep(
                onComplete = onNavigateToHome,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun MnemonicDisplayStep(
    words: List<String>,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Warning
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Write these 12 words down in order. Never share them with anyone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Word grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(words) { index, word ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            word,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Continue button
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            enabled = words.isNotEmpty()
        ) {
            Text("I've Written Them Down")
        }
    }
}

@Composable
private fun MnemonicVerifyStep(
    verifyPositions: List<Int>,
    verifyOptions: Map<Int, List<String>>,
    userSelections: Map<Int, String>,
    onSelectWord: (Int, String) -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            "Select the correct word for each position to verify your backup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        verifyPositions.forEach { position ->
            val options = verifyOptions[position] ?: return@forEach
            val selected = userSelections[position]

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Word #${position + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // 2x2 grid of options
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in options.chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { word ->
                                val isSelected = selected == word
                                OutlinedButton(
                                    onClick = { onSelectWord(position, word) },
                                    modifier = Modifier.weight(1f),
                                    colors = if (isSelected) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    }
                                ) {
                                    Text(word)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onVerify,
            modifier = Modifier.fillMaxWidth(),
            enabled = verifyPositions.all { userSelections.containsKey(it) }
        ) {
            Text("Verify")
        }
    }
}

@Composable
private fun MnemonicSuccessStep(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Backup Complete!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Your wallet recovery phrase is safely backed up.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}
