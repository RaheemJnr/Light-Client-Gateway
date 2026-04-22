package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse

@Entity(
    tableName = "balance_cache",
    primaryKeys = ["walletId", "network"]
)
data class BalanceCacheEntity(
    val walletId: String,
    val network: String,
    val address: String,
    val capacity: String,
    val capacityCkb: String,
    val blockNumber: String,
    val cachedAt: Long
) {
    fun toBalanceResponse(): BalanceResponse = BalanceResponse(
        address = address,
        capacity = capacity,
        capacityCkb = capacityCkb,
        asOfBlock = blockNumber
    )

    fun isFresh(ttlMs: Long = 120_000L): Boolean =
        System.currentTimeMillis() - cachedAt < ttlMs

    companion object {
        fun from(response: BalanceResponse, network: String, walletId: String): BalanceCacheEntity =
            BalanceCacheEntity(
                walletId = walletId,
                network = network,
                address = response.address,
                capacity = response.capacity,
                capacityCkb = response.capacityCkb,
                blockNumber = response.asOfBlock,
                cachedAt = System.currentTimeMillis()
            )
    }
}
