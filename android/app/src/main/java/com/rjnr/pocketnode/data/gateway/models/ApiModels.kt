package com.rjnr.pocketnode.data.gateway.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============ Sync Options ============

/**
 * Sync mode options for account registration
 */
enum class SyncMode {
    /** Sync from current tip - for new wallets, no historical transactions */
    NEW_WALLET,
    /** Sync recent history (~30 days / 200k blocks) - good default for most users */
    RECENT,
    /** Sync full history from genesis - slow but complete */
    FULL_HISTORY,
    /** Sync from a custom block height */
    CUSTOM
}

/**
 * CKB Mainnet checkpoint - approximately early Jan 2026.
 * Using block 18,300,000 to avoid syncing 18M blocks from genesis.
 */
const val HARDCODED_CHECKPOINT = 18_300_000L

/**
 * Helper to convert SyncMode to the from_block parameter value
 */
fun SyncMode.toFromBlock(customBlockHeight: Long? = null, tipHeight: Long = 0): String {
    val bestTip = if (tipHeight > 0) tipHeight else HARDCODED_CHECKPOINT
    
    return when (this) {
        SyncMode.NEW_WALLET -> bestTip.toString()
        SyncMode.RECENT -> {
            // CKB block time is ~10-15s. 30 days is about 200,000 blocks.
            // We use the best available tip (either real or checkpoint)
            val recentBlock = bestTip - 200_000
            if (recentBlock < 0) "0" else recentBlock.toString()
        }
        SyncMode.FULL_HISTORY -> "0"
        SyncMode.CUSTOM -> customBlockHeight?.toString() ?: "0"
    }
}

// ============ Requests ============

@Serializable
data class RegisterAccountRequest(
    val address: String? = null,
    val script: Script? = null,
    @SerialName("from_block") val fromBlock: String? = null
)

@Serializable
data class SendTransactionRequest(
    val transaction: Transaction
)

// ============ Responses ============

@Serializable
data class StatusResponse(
    val network: String,
    @SerialName("tip_number") val tipNumber: String,
    @SerialName("tip_hash") val tipHash: String,
    @SerialName("peer_count") val peerCount: Int,
    @SerialName("is_synced") val isSynced: Boolean,
    @SerialName("is_healthy") val isHealthy: Boolean
)

@Serializable
data class RegisterResponse(
    val ok: Boolean? = null,
    @SerialName("syncing_from") val syncingFrom: String? = null,
    // Error fields when registration fails
    val error: ErrorDetail? = null
)

@Serializable
data class AccountStatusResponse(
    val address: String,
    @SerialName("is_registered") val isRegistered: Boolean,
    @SerialName("tip_number") val tipNumber: String,
    @SerialName("synced_to_block") val syncedToBlock: String,
    @SerialName("sync_progress") val syncProgress: Double,
    @SerialName("is_synced") val isSynced: Boolean
)

@Serializable
data class BalanceResponse(
    val address: String,
    val capacity: String,
    @SerialName("capacity_ckb") val capacityCkb: String,
    @SerialName("as_of_block") val asOfBlock: String
) {
    fun capacityAsLong(): Long = capacity.removePrefix("0x").toLong(16)
    fun capacityAsCkb(): Double = capacityCkb.toDoubleOrNull() ?: 0.0
}

@Serializable
data class CellsResponse(
    val items: List<Cell>,
    @SerialName("next_cursor") val nextCursor: String?
)

@Serializable
data class TransactionsResponse(
    val items: List<TransactionRecord>,
    @SerialName("next_cursor") val nextCursor: String?
)

@Serializable
data class SendTransactionResponse(
    @SerialName("tx_hash") val txHash: String
)

@Serializable
data class TransactionStatusResponse(
    @SerialName("tx_hash") val txHash: String,
    val status: String, // "pending", "proposed", "committed", "unknown"
    val confirmations: Int? = null,
    @SerialName("block_number") val blockNumber: String? = null,
    @SerialName("block_hash") val blockHash: String? = null,
    val timestamp: Long? = null
) {
    fun isConfirmed(): Boolean = status == "committed" && (confirmations ?: 0) >= 1
    fun isPending(): Boolean = status == "pending" || status == "proposed"
    fun isUnknown(): Boolean = status == "unknown"
}

@Serializable
data class ApiError(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

// ============ JNI Response Models ============
// These match the JSON structure returned by the Rust JNI bridge

@Serializable
data class JniCell(
    val output: CellOutput,
    @SerialName("output_data") val outputData: String? = null,
    @SerialName("out_point") val outPoint: OutPoint,
    @SerialName("block_number") val blockNumber: String,
    @SerialName("tx_index") val txIndex: String
) {
    /**
     * Convert JNI cell format to the app's Cell format
     */
    fun toCell(): Cell = Cell(
        outPoint = outPoint,
        capacity = output.capacity,
        blockNumber = blockNumber,
        lock = output.lock,
        type = output.type,
        data = outputData ?: "0x"
    )
}
