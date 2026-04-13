package com.rjnr.pocketnode.ui.screens.recovery

import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecoveryViewModelTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val correctPin = "123456".toCharArray()
    private val wrongPin = "999999".toCharArray()

    private fun testMaterial(walletId: String = "wallet1") = KeyMaterial(
        privateKey = "a".repeat(64),
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        walletType = "mnemonic",
        mnemonicBackedUp = true,
        createdAt = "2026-01-01T00:00:00Z"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createBackupManager(): KeyBackupManager {
        return KeyBackupManager(tempDir.root).also {
            it.kdfIterations = 1_000
        }
    }

    @Test
    fun `initial state is PinEntry when backups exist`() {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager)

        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertEquals(0, vm.uiState.value.failedAttempts)
        assertTrue(vm.uiState.value.recoveredWallets.isEmpty())
    }

    @Test
    fun `initial state is MnemonicEntry when no backups exist`() {
        val manager = createBackupManager()

        val vm = RecoveryViewModel(manager)

        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
    }

    @Test
    fun `attemptPinRecovery succeeds with correct PIN`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager)
        vm.attemptPinRecovery(correctPin)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.recoveredWallets.size)
        assertEquals("wallet1", vm.uiState.value.recoveredWallets[0].walletId)
        assertEquals("a".repeat(64), vm.uiState.value.recoveredWallets[0].material.privateKey)
        assertTrue(vm.uiState.value.failedWalletIds.isEmpty())
    }

    @Test
    fun `attemptPinRecovery fails with wrong PIN and tracks attempts`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager)
        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()

        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.failedAttempts)
        assertEquals("Incorrect PIN. 2 attempts remaining.", vm.uiState.value.error)
    }

    @Test
    fun `3 failed PIN attempts transitions to MnemonicEntry`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager)

        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.failedAttempts)
        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)

        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.failedAttempts)
        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)

        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.failedAttempts)
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
        assertEquals("Too many failed attempts. Please enter your recovery phrase.", vm.uiState.value.error)
    }

    @Test
    fun `clearError sets error to null`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager)
        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()

        // Error should be set
        assertTrue(vm.uiState.value.error != null)

        vm.clearError()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun `partial recovery reports both recovered and failed wallets`() = runTest {
        val manager = createBackupManager()
        val pin1 = "111111".toCharArray()
        val pin2 = "222222".toCharArray()
        // wallet1 encrypted with pin1, wallet2 encrypted with pin2
        manager.writeBackup("wallet1", testMaterial("wallet1"), pin1)
        manager.writeBackup("wallet2", testMaterial("wallet2"), pin2)

        val vm = RecoveryViewModel(manager)
        // Try with pin1 — wallet1 succeeds, wallet2 fails
        vm.attemptPinRecovery(pin1)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.recoveredWallets.size)
        assertEquals("wallet1", vm.uiState.value.recoveredWallets[0].walletId)
        assertEquals(1, vm.uiState.value.failedWalletIds.size)
        assertEquals("wallet2", vm.uiState.value.failedWalletIds[0])
    }
}
