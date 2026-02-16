package com.rjnr.pocketnode.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PinMode { SETUP, CONFIRM, VERIFY }

data class PinUiState(
    val mode: PinMode = PinMode.VERIFY,
    val enteredDigits: String = "",
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isLockedOut: Boolean = false,
    val lockoutRemainingSeconds: Int = 0,
    val remainingAttempts: Int = PinManager.MAX_ATTEMPTS,
    val pinComplete: Boolean = false,
    val title: String = "Enter PIN",
    val subtitle: String? = null
)

@HiltViewModel
class PinViewModel @Inject constructor(
    private val pinManager: PinManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PinUiState())
    val uiState: StateFlow<PinUiState> = _uiState.asStateFlow()

    private var setupPin: String? = null
    private var lockoutTimerJob: Job? = null

    fun setMode(mode: PinMode) {
        val (title, subtitle) = when (mode) {
            PinMode.SETUP -> "Create PIN" to "Choose a 6-digit PIN"
            PinMode.CONFIRM -> "Confirm PIN" to "Re-enter your PIN"
            PinMode.VERIFY -> "Enter PIN" to null
        }
        _uiState.update {
            it.copy(
                mode = mode,
                title = title,
                subtitle = subtitle,
                enteredDigits = "",
                pinComplete = false,
                isError = false,
                errorMessage = null
            )
        }
        if (mode == PinMode.VERIFY) {
            refreshLockoutState()
        }
    }

    fun setSetupPin(pin: String) {
        setupPin = pin
    }

    fun getEnteredPin(): String = _uiState.value.enteredDigits

    fun consumePinComplete() {
        _uiState.update { it.copy(pinComplete = false) }
    }

    fun onDigitEntered(digit: Char) {
        val current = _uiState.value
        if (current.isLockedOut || current.enteredDigits.length >= PinManager.PIN_LENGTH) return

        val newDigits = current.enteredDigits + digit
        _uiState.update { it.copy(enteredDigits = newDigits, isError = false, errorMessage = null) }

        if (newDigits.length == PinManager.PIN_LENGTH) {
            handlePinSubmit(newDigits)
        }
    }

    fun onDeleteDigit() {
        val current = _uiState.value
        if (current.enteredDigits.isEmpty()) return
        _uiState.update { it.copy(enteredDigits = current.enteredDigits.dropLast(1)) }
    }

    private fun handlePinSubmit(pin: String) {
        when (_uiState.value.mode) {
            PinMode.SETUP -> {
                _uiState.update { it.copy(pinComplete = true) }
            }

            PinMode.CONFIRM -> {
                if (pin == setupPin) {
                    pinManager.setPin(pin)
                    _uiState.update { it.copy(pinComplete = true) }
                } else {
                    showError("PINs don't match. Try again.")
                }
            }

            PinMode.VERIFY -> {
                if (pinManager.verifyPin(pin)) {
                    _uiState.update { it.copy(pinComplete = true) }
                } else {
                    val remaining = pinManager.getRemainingAttempts()
                    if (pinManager.isLockedOut()) {
                        startLockoutTimer()
                    } else {
//                        showError("Wrong PIN. $remaining attempts remaining.")
//                        _uiState.update { it.copy(remainingAttempts = remaining) }
                        _uiState.update {
                            it.copy(
                                isError = true,
                                errorMessage = "Wrong PIN. $remaining attempts remaining.",
                                enteredDigits = "",
                                remainingAttempts = remaining
                            )
                        }
                        viewModelScope.launch {
                            delay(500)
                            _uiState.update { it.copy(isError = false) }
                        }

                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(isError = true, errorMessage = message, enteredDigits = "") }
        viewModelScope.launch {
            delay(500)
            _uiState.update { it.copy(isError = false) }
        }
    }

    private fun refreshLockoutState() {
        if (pinManager.isLockedOut()) {
            startLockoutTimer()
        } else {
            _uiState.update {
                it.copy(
                    isLockedOut = false,
                    lockoutRemainingSeconds = 0,
                    remainingAttempts = pinManager.getRemainingAttempts()
                )
            }
        }
    }

    private fun startLockoutTimer() {
        lockoutTimerJob?.cancel()
        _uiState.update { it.copy(isLockedOut = true, enteredDigits = "", errorMessage = null) }
        lockoutTimerJob = viewModelScope.launch {
            while (pinManager.isLockedOut()) {
                val remainingMs = pinManager.getLockoutRemainingMs()
                _uiState.update {
                    it.copy(lockoutRemainingSeconds = ((remainingMs + 999) / 1000).toInt())
                }
                delay(1000)
            }
            _uiState.update {
                it.copy(
                    isLockedOut = false,
                    lockoutRemainingSeconds = 0,
                    remainingAttempts = pinManager.getRemainingAttempts()
                )
            }
        }
    }
}
