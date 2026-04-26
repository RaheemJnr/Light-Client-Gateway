package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity

@Entity(
    tableName = "sync_progress",
    primaryKeys = ["walletId", "network"]
)
data class SyncProgressEntity(
    val walletId: String,
    val network: String,                 // NetworkType.name (MAINNET / TESTNET)
    val lightStartBlockNumber: Long,     // block we passed to nativeSetScripts
    val localSavedBlockNumber: Long,     // last block fully processed
    val updatedAt: Long                  // epoch ms
)
