package com.rjnr.pocketnode.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey val walletId: String,
    val name: String,
    val type: String,                    // "mnemonic" | "raw_key"
    val derivationPath: String?,         // BIP44 path, null for raw_key
    val parentWalletId: String?,         // non-null for HD sub-accounts
    @ColumnInfo(defaultValue = "0") val accountIndex: Int,               // BIP44 account index (0 for primary)
    @ColumnInfo(defaultValue = "") val mainnetAddress: String,
    @ColumnInfo(defaultValue = "") val testnetAddress: String,
    @ColumnInfo(defaultValue = "0") val isActive: Boolean,
    @ColumnInfo(defaultValue = "0") val createdAt: Long
)
