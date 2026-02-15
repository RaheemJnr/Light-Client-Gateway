package com.rjnr.pocketnode.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AuthManagerTest {

    private lateinit var authManager: AuthManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        authManager = AuthManager(context)
        authManager.testPrefs = context.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        authManager.testPrefs!!.edit().clear().commit()
    }

    @Test
    fun `isBiometricEnabled returns false by default`() {
        assertFalse(authManager.isBiometricEnabled())
    }

    @Test
    fun `setBiometricEnabled true then isBiometricEnabled returns true`() {
        authManager.setBiometricEnabled(true)
        assertTrue(authManager.isBiometricEnabled())
    }

    @Test
    fun `setBiometricEnabled false after true returns false`() {
        authManager.setBiometricEnabled(true)
        authManager.setBiometricEnabled(false)
        assertFalse(authManager.isBiometricEnabled())
    }

    @Test
    fun `isBiometricAvailable returns valid status on emulator`() {
        val status = authManager.isBiometricAvailable()
        assertTrue(
            status == AuthManager.BiometricStatus.NO_HARDWARE ||
                status == AuthManager.BiometricStatus.UNAVAILABLE
        )
    }

    @Test
    fun `isBiometricEnrolled returns false on emulator`() {
        assertFalse(authManager.isBiometricEnrolled())
    }
}
