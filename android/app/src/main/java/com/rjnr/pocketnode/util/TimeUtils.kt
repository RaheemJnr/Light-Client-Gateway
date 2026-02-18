package com.rjnr.pocketnode.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FMT_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val FMT_MONTH_DAY = DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault())
private val FMT_FULL = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())

/**
 * Formats a raw CKB block timestamp string into a human-readable date/time.
 *
 * CKB block timestamps are Unix milliseconds encoded as hex strings (e.g. "0x18c8d0a7a00").
 * Decimal millisecond strings are also accepted.
 *
 * Returns "—" for null, empty, zero, or unparseable input.
 * Returns HH:mm for today's transactions, MMM dd for this year, MMM dd, yyyy otherwise.
 */
fun formatBlockTimestamp(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "0x0" || raw == "0") return "—"
    return try {
        val millis = if (raw.startsWith("0x") || raw.startsWith("0X")) {
            raw.removePrefix("0x").removePrefix("0X").toLong(16)
        } else {
            raw.toLong()
        }
        if (millis <= 0) return "—"
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        val today = LocalDate.now(ZoneId.systemDefault())
        when {
            dt.toLocalDate() == today -> dt.format(FMT_TIME)
            dt.year == today.year -> dt.format(FMT_MONTH_DAY)
            else -> dt.format(FMT_FULL)
        }
    } catch (e: Exception) {
        "—"
    }
}
