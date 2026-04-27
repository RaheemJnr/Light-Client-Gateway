package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Broadcast-state row for an in-flight or recently-failed transaction.
 *
 * Distinct from `transactions`: that table is the user-facing ledger
 * record (kept forever); this table is the ephemeral broadcast state
 * machine (deleted on terminal resolution, except FAILED which is kept
 * until user dismisses the retry CTA).
 *
 * State transitions (CAS in PendingBroadcastDao.compareAndUpdateState):
 *   BROADCASTING -> BROADCAST     (JNI returned the hash)
 *   BROADCASTING -> CONFIRMED     (watchdog saw on-chain)
 *   BROADCASTING -> FAILED        (JNI null/exception; or watchdog null x 3 + tip past +25)
 *   BROADCAST    -> CONFIRMED     (watchdog saw on-chain)
 *   BROADCAST    -> FAILED        (watchdog null x 3 + tip past +25)
 */
@Entity(
    tableName = "pending_broadcasts",
    indices = [
        Index(value = ["walletId", "network", "state"], name = "idx_pb_wallet_net_state")
    ]
)
data class PendingBroadcastEntity(
    @PrimaryKey val txHash: String,
    val walletId: String,
    val network: String,
    val signedTxJson: String,
    val reservedInputs: String,         // JSON-encoded List<OutPoint>
    val state: String,                  // BROADCASTING | BROADCAST | CONFIRMED | FAILED
    val submittedAtTipBlock: Long,
    val nullCount: Int,
    val createdAt: Long,
    val lastCheckedAt: Long
)
