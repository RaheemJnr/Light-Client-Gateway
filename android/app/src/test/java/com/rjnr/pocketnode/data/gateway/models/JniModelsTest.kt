package com.rjnr.pocketnode.data.gateway.models

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class JniModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- JniPagination ---

    @Test
    fun `JniPagination deserializes with objects and cursor`() {
        val input = """{"objects":["a","b","c"],"last_cursor":"cursor123"}"""
        val result = json.decodeFromString<JniPagination<String>>(input)
        assertEquals(listOf("a", "b", "c"), result.objects)
        assertEquals("cursor123", result.lastCursor)
    }

    @Test
    fun `JniPagination deserializes with null cursor`() {
        val input = """{"objects":[],"last_cursor":null}"""
        val result = json.decodeFromString<JniPagination<String>>(input)
        assertTrue(result.objects.isEmpty())
        assertNull(result.lastCursor)
    }

    // --- JniCellsCapacity ---

    @Test
    fun `JniCellsCapacity deserializes`() {
        val input = """{"capacity":"0x174876e800","block_number":"0x100","block_hash":"0xabc"}"""
        val result = json.decodeFromString<JniCellsCapacity>(input)
        assertEquals("0x174876e800", result.capacity)
        assertEquals("0x100", result.blockNumber)
        assertEquals("0xabc", result.blockHash)
    }

    // --- JniTransactionView ---

    @Test
    fun `JniTransactionView deserializes full transaction`() {
        val input = """{
            "hash":"0xtxhash",
            "version":"0x0",
            "cell_deps":[{"out_point":{"tx_hash":"0xdep","index":"0x0"},"dep_type":"dep_group"}],
            "header_deps":["0xheader1"],
            "inputs":[{"since":"0x0","previous_output":{"tx_hash":"0xinput","index":"0x0"}}],
            "outputs":[{"capacity":"0x174876e800","lock":{"code_hash":"0xcode","hash_type":"type","args":"0xargs"},"type":null}],
            "outputs_data":["0x"],
            "witnesses":["0xwitness"]
        }"""
        val result = json.decodeFromString<JniTransactionView>(input)
        assertEquals("0xtxhash", result.hash)
        assertEquals("0x0", result.version)
        assertEquals(1, result.cellDeps.size)
        assertEquals("dep_group", result.cellDeps[0].depType)
        assertEquals(1, result.headerDeps.size)
        assertEquals(1, result.inputs.size)
        assertEquals("0xinput", result.inputs[0].previousOutput.txHash)
        assertEquals(1, result.outputs.size)
        assertEquals("0x174876e800", result.outputs[0].capacity)
        assertNull(result.outputs[0].type)
        assertEquals(listOf("0x"), result.outputsData)
        assertEquals(listOf("0xwitness"), result.witnesses)
    }

    // --- JniTxWithCell ---

    @Test
    fun `JniTxWithCell deserializes with io metadata`() {
        val input = """{
            "transaction":{
                "hash":"0xh","version":"0x0","cell_deps":[],"header_deps":[],
                "inputs":[],"outputs":[],"outputs_data":[],"witnesses":[]
            },
            "block_number":"0x100",
            "tx_index":"0x1",
            "io_index":"0x0",
            "io_type":"output",
            "io_capacity":"0x174876e800"
        }"""
        val result = json.decodeFromString<JniTxWithCell>(input)
        assertEquals("0xh", result.transaction.hash)
        assertEquals("0x100", result.blockNumber)
        assertEquals("0x1", result.txIndex)
        assertEquals("output", result.ioType)
        assertEquals("0x174876e800", result.ioCapacity)
    }

    // --- JniTxStatus ---

    @Test
    fun `JniTxStatus deserializes committed status`() {
        val input = """{"status":"committed","block_hash":"0xblock"}"""
        val result = json.decodeFromString<JniTxStatus>(input)
        assertEquals("committed", result.status)
        assertEquals("0xblock", result.blockHash)
    }

    @Test
    fun `JniTxStatus deserializes pending status with null block_hash`() {
        val input = """{"status":"pending"}"""
        val result = json.decodeFromString<JniTxStatus>(input)
        assertEquals("pending", result.status)
        assertNull(result.blockHash)
    }

    // --- JniTransactionWithStatus ---

    @Test
    fun `JniTransactionWithStatus deserializes committed tx`() {
        val input = """{
            "transaction":{
                "hash":"0xh","version":"0x0","cell_deps":[],"header_deps":[],
                "inputs":[],"outputs":[],"outputs_data":[],"witnesses":[]
            },
            "cycles":"0x100",
            "tx_status":{"status":"committed","block_hash":"0xblock"}
        }"""
        val result = json.decodeFromString<JniTransactionWithStatus>(input)
        assertNotNull(result.transaction)
        assertEquals("0x100", result.cycles)
        assertEquals("committed", result.txStatus.status)
    }

    @Test
    fun `JniTransactionWithStatus deserializes unknown status without transaction`() {
        val input = """{"tx_status":{"status":"unknown"}}"""
        val result = json.decodeFromString<JniTransactionWithStatus>(input)
        assertNull(result.transaction)
        assertNull(result.cycles)
        assertEquals("unknown", result.txStatus.status)
    }

    // --- JniHeaderView ---

    @Test
    fun `JniHeaderView deserializes full header`() {
        val input = """{
            "hash":"0xhash","number":"0x100","epoch":"0x7080291000032",
            "timestamp":"0x18c8d0a7a00","parent_hash":"0xparent",
            "transactions_root":"0xtxroot","proposals_hash":"0xprop",
            "extra_hash":"0xextra","dao":"0xdaofield","nonce":"0xnonce"
        }"""
        val result = json.decodeFromString<JniHeaderView>(input)
        assertEquals("0xhash", result.hash)
        assertEquals("0x100", result.number)
        assertEquals("0x7080291000032", result.epoch)
        assertEquals("0x18c8d0a7a00", result.timestamp)
        assertEquals("0xparent", result.parentHash)
        assertEquals("0xtxroot", result.transactionsRoot)
        assertEquals("0xprop", result.proposalsHash)
        assertEquals("0xextra", result.extraHash)
        assertEquals("0xdaofield", result.dao)
        assertEquals("0xnonce", result.nonce)
    }

    // --- JniFetchHeaderResponse ---

    @Test
    fun `JniFetchHeaderResponse deserializes Fetched with data`() {
        val input = """{
            "status":"fetched",
            "data":{
                "hash":"0xh","number":"0x1","epoch":"0x1","timestamp":"0x1",
                "parent_hash":"0xp","transactions_root":"0xt","proposals_hash":"0xp",
                "extra_hash":"0xe","dao":"0xd","nonce":"0xn"
            }
        }"""
        val result = json.decodeFromString<JniFetchHeaderResponse>(input)
        assertEquals("fetched", result.status)
        assertNotNull(result.data)
        assertEquals("0xh", result.data!!.hash)
    }

    @Test
    fun `JniFetchHeaderResponse deserializes Added without data`() {
        val input = """{"status":"added"}"""
        val result = json.decodeFromString<JniFetchHeaderResponse>(input)
        assertEquals("added", result.status)
        assertNull(result.data)
    }

    @Test
    fun `JniFetchHeaderResponse deserializes NotFound`() {
        val input = """{"status":"not_found"}"""
        val result = json.decodeFromString<JniFetchHeaderResponse>(input)
        assertEquals("not_found", result.status)
        assertNull(result.data)
    }

    // --- JniFetchTransactionResponse ---

    @Test
    fun `JniFetchTransactionResponse deserializes Fetched with full data`() {
        val input = """{
            "status":"fetched",
            "data":{
                "transaction":{
                    "hash":"0xh","version":"0x0","cell_deps":[],"header_deps":[],
                    "inputs":[],"outputs":[],"outputs_data":[],"witnesses":[]
                },
                "tx_status":{"status":"committed","block_hash":"0xb"}
            },
            "timestamp":"0x18c8d0a7a00",
            "first_sent":"0x18c8d0a7000"
        }"""
        val result = json.decodeFromString<JniFetchTransactionResponse>(input)
        assertEquals("fetched", result.status)
        assertNotNull(result.data)
        assertEquals("committed", result.data!!.txStatus.status)
        assertEquals("0x18c8d0a7a00", result.timestamp)
        assertEquals("0x18c8d0a7000", result.firstSent)
    }

    @Test
    fun `JniFetchTransactionResponse deserializes Fetching without data`() {
        val input = """{"status":"fetching"}"""
        val result = json.decodeFromString<JniFetchTransactionResponse>(input)
        assertEquals("fetching", result.status)
        assertNull(result.data)
    }

    @Test
    fun `JniFetchTransactionResponse deserializes Added`() {
        val input = """{"status":"added"}"""
        val result = json.decodeFromString<JniFetchTransactionResponse>(input)
        assertEquals("added", result.status)
    }

    @Test
    fun `JniFetchTransactionResponse deserializes NotFound`() {
        val input = """{"status":"not_found"}"""
        val result = json.decodeFromString<JniFetchTransactionResponse>(input)
        assertEquals("not_found", result.status)
    }

    // --- JniScriptStatus ---

    @Test
    fun `JniScriptStatus deserializes with defaults`() {
        val input = """{
            "script":{"code_hash":"0xcode","hash_type":"type","args":"0xargs"},
            "block_number":"0x100"
        }"""
        val result = json.decodeFromString<JniScriptStatus>(input)
        assertEquals("0xcode", result.script.codeHash)
        assertEquals("lock", result.scriptType)
        assertEquals("0x100", result.blockNumber)
    }

    // --- JniSearchKey ---

    @Test
    fun `JniSearchKey deserializes with filter`() {
        val input = """{
            "script":{"code_hash":"0xcode","hash_type":"type","args":"0xargs"},
            "script_type":"lock",
            "filter":{"block_range":["0x0","0x100"]},
            "with_data":false
        }"""
        val result = json.decodeFromString<JniSearchKey>(input)
        assertEquals("lock", result.scriptType)
        assertNotNull(result.filter)
        assertEquals(listOf("0x0", "0x100"), result.filter!!.blockRange)
        assertFalse(result.withData)
    }

    @Test
    fun `JniSearchKey deserializes with defaults`() {
        val input = """{"script":{"code_hash":"0xc","hash_type":"type","args":"0xa"}}"""
        val result = json.decodeFromString<JniSearchKey>(input)
        assertEquals("lock", result.scriptType)
        assertNull(result.filter)
        assertTrue(result.withData)
    }

    // --- JniSearchKeyFilter ---

    @Test
    fun `JniSearchKeyFilter deserializes all fields`() {
        val input = """{
            "script":{"code_hash":"0xc","hash_type":"type","args":"0xa"},
            "script_len_range":["0x0","0x100"],
            "output_data_len_range":["0x0","0x10"],
            "block_range":["0x0","0xfff"]
        }"""
        val result = json.decodeFromString<JniSearchKeyFilter>(input)
        assertNotNull(result.script)
        assertEquals(2, result.scriptLenRange!!.size)
        assertEquals(2, result.outputDataLenRange!!.size)
        assertEquals(2, result.blockRange!!.size)
    }

    @Test
    fun `JniSearchKeyFilter deserializes with all nulls`() {
        val input = """{}"""
        val result = json.decodeFromString<JniSearchKeyFilter>(input)
        assertNull(result.script)
        assertNull(result.scriptLenRange)
        assertNull(result.outputDataLenRange)
        assertNull(result.blockRange)
    }

    // --- JniLocalNode ---

    @Test
    fun `JniLocalNode deserializes`() {
        val input = """{"version":"0.116.0","node_id":"QmNodeId123","active":true}"""
        val result = json.decodeFromString<JniLocalNode>(input)
        assertEquals("0.116.0", result.version)
        assertEquals("QmNodeId123", result.nodeId)
        assertTrue(result.active)
    }

    // --- JniRemoteNode ---

    @Test
    fun `JniRemoteNode deserializes`() {
        val input = """{"version":"0.116.0","node_id":"QmRemote","connected_duration":"0x3e8"}"""
        val result = json.decodeFromString<JniRemoteNode>(input)
        assertEquals("0.116.0", result.version)
        assertEquals("QmRemote", result.nodeId)
        assertEquals("0x3e8", result.connectedDuration)
    }
}
