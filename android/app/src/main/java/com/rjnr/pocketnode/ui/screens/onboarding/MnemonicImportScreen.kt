package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.ui.util.Bip39WordList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// -- ViewModel --

data class MnemonicImportUiState(
    val words: List<String> = List(12) { "" },
    val suggestions: Map<Int, List<String>> = emptyMap(),
    val wordErrors: Set<Int> = emptySet(),
    val isImporting: Boolean = false,
    val importSuccess: Boolean = false,
    val showPrivateKeyDialog: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MnemonicImportViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val mnemonicManager: MnemonicManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MnemonicImportUiState())
    val uiState: StateFlow<MnemonicImportUiState> = _uiState.asStateFlow()

    fun updateWord(index: Int, text: String) {
        val trimmed = text.trim().lowercase()
        val newWords = _uiState.value.words.toMutableList().apply { set(index, trimmed) }
        val newSuggestions = _uiState.value.suggestions.toMutableMap()
        val newErrors = _uiState.value.wordErrors.toMutableSet()

        if (trimmed.length >= 2) {
            newSuggestions[index] = Bip39WordList.getSuggestions(trimmed)
        } else {
            newSuggestions.remove(index)
        }

        // Mark error if user finished typing (no suggestions match exactly) and word is invalid
        if (trimmed.isNotEmpty() && !Bip39WordList.isValidWord(trimmed)) {
            newErrors.add(index)
        } else {
            newErrors.remove(index)
        }

        _uiState.update {
            it.copy(words = newWords, suggestions = newSuggestions, wordErrors = newErrors)
        }
    }

    fun selectSuggestion(index: Int, word: String) {
        val newWords = _uiState.value.words.toMutableList().apply { set(index, word) }
        val newSuggestions = _uiState.value.suggestions.toMutableMap().apply { remove(index) }
        val newErrors = _uiState.value.wordErrors.toMutableSet().apply { remove(index) }
        _uiState.update {
            it.copy(words = newWords, suggestions = newSuggestions, wordErrors = newErrors)
        }
    }

    fun pasteMnemonic(text: String) {
        val parts = text.trim().lowercase().split("\\s+".toRegex()).take(12)
        val newWords = List(12) { i -> parts.getOrElse(i) { "" } }
        val newErrors = newWords.mapIndexedNotNull { i, w ->
            if (w.isNotEmpty() && !Bip39WordList.isValidWord(w)) i else null
        }.toSet()
        _uiState.update {
            it.copy(words = newWords, suggestions = emptyMap(), wordErrors = newErrors)
        }
    }

    fun importMnemonic() {
        val words = _uiState.value.words.map { it.trim().lowercase() }

        if (words.any { it.isEmpty() }) {
            _uiState.update { it.copy(error = "Please fill in all 12 words") }
            return
        }

        if (!mnemonicManager.validateMnemonic(words)) {
            _uiState.update { it.copy(error = "Invalid mnemonic. Please check your words and try again.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, error = null) }
            repository.importFromMnemonic(words)
                .onSuccess {
                    _uiState.update { it.copy(isImporting = false, importSuccess = true) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isImporting = false, error = error.message) }
                }
        }
    }

    fun showPrivateKeyImport() {
        _uiState.update { it.copy(showPrivateKeyDialog = true) }
    }

    fun hidePrivateKeyImport() {
        _uiState.update { it.copy(showPrivateKeyDialog = false) }
    }

    fun importPrivateKey(hex: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, showPrivateKeyDialog = false, error = null) }
            repository.importExistingWallet(hex)
                .onSuccess {
                    _uiState.update { it.copy(isImporting = false, importSuccess = true) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isImporting = false, error = error.message) }
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
fun MnemonicImportScreen(
    onNavigateToHome: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: MnemonicImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(uiState.importSuccess) {
        if (uiState.importSuccess) onNavigateToHome()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Private key import dialog
    if (uiState.showPrivateKeyDialog) {
        PrivateKeyImportDialog(
            onDismiss = { viewModel.hidePrivateKeyImport() },
            onImport = { viewModel.importPrivateKey(it) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recover Wallet") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Enter your 12-word recovery phrase to restore your wallet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Paste button
            OutlinedButton(
                onClick = {
                    clipboardManager.getText()?.text?.let { text ->
                        viewModel.pasteMnemonic(text)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste from Clipboard")
            }

            // 2-column word grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(12) { index ->
                    WordInputField(
                        index = index,
                        value = uiState.words[index],
                        suggestions = uiState.suggestions[index] ?: emptyList(),
                        isError = uiState.wordErrors.contains(index),
                        onValueChange = { viewModel.updateWord(index, it) },
                        onSuggestionSelected = { viewModel.selectSuggestion(index, it) }
                    )
                }
            }

            // Private key fallback
            TextButton(
                onClick = { viewModel.showPrivateKeyImport() },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Have a private key instead?")
            }

            // Import button
            Button(
                onClick = { viewModel.importMnemonic() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isImporting && uiState.words.all { it.isNotBlank() }
            ) {
                if (uiState.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Import Wallet")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordInputField(
    index: Int,
    value: String,
    suggestions: List<String>,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // Show dropdown when there are suggestions and value doesn't exactly match a suggestion
    LaunchedEffect(suggestions, value) {
        expanded = suggestions.isNotEmpty() && !Bip39WordList.isValidWord(value)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("#${index + 1}") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
            singleLine = true,
            isError = isError,
            textStyle = MaterialTheme.typography.bodyMedium
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            suggestions.forEach { word ->
                DropdownMenuItem(
                    text = { Text(word) },
                    onClick = {
                        onSuggestionSelected(word)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PrivateKeyImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var privateKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Private Key", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter your 64-character private key (hex) to restore your wallet.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it.trim() },
                    label = { Text("Private Key") },
                    placeholder = { Text("0x...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(privateKey) },
                enabled = privateKey.length >= 64
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
