package com.rjnr.pocketnode.ui.screens.activity

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.export.TransactionExporter
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ActivityViewModel"

data class ActivityUiState(
    val transactions: List<TransactionRecord> = emptyList(),
    val isLoading: Boolean = false,
    val filter: ActivityViewModel.Filter = ActivityViewModel.Filter.ALL,
    val hasMore: Boolean = false,
    val error: String? = null,
    val currentNetwork: NetworkType = NetworkType.MAINNET
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val transactionDao: TransactionDao,
    private val walletPreferences: WalletPreferences
) : ViewModel() {

    enum class Filter { ALL, RECEIVED, SENT }

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    private val _exportEvent = MutableSharedFlow<String>()
    val exportEvent: SharedFlow<String> = _exportEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.network.collect { network ->
                _uiState.update { it.copy(currentNetwork = network) }
                loadTransactions()
            }
        }
    }

    fun loadTransactions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getTransactions()
                .onSuccess { response ->
                    Log.d(TAG, "Loaded ${response.items.size} transactions")
                    _uiState.update {
                        it.copy(
                            transactions = response.items,
                            isLoading = false,
                            hasMore = response.nextCursor != null
                        )
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to load transactions", error)
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "Failed to load transactions")
                    }
                }
        }
    }

    fun setFilter(filter: Filter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /** Returns the full transaction list filtered by the current filter setting. */
    fun filteredTransactions(state: ActivityUiState): List<TransactionRecord> {
        return when (state.filter) {
            Filter.ALL -> state.transactions
            Filter.RECEIVED -> state.transactions.filter { it.isIncoming() || it.isDaoUnlock() }
            Filter.SENT -> state.transactions.filter { it.isOutgoing() || it.isDaoDeposit() || it.isDaoWithdraw() }
        }
    }

    fun exportTransactions() {
        viewModelScope.launch {
            runCatching {
                val walletId = walletPreferences.getActiveWalletId() ?: ""
                val network = _uiState.value.currentNetwork.name
                val entities = transactionDao.getAllByWalletAndNetwork(walletId, network)
                TransactionExporter().exportToCsv(entities)
            }.onSuccess { csv ->
                _exportEvent.emit(csv)
            }.onFailure { error ->
                Log.e(TAG, "Export failed", error)
                _uiState.update { it.copy(error = "Export failed: ${error.message}") }
            }
        }
    }
}
