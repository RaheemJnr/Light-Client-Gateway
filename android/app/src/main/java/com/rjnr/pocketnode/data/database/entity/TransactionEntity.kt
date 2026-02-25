package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val txHash: String,
    val blockNumber: String,
    val blockHash: String,
    val timestamp: Long,
    val balanceChange: String,
    val direction: String,
    val fee: String,
    val confirmations: Int,
    val blockTimestampHex: String?,
    val network: String,
    val status: String,       // "PENDING", "CONFIRMED", "FAILED"
    val isLocal: Boolean,     // true = broadcast but not yet synced
    val cachedAt: Long
) {
    fun toTransactionRecord(): TransactionRecord = TransactionRecord(
        txHash = txHash,
        blockNumber = blockNumber,
        blockHash = blockHash,
        timestamp = timestamp,
        balanceChange = balanceChange,
        direction = direction,
        fee = fee,
        confirmations = confirmations,
        blockTimestampHex = blockTimestampHex
    )

    companion object {
        fun fromTransactionRecord(
            txHash: String,
            blockNumber: String,
            blockHash: String,
            timestamp: Long,
            balanceChange: String,
            direction: String,
            fee: String,
            confirmations: Int,
            blockTimestampHex: String?,
            network: String
        ): TransactionEntity = TransactionEntity(
            txHash = txHash,
            blockNumber = blockNumber,
            blockHash = blockHash,
            timestamp = timestamp,
            balanceChange = balanceChange,
            direction = direction,
            fee = fee,
            confirmations = confirmations,
            blockTimestampHex = blockTimestampHex,
            network = network,
            status = if (confirmations > 0) "CONFIRMED" else "PENDING",
            isLocal = false,
            cachedAt = System.currentTimeMillis()
        )
    }
}
