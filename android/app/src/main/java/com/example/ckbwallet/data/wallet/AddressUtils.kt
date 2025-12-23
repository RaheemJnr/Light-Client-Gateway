package com.example.ckbwallet.data.wallet

import com.example.ckbwallet.data.gateway.models.NetworkType
import com.example.ckbwallet.data.gateway.models.Script
import org.nervos.ckb.Network
import org.nervos.ckb.utils.address.Address

/**
 * Address utilities using official CKB SDK implementation.
 */
object AddressUtils {

    /**
     * Parses a CKB address string into a Script object.
     * Returns null if the address is invalid.
     */
    fun parseAddress(address: String): Script? {
        return try {
            val decoded = Address.decode(address)
            val script = decoded.script
            Script(
                codeHash = "0x" + script.codeHash.joinToString("") { "%02x".format(it) },
                hashType = when (script.hashType) {
                    org.nervos.ckb.type.Script.HashType.TYPE -> "type"
                    org.nervos.ckb.type.Script.HashType.DATA -> "data"
                    org.nervos.ckb.type.Script.HashType.DATA1 -> "data1"
                    org.nervos.ckb.type.Script.HashType.DATA2 -> "data2"
                },
                args = "0x" + script.args.joinToString("") { "%02x".format(it) }
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encode a Script to a CKB address string.
     */
    fun encode(script: Script, network: NetworkType): String {
        val ckbNetwork = when (network) {
            NetworkType.TESTNET -> Network.TESTNET
            NetworkType.MAINNET -> Network.MAINNET
        }

        val codeHashBytes = script.codeHash.removePrefix("0x").hexToBytes()
        val argsBytes = script.args.removePrefix("0x").hexToBytes()
        val hashType = when (script.hashType) {
            "type" -> org.nervos.ckb.type.Script.HashType.TYPE
            "data" -> org.nervos.ckb.type.Script.HashType.DATA
            "data1" -> org.nervos.ckb.type.Script.HashType.DATA1
            "data2" -> org.nervos.ckb.type.Script.HashType.DATA2
            else -> org.nervos.ckb.type.Script.HashType.TYPE
        }

        val ckbScript = org.nervos.ckb.type.Script(codeHashBytes, argsBytes, hashType)
        val address = Address(ckbScript, ckbNetwork)
        return address.encode()
    }

    /**
     * Decode a CKB address to extract the Script.
     */
    fun decode(address: String): Script {
        val decoded = Address.decode(address)
        val script = decoded.script
        return Script(
            codeHash = "0x" + script.codeHash.joinToString("") { "%02x".format(it) },
            hashType = when (script.hashType) {
                org.nervos.ckb.type.Script.HashType.TYPE -> "type"
                org.nervos.ckb.type.Script.HashType.DATA -> "data"
                org.nervos.ckb.type.Script.HashType.DATA1 -> "data1"
                org.nervos.ckb.type.Script.HashType.DATA2 -> "data2"
            },
            args = "0x" + script.args.joinToString("") { "%02x".format(it) }
        )
    }

    /**
     * Validate if an address string is valid.
     */
    fun isValid(address: String): Boolean {
        return try {
            Address.decode(address)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the network type from an address.
     */
    fun getNetwork(address: String): NetworkType? {
        return try {
            val decoded = Address.decode(address)
            when (decoded.network) {
                Network.TESTNET -> NetworkType.TESTNET
                Network.MAINNET -> NetworkType.MAINNET
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun String.hexToBytes(): ByteArray {
        val hex = this.removePrefix("0x")
        if (hex.isEmpty()) return byteArrayOf()
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
