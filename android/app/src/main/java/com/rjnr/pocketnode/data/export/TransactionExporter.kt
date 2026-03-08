package com.rjnr.pocketnode.data.export

import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TransactionExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun exportToCsv(transactions: List<TransactionEntity>): String {
        val header = "Date,Transaction Hash,Direction,Amount (CKB),Fee (CKB),Status,Block Number,Confirmations"
        if (transactions.isEmpty()) return header

        val rows = transactions.map { tx ->
            val date = dateFormat.format(Date(tx.timestamp))
            val amount = formatShannons(tx.balanceChange)
            val fee = formatShannons(tx.fee)
            "$date,${tx.txHash},${tx.direction},$amount,$fee,${tx.status},${tx.blockNumber},${tx.confirmations}"
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun formatShannons(value: String): String {
        val shannons = value.toLongOrNull() ?: return value
        val whole = shannons / 100_000_000
        val fraction = (shannons % 100_000_000).let { if (it < 0) -it else it }
        return "%d.%08d".format(whole, fraction)
    }
}
