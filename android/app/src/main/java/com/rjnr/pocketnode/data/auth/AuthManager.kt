package com.rjnr.pocketnode.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @VisibleForTesting
    internal var testPrefs: SharedPreferences? = null

    private val prefs: SharedPreferences
        get() = testPrefs ?: defaultPrefs

    private val defaultPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @VisibleForTesting
    internal var testBiometricManager: BiometricManager? = null

    private val biometricMgr: BiometricManager
        get() = testBiometricManager ?: BiometricManager.from(context)

    enum class BiometricStatus {
        AVAILABLE,
        NO_HARDWARE,
        NOT_ENROLLED,
        UNAVAILABLE
    }

    fun isBiometricAvailable(): BiometricStatus {
        return when (biometricMgr.canAuthenticate(Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            else -> BiometricStatus.UNAVAILABLE
        }
    }

    fun isBiometricEnrolled(): Boolean =
        isBiometricAvailable() == BiometricStatus.AVAILABLE

    fun isBiometricEnabled(): Boolean =
        prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun hasDeviceCredential(): Boolean =
        biometricMgr.canAuthenticate(Authenticators.DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun getAllowedAuthenticators(): Int {
        return if (isBiometricEnrolled() && isBiometricEnabled()) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.DEVICE_CREDENTIAL
        }
    }

    companion object {
        private const val PREFS_NAME = "ckb_auth_settings"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    }
}
