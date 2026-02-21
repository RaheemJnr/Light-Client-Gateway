package com.rjnr.pocketnode.ui.screens.status

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniRemoteNode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

data class NodeStatusUiState(
    val tipHeader: JniHeaderView? = null,
    val peers: List<JniRemoteNode> = emptyList(),
    val scriptsJson: String = "",
    val rpcResult: String = "",
    val logs: List<String> = emptyList()
)

@HiltViewModel
class NodeStatusViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val json: Json
) : ViewModel() {

    private val _uiState = MutableStateFlow(NodeStatusUiState())
    val uiState: StateFlow<NodeStatusUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var logcatProcess: Process? = null
    private var logJob: Job? = null

    init {
        startRefreshing()
        startLogcat()
    }

    private fun startRefreshing() {
        refreshJob = viewModelScope.launch {
            while (isActive) {
                updateStatus()
                delay(3000) // Refresh every 3 seconds
            }
        }
    }

    private suspend fun updateStatus() {
        try {
            val peersRaw = repository.getPeers() ?: ""
            val tipRaw = repository.getTipHeader() ?: ""
            val scripts = repository.getScripts() ?: ""

            val parsedTip = runCatching {
                json.decodeFromString<JniHeaderView>(tipRaw)
            }.getOrNull()

            val parsedPeers = runCatching {
                json.decodeFromString<List<JniRemoteNode>>(peersRaw)
            }.getOrDefault(emptyList())

            _uiState.update {
                it.copy(
                    tipHeader = parsedTip,
                    peers = parsedPeers,
                    scriptsJson = scripts
                )
            }
        } catch (e: Exception) {
            Log.e("NodeStatusVM", "Error updating status", e)
        }
    }

    fun callRpc(method: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(rpcResult = "Calling...") }
                val result = repository.callRpc(method) ?: "null"
                _uiState.update { it.copy(rpcResult = result) }
            } catch (e: Exception) {
                _uiState.update { it.copy(rpcResult = "Error: ${e.message}") }
            }
        }
    }

    private fun startLogcat() {
        logJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Clear previous logs
                _uiState.update { it.copy(logs = emptyList()) }

                // Start logcat process
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "time", "-s", "ckb-light-client:*", "LightClientService:*", "LightClientNative:*", "NodeStatusVM:*")
                )
                logcatProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String? = reader.readLine()
                val batch = ArrayList<String>()
                var lastUpdate = System.currentTimeMillis()

                while (isActive && line != null) {
                    batch.add(line)
                    
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 500 || batch.size > 100) {
                        val newBatch = batch.toList()
                        batch.clear()
                        lastUpdate = now
                        
                        _uiState.update { current ->
                            val newLogs = current.logs + newBatch
                            current.copy(logs = newLogs.takeLast(1000))
                        }
                    }
                    
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Log.e("NodeStatusVM", "Error reading logs", e)
                _uiState.update { it.copy(logs = it.logs + "Error reading logs: ${e.message}") }
            }
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        logJob?.cancel()
        logcatProcess?.destroy()
    }
}
