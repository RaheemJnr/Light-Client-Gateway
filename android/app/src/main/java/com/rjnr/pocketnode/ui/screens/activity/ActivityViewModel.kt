package com.rjnr.pocketnode.ui.screens.activity

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ActivityViewModel"

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    data class UiState(
        val transactions: List<TransactionRecord> = emptyList(),
        val isLoading: Boolean = false,
        val filter: Filter = Filter.ALL,
        val hasMore: Boolean = false,
        val error: String? = null,
        val currentNetwork: NetworkType = NetworkType.MAINNET
    )

    enum class Filter { ALL, RECEIVED, SENT }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.network.collect { network ->
                _uiState.update { it.copy(currentNetwork = network) }
            }
        }
        loadTransactions()
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
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun setFilter(filter: Filter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /** Returns the full transaction list filtered by the current filter setting. */
    fun filteredTransactions(state: UiState): List<TransactionRecord> {
        return when (state.filter) {
            Filter.ALL -> state.transactions
            Filter.RECEIVED -> state.transactions.filter { it.isIncoming() }
            Filter.SENT -> state.transactions.filter { it.isOutgoing() }
        }
    }
}
