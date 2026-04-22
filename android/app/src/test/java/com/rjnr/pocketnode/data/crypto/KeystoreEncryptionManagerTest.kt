package com.rjnr.pocketnode.data.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeystoreEncryptionManagerTest {

    private lateinit var manager: KeystoreEncryptionManager

    @Before
    fun setUp() {
        manager = KeystoreEncryptionManager.createForTest()
    }

    @Test
    fun `encrypt and decrypt round-trip`() {
        val plaintext = "hello world private key".toByteArray()
        val (ciphertext, iv) = manager.encrypt(plaintext)

        assertFalse(ciphertext.contentEquals(plaintext))
        assertEquals(12, iv.size)

        val decrypted = manager.decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertext each time`() {
        val plaintext = "same input".toByteArray()
        val (ct1, _) = manager.encrypt(plaintext)
        val (ct2, _) = manager.encrypt(plaintext)

        assertFalse(ct1.contentEquals(ct2))
    }

    @Test
    fun `decrypt with wrong IV fails`() {
        val plaintext = "secret".toByteArray()
        val (ciphertext, _) = manager.encrypt(plaintext)
        val wrongIv = ByteArray(12) { 0xFF.toByte() }

        try {
            manager.decrypt(ciphertext, wrongIv)
            fail("Should throw on wrong IV")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun `encrypt empty data works`() {
        val plaintext = ByteArray(0)
        val (ciphertext, iv) = manager.encrypt(plaintext)
        val decrypted = manager.decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt large data works`() {
        val plaintext = ByteArray(10_000) { (it % 256).toByte() }
        val (ciphertext, iv) = manager.encrypt(plaintext)
        val decrypted = manager.decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }
}
