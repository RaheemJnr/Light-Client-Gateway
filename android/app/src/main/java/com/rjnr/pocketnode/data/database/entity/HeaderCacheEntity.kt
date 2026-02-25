package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView

@Entity(tableName = "header_cache")
data class HeaderCacheEntity(
    @PrimaryKey val blockHash: String,
    val number: String,
    val epoch: String,
    val timestamp: String,
    val dao: String,
    val network: String,
    val cachedAt: Long
) {
    companion object {
        fun from(header: JniHeaderView, network: String): HeaderCacheEntity =
            HeaderCacheEntity(
                blockHash = header.hash,
                number = header.number,
                epoch = header.epoch,
                timestamp = header.timestamp,
                dao = header.dao,
                network = network,
                cachedAt = System.currentTimeMillis()
            )
    }
}
