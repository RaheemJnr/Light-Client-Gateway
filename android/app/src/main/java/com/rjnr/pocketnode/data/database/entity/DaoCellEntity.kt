package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity

@Entity(tableName = "dao_cells", primaryKeys = ["txHash", "index"])
data class DaoCellEntity(
    val txHash: String,
    val index: String,
    val capacity: Long,
    val status: String,
    val depositBlockNumber: Long,
    val depositBlockHash: String,
    val depositEpochHex: String?,
    val withdrawBlockNumber: Long?,
    val withdrawBlockHash: String?,
    val withdrawEpochHex: String?,
    val compensation: Long,
    val unlockEpochHex: String?,
    val depositTimestamp: Long,
    val network: String,
    val lastUpdatedAt: Long
)
