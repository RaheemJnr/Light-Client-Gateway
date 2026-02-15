package com.rjnr.pocketnode.ui.util

object Bip39WordList {
    val WORDS: List<String> = BIP39_ENGLISH_WORDS

    private val wordSet: Set<String> = WORDS.toHashSet()

    fun getSuggestions(prefix: String, limit: Int = 4): List<String> {
        if (prefix.length < 2) return emptyList()
        val lower = prefix.lowercase()
        return WORDS.filter { it.startsWith(lower) }.take(limit)
    }

    fun isValidWord(word: String): Boolean = wordSet.contains(word.lowercase())
}
