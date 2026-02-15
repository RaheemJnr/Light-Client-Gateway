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

    /**
     * Update the mnemonic word at the given position and refresh its suggestions and validation state.
     *
     * Sets the word at `index` (trimmed and lowercased), updates per-index suggestions when the entry is long enough,
     * and adjusts the set of invalid-word indices accordingly.
     *
     * @param index The word position (0-based).
     * @param text The new text for the word input.
     */
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

    /**
     * Sets the selected suggestion into the mnemonic words at the given index and clears any suggestions and validation error for that position.
     *
     * @param index Zero-based position in the 12-word mnemonic to update.
     * @param word The suggested word to insert at the specified index.
     */
    fun selectSuggestion(index: Int, word: String) {
        val newWords = _uiState.value.words.toMutableList().apply { set(index, word) }
        val newSuggestions = _uiState.value.suggestions.toMutableMap().apply { remove(index) }
        val newErrors = _uiState.value.wordErrors.toMutableSet().apply { remove(index) }
        _uiState.update {
            it.copy(words = newWords, suggestions = newSuggestions, wordErrors = newErrors)
        }
    }

    /**
     * Populate the 12 mnemonic word inputs from a pasted string, validating each word and updating suggestions and error indices.
     *
     * @param text A string containing a mnemonic phrase (words separated by whitespace); may contain fewer than 12 words.
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

    /**
     * Initiates import of the current 12-word mnemonic from UI state into the repository.
     *
     * Trims and lowercases the stored words, validates presence of all 12 words and the mnemonic itself,
     * and then performs the import asynchronously. On validation failure sets `uiState.error` with a
     * descriptive message. During the import updates `uiState.isImporting`, and on completion sets
     * `uiState.importSuccess` on success or `uiState.error` with the failure message on error.
     */
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

    /**
     * Shows the private key import dialog.
     *
     * Sets the UI state flag to display the private key import dialog.
     */
    fun showPrivateKeyImport() {
        _uiState.update { it.copy(showPrivateKeyDialog = true) }
    }

    /**
     * Closes the private key import dialog in the UI.
     *
     * Updates the view model state so the private key dialog is not shown.
     */
    fun hidePrivateKeyImport() {
        _uiState.update { it.copy(showPrivateKeyDialog = false) }
    }

    /**
     * Imports a wallet from the given hex private key and updates the UI state to reflect progress and outcome.
     *
     * While the import is in progress the UI state is set to indicate loading and the private key dialog is dismissed.
     * On success the UI state is updated to mark import success; on failure the UI state records the error message.
     *
     * @param hex The private key as a hex string (typically 64 hex characters, with or without a leading "0x").
     */
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

    /**
     * Clears the general error message from the UI state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

/**
 * Composable screen that lets the user restore a wallet from a 12-word mnemonic or import a private key.
 *
 * Displays inputs for the 12 recovery words with per-word suggestions, a "Paste from Clipboard" action,
 * an option to import a private key, and an import button that shows progress and error feedback via a Snackbar.
 *
 * @param onNavigateToHome Callback invoked when the import completes successfully; should navigate to the app's home.
 * @param onNavigateBack Callback invoked when the user requests back navigation from this screen.
 * @param viewModel ViewModel that supplies UI state and handles actions for mnemonic and private-key import.

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

/**
 * Input field for a single mnemonic word that shows selectable suggestions.
 *
 * Displays a labeled single-line text field for the word at the given index and, when available,
 * presents a dropdown of suggested BIP-39 words to choose from.
 *
 * @param index Zero-based position of this word in the 12-word mnemonic.
 * @param value Current text value of the field.
 * @param suggestions List of suggested words to display in the dropdown for this position.
 * @param isError Whether the field is currently in an error state (invalid word).
 * @param onValueChange Called when the text value changes.
 * @param onSuggestionSelected Called with the selected suggestion when the user picks one.
 */
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

/**
 * Shows a dialog that lets the user enter a 64-character hex private key to restore a wallet.
 *
 * The dialog disables the confirm button until the entered key has at least 64 characters.
 *
 * @param onDismiss Called when the dialog is dismissed or the user cancels.
 * @param onImport Called with the entered private key when the user confirms import.
 */
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