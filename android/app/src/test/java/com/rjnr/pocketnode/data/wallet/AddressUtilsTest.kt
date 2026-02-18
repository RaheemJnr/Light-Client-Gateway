package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import org.junit.Assert.*
import org.junit.Test

class AddressUtilsTest {

    // Standard secp256k1-blake160 lock script params from CLAUDE.md
    private val testScript = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + "aa".repeat(20) // 20-byte placeholder args
    )

    @Test
    fun `encode mainnet address starts with ckb1`() {
        val address = AddressUtils.encode(testScript, NetworkType.MAINNET)
        assertTrue("Mainnet address must start with ckb1, got: $address", address.startsWith("ckb1"))
    }

    @Test
    fun `encode testnet address starts with ckt1`() {
        val address = AddressUtils.encode(testScript, NetworkType.TESTNET)
        assertTrue("Testnet address must start with ckt1, got: $address", address.startsWith("ckt1"))
    }

    @Test
    fun `getNetwork returns MAINNET for ckb1 address`() {
        val address = AddressUtils.encode(testScript, NetworkType.MAINNET)
        assertEquals(NetworkType.MAINNET, AddressUtils.getNetwork(address))
    }

    @Test
    fun `getNetwork returns TESTNET for ckt1 address`() {
        val address = AddressUtils.encode(testScript, NetworkType.TESTNET)
        assertEquals(NetworkType.TESTNET, AddressUtils.getNetwork(address))
    }
}
