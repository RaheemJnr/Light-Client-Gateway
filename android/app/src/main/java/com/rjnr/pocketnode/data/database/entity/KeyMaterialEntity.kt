package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "key_material")
data class KeyMaterialEntity(
    @PrimaryKey val walletId: String,
    val encryptedPrivateKey: ByteArray,
    val encryptedMnemonic: ByteArray?,
    val iv: ByteArray,
    val walletType: String,
    val mnemonicBackedUp: Boolean,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyMaterialEntity) return false
        val mnemonicEqual = when {
            encryptedMnemonic == null && other.encryptedMnemonic == null -> true
            encryptedMnemonic == null || other.encryptedMnemonic == null -> false
            else -> encryptedMnemonic.contentEquals(other.encryptedMnemonic)
        }
        return walletId == other.walletId &&
            encryptedPrivateKey.contentEquals(other.encryptedPrivateKey) &&
            mnemonicEqual &&
            iv.contentEquals(other.iv) &&
            walletType == other.walletType &&
            mnemonicBackedUp == other.mnemonicBackedUp &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = walletId.hashCode()
        result = 31 * result + encryptedPrivateKey.contentHashCode()
        result = 31 * result + (encryptedMnemonic?.contentHashCode() ?: 0)
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + walletType.hashCode()
        result = 31 * result + mnemonicBackedUp.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
