package com.example.ckbwallet.ui.screens.send

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ckbwallet.data.gateway.GatewayRepository
import com.example.ckbwallet.data.gateway.models.TransactionStatusResponse
import com.example.ckbwallet.data.transaction.TransactionBuilder
import com.example.ckbwallet.data.wallet.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TransactionState {
    IDLE,           // No transaction in progress
    SENDING,        // Transaction being built and sent
    PENDING,        // Transaction submitted, waiting for confirmation
    PROPOSED,       // Transaction in proposal stage
    CONFIRMED,      // Transaction confirmed on chain
    FAILED          // Transaction failed
}

data class SendUiState(
    val recipientAddress: String = "",
    val amountCkb: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val txHash: String? = null,
    val availableBalance: Long = 0L,
    val transactionState: TransactionState = TransactionState.IDLE,
    val confirmations: Int = 0,
    val statusMessage: String = ""
)

@HiltViewModel
class SendViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val keyManager: KeyManager,
    private val transactionBuilder: TransactionBuilder
) : ViewModel() {

    private val _uiState = MutableStateFlow(SendUiState())
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    companion object {
        private const val TAG = "SendViewModel"
        private const val POLLING_INTERVAL_MS = 3000L // Poll every 3 seconds
        private const val MAX_POLLING_ATTEMPTS = 120  // Stop after ~6 minutes
        private const val REQUIRED_CONFIRMATIONS = 3  // Consider fully confirmed after 3 confirmations
    }

    init {
        viewModelScope.launch {
            repository.balance.collect { balance ->
                _uiState.update { it.copy(availableBalance = balance?.capacityAsLong() ?: 0L) }
            }
        }
    }

    fun updateRecipient(address: String) {
        _uiState.update { it.copy(recipientAddress = address, error = null) }
    }

    fun updateAmount(amount: String) {
        if (amount.isEmpty() || amount.matches(Regex("^\\d*\\.?\\d*$"))) {
            _uiState.update { it.copy(amountCkb = amount, error = null) }
        }
    }

    fun sendTransaction() {
        val state = _uiState.value

        if (state.recipientAddress.isBlank()) {
            _uiState.update { it.copy(error = "Please enter recipient address") }
            return
        }

        if (state.amountCkb.isBlank()) {
            _uiState.update { it.copy(error = "Please enter amount") }
            return
        }

        val amountShannons = try {
            (state.amountCkb.toDouble() * 100_000_000).toLong()
        } catch (e: NumberFormatException) {
            _uiState.update { it.copy(error = "Invalid amount") }
            return
        }

        val minCapacity = 61_00000000L
        if (amountShannons < minCapacity) {
            _uiState.update { it.copy(error = "Minimum transfer is 61 CKB") }
            return
        }

        if (amountShannons > state.availableBalance) {
            _uiState.update { it.copy(error = "Insufficient balance") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    transactionState = TransactionState.SENDING,
                    statusMessage = "Building transaction..."
                )
            }

            try {
                Log.d(TAG, "📤 Starting send transaction flow")
                Log.d(TAG, "  Recipient: ${state.recipientAddress}")
                Log.d(TAG, "  Amount: ${state.amountCkb} CKB ($amountShannons shannons)")

                val walletInfo = keyManager.getWalletInfo()
                val address = walletInfo.mainnetAddress
                Log.d(TAG, "  From address: $address")

                Log.d(TAG, "🔍 Fetching available cells...")
                val cellsResult = repository.getCells(address)
                val cells = cellsResult.getOrThrow().items
                Log.d(TAG, "✅ Got ${cells.size} cells")
                cells.forEachIndexed { i, cell ->
                    Log.d(TAG, "  Cell[$i]: ${cell.capacityAsLong()} shannons")
                }

                _uiState.update { it.copy(statusMessage = "Signing transaction...") }
                Log.d(TAG, "✍️ Building and signing transaction...")

                val signedTx = transactionBuilder.buildTransfer(
                    fromAddress = address,
                    toAddress = state.recipientAddress,
                    amountShannons = amountShannons,
                    availableCells = cells,
                    privateKey = keyManager.getPrivateKey()
                )
                Log.d(TAG, "✅ Transaction built: ${signedTx.cellInputs.size} inputs, ${signedTx.cellOutputs.size} outputs")

                _uiState.update { it.copy(statusMessage = "Broadcasting transaction...") }
                Log.d(TAG, "📡 Broadcasting transaction...")

                val txHash = repository.sendTransaction(signedTx).getOrThrow()
                Log.d(TAG, "✅ Transaction sent! Hash: $txHash")

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        txHash = txHash,
                        recipientAddress = "",
                        amountCkb = "",
                        transactionState = TransactionState.PENDING,
                        statusMessage = "Transaction submitted. Waiting for confirmation..."
                    )
                }

                // Start polling for transaction status
                startPollingTransactionStatus(txHash, address)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Transaction failed", e)
                Log.e(TAG, "  Error type: ${e.javaClass.simpleName}")
                Log.e(TAG, "  Error message: ${e.message}")
                e.printStackTrace()

                val userFriendlyError = parseErrorMessage(e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = userFriendlyError,
                        transactionState = TransactionState.FAILED,
                        statusMessage = "Transaction failed"
                    )
                }
            }
        }
    }

    /**
     * Parse technical error messages into user-friendly descriptions
     */
    private fun parseErrorMessage(e: Exception): String {
        val message = e.message ?: "Unknown error"

        return when {
            // Cell/UTXO errors
            message.contains("Failed to get cells", ignoreCase = true) ->
                "Could not fetch your available funds. Please ensure your wallet is synced and try again."
            message.contains("No cells available", ignoreCase = true) ||
            message.contains("Insufficient cells", ignoreCase = true) ->
                "Not enough funds available. Please wait for your wallet to fully sync."

            // Transaction building errors
            message.contains("Insufficient balance", ignoreCase = true) ->
                "Insufficient balance for this transaction."
            message.contains("minimum", ignoreCase = true) && message.contains("61", ignoreCase = true) ->
                "Minimum transfer amount is 61 CKB due to CKB's cell model."

            // Network/broadcast errors
            message.contains("Send failed", ignoreCase = true) ||
            message.contains("broadcast", ignoreCase = true) ->
                "Could not broadcast transaction. Please check your network connection and try again."
            message.contains("verification failed", ignoreCase = true) ->
                "Transaction verification failed. The transaction may be invalid."

            // Sync errors
            message.contains("not synced", ignoreCase = true) ||
            message.contains("sync", ignoreCase = true) ->
                "Wallet is still syncing. Please wait for sync to complete before sending."

            // JSON/parsing errors (likely a bug)
            message.contains("json", ignoreCase = true) ||
            message.contains("parse", ignoreCase = true) ||
            message.contains("serial", ignoreCase = true) ||
            message.contains("missing", ignoreCase = true) ->
                "Internal error processing transaction data. Please try again or restart the app."

            // Generic fallback
            else -> "Transaction failed: $message"
        }
    }

    private fun startPollingTransactionStatus(txHash: String, address: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var attempts = 0

            while (attempts < MAX_POLLING_ATTEMPTS) {
                delay(POLLING_INTERVAL_MS)
                attempts++

                try {
                    val statusResult = repository.getTransactionStatus(txHash)
                    statusResult.onSuccess { status ->
                        updateTransactionStatus(status)

                        // Stop polling only after reaching required confirmations
                        if (status.isConfirmed() && (status.confirmations ?: 0) >= REQUIRED_CONFIRMATIONS) {
                            // Refresh balance after full confirmation
                            repository.refreshBalance(address)
                            _uiState.update {
                                it.copy(
                                    statusMessage = "Fully confirmed with ${status.confirmations} confirmations ✓"
                                )
                            }
                            return@launch
                        }
                    }.onFailure {
                        // Continue polling on failure
                    }
                } catch (e: Exception) {
                    // Continue polling on exception
                }
            }

            // Max attempts reached - leave in current state
            _uiState.update {
                it.copy(
                    statusMessage = "Status check timed out. Transaction may still confirm."
                )
            }
        }
    }

    private fun updateTransactionStatus(status: TransactionStatusResponse) {
        val newState = when {
            status.isConfirmed() -> TransactionState.CONFIRMED
            status.status == "proposed" -> TransactionState.PROPOSED
            status.isPending() -> TransactionState.PENDING
            status.isUnknown() -> TransactionState.PENDING // Treat unknown as pending initially
            else -> TransactionState.PENDING
        }

        val confirmations = status.confirmations ?: 0

        val message = when (newState) {
            TransactionState.PENDING -> "Transaction pending..."
            TransactionState.PROPOSED -> "Transaction in proposal stage..."
            TransactionState.CONFIRMED -> when {
                confirmations >= REQUIRED_CONFIRMATIONS ->
                    "Fully confirmed with $confirmations confirmations ✓"
                confirmations == 1 ->
                    "1 confirmation (waiting for ${REQUIRED_CONFIRMATIONS - 1} more)..."
                else ->
                    "$confirmations confirmations (waiting for ${REQUIRED_CONFIRMATIONS - confirmations} more)..."
            }
            else -> "Processing..."
        }

        _uiState.update {
            it.copy(
                transactionState = newState,
                confirmations = confirmations,
                statusMessage = message
            )
        }
    }

    fun clearTxHash() {
        pollingJob?.cancel()
        _uiState.update {
            it.copy(
                txHash = null,
                transactionState = TransactionState.IDLE,
                confirmations = 0,
                statusMessage = ""
            )
        }
    }

    fun clearError() {
        _uiState.update {
            it.copy(
                error = null,
                transactionState = if (it.txHash != null) it.transactionState else TransactionState.IDLE
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
