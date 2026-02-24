package com.rjnr.pocketnode.ui.screens.dao

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DaoViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DaoUiState())
    val uiState: StateFlow<DaoUiState> = _uiState.asStateFlow()

    val availableBalance: StateFlow<Long> = repository.balance
        .map { it?.capacityAsLong() ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                refreshDaoData()
                val interval = if (_uiState.value.pendingAction != null) 10_000L else 30_000L
                delay(interval)
            }
        }
    }

    private suspend fun refreshDaoData() {
        repository.getDaoDeposits()
            .onSuccess { deposits ->
                val active = deposits
                    .filter { it.status != DaoCellStatus.COMPLETED }
                    .sortedByDescending { it.depositBlockNumber }
                val completed = deposits
                    .filter { it.status == DaoCellStatus.COMPLETED }
                    .sortedByDescending { it.depositBlockNumber }

                val overview = DaoOverview(
                    totalLocked = active.sumOf { it.capacity },
                    totalCompensation = deposits.sumOf { it.compensation },
                    currentApc = 2.47, // approximate
                    activeCount = active.size,
                    completedCount = completed.size
                )

                _uiState.update {
                    it.copy(
                        overview = overview,
                        activeDeposits = active,
                        completedDeposits = completed,
                        isLoading = false
                    )
                }

                // Auto-clear pending actions when state transitions
                resolvePendingAction(deposits)
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(error = e.message, isLoading = false)
                }
            }
    }

    private fun resolvePendingAction(deposits: List<DaoDeposit>) {
        val pending = _uiState.value.pendingAction ?: return
        if (shouldClearPendingAction(pending, deposits)) {
            _uiState.update { it.copy(pendingAction = null) }
        }
    }

    fun deposit(amountShannons: Long) {
        _uiState.update { it.copy(pendingAction = DaoAction.Depositing(amountShannons)) }
        viewModelScope.launch {
            repository.depositToDao(amountShannons)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    fun withdraw(deposit: DaoDeposit) {
        _uiState.update { it.copy(pendingAction = DaoAction.Withdrawing(deposit.outPoint)) }
        viewModelScope.launch {
            repository.withdrawFromDao(deposit.outPoint)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    fun unlock(deposit: DaoDeposit) {
        _uiState.update { it.copy(pendingAction = DaoAction.Unlocking(deposit.outPoint)) }
        viewModelScope.launch {
            repository.unlockDao(
                withdrawingOutPoint = deposit.outPoint,
                depositBlockHash = deposit.depositBlockHash,
                withdrawBlockHash = deposit.withdrawBlockHash
                    ?: throw Exception("No withdraw block hash")
            )
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    fun selectTab(tab: DaoTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

internal fun shouldClearPendingAction(
    pendingAction: DaoAction,
    deposits: List<DaoDeposit>
): Boolean = when (pendingAction) {
    is DaoAction.Depositing -> deposits.any { it.status == DaoCellStatus.DEPOSITED }
    is DaoAction.Withdrawing -> deposits.any {
        it.outPoint == pendingAction.outPoint &&
            (it.status == DaoCellStatus.LOCKED || it.status == DaoCellStatus.UNLOCKABLE)
    }
    is DaoAction.Unlocking -> deposits.none { it.outPoint == pendingAction.outPoint }
}
