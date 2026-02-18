package com.rjnr.pocketnode.util

private val CKB_ADDRESS_REGEX = Regex("(ck[bt]1[a-z0-9]+)")

/**
 * Extracts a CKB address from a raw scanned QR value.
 * Handles: plain ckb1/ckt1 addresses, joyid:// URIs, HTTPS URLs with embedded address.
 * Returns null if no valid CKB address found.
 */
fun extractCkbAddress(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()

    // 1. Plain address
    if (trimmed.startsWith("ckb1") || trimmed.startsWith("ckt1")) return trimmed

    // 2. joyid:// URI scheme
    if (trimmed.startsWith("joyid://")) {
        val after = trimmed.removePrefix("joyid://")
        if (after.startsWith("ckb1") || after.startsWith("ckt1")) return after
    }

    // 3. Extract any ckb1/ckt1 address embedded in a URL (path or query param)
    return CKB_ADDRESS_REGEX.find(trimmed)?.value
}
