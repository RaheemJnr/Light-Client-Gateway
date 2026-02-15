package com.rjnr.pocketnode.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.Blake2b
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PinManagerTest {

    private lateinit var pinManager: PinManager
    private lateinit var blake2b: Blake2b
    private var fakeTimeMs: Long = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        blake2b = Blake2b()
        pinManager = PinManager(context, blake2b)
        pinManager.testPrefs = context.getSharedPreferences("test_pin", Context.MODE_PRIVATE)
        pinManager.timeProvider = { fakeTimeMs }
        pinManager.testPrefs!!.edit().clear().commit()
    }

    // -- PIN set/verify --

    @Test
    fun `setPin and verifyPin with correct PIN returns true`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.verifyPin("123456"))
    }

    @Test
    fun `verifyPin with wrong PIN returns false`() {
        pinManager.setPin("123456")
        assertFalse(pinManager.verifyPin("654321"))
    }

    @Test
    fun `hasPin returns false initially`() {
        assertFalse(pinManager.hasPin())
    }

    @Test
    fun `hasPin returns true after setPin`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.hasPin())
    }

    @Test
    fun `removePin clears stored PIN`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.hasPin())
        pinManager.removePin()
        assertFalse(pinManager.hasPin())
    }

    @Test
    fun `verifyPin returns false when no PIN set`() {
        assertFalse(pinManager.verifyPin("123456"))
    }

    // -- PIN validation --

    @Test(expected = IllegalArgumentException::class)
    fun `setPin rejects PIN shorter than 6 digits`() {
        pinManager.setPin("12345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPin rejects PIN longer than 6 digits`() {
        pinManager.setPin("1234567")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPin rejects non-digit characters`() {
        pinManager.setPin("12345a")
    }

    // -- Hash consistency --

    @Test
    fun `same PIN always produces same hash`() {
        pinManager.setPin("111111")
        assertTrue(pinManager.verifyPin("111111"))
        assertTrue(pinManager.verifyPin("111111"))
        assertTrue(pinManager.verifyPin("111111"))
    }

    @Test
    fun `changing PIN updates hash`() {
        pinManager.setPin("123456")
        assertTrue(pinManager.verifyPin("123456"))
        pinManager.setPin("654321")
        assertFalse(pinManager.verifyPin("123456"))
        assertTrue(pinManager.verifyPin("654321"))
    }

    // -- Lockout cycle --

    @Test
    fun `getRemainingAttempts starts at MAX_ATTEMPTS`() {
        pinManager.setPin("123456")
        assertEquals(PinManager.MAX_ATTEMPTS, pinManager.getRemainingAttempts())
    }

    @Test
    fun `failed attempts decrement remaining attempts`() {
        pinManager.setPin("123456")
        pinManager.verifyPin("000000")
        assertEquals(PinManager.MAX_ATTEMPTS - 1, pinManager.getRemainingAttempts())
    }

    @Test
    fun `lockout triggers after MAX_ATTEMPTS failed attempts`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())
        assertEquals(0, pinManager.getRemainingAttempts())
    }

    @Test
    fun `verifyPin returns false during lockout even with correct PIN`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())
        assertFalse(pinManager.verifyPin("123456"))
    }

    @Test
    fun `lockout expires after LOCKOUT_DURATION_MS`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())

        fakeTimeMs += PinManager.LOCKOUT_DURATION_MS + 1
        assertFalse(pinManager.isLockedOut())
        assertEquals(PinManager.MAX_ATTEMPTS, pinManager.getRemainingAttempts())
    }

    @Test
    fun `getLockoutRemainingMs returns correct remaining time`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        val remaining = pinManager.getLockoutRemainingMs()
        assertTrue(remaining > 0)
        assertTrue(remaining <= PinManager.LOCKOUT_DURATION_MS)
    }

    @Test
    fun `getLockoutRemainingMs returns 0 when not locked out`() {
        pinManager.setPin("123456")
        assertEquals(0L, pinManager.getLockoutRemainingMs())
    }

    @Test
    fun `successful verification resets failed attempts`() {
        pinManager.setPin("123456")
        pinManager.verifyPin("000000")
        pinManager.verifyPin("000000")
        assertEquals(PinManager.MAX_ATTEMPTS - 2, pinManager.getRemainingAttempts())

        pinManager.verifyPin("123456")
        assertEquals(PinManager.MAX_ATTEMPTS, pinManager.getRemainingAttempts())
    }

    @Test
    fun `setPin resets failed attempts and lockout`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())

        pinManager.setPin("654321")
        assertFalse(pinManager.isLockedOut())
        assertEquals(PinManager.MAX_ATTEMPTS, pinManager.getRemainingAttempts())
        assertTrue(pinManager.verifyPin("654321"))
    }

    @Test
    fun `lockout state persists across PinManager instances`() {
        pinManager.setPin("123456")
        repeat(PinManager.MAX_ATTEMPTS) {
            pinManager.verifyPin("000000")
        }
        assertTrue(pinManager.isLockedOut())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val newPinManager = PinManager(context, blake2b)
        newPinManager.testPrefs = pinManager.testPrefs
        newPinManager.timeProvider = { fakeTimeMs }

        assertTrue(newPinManager.isLockedOut())
    }
}
