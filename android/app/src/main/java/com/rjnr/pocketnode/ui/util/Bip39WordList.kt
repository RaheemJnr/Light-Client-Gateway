package com.rjnr.pocketnode.ui.util

object Bip39WordList {
    val WORDS: List<String> = BIP39_ENGLISH_WORDS

    private val wordSet: Set<String> = WORDS.toHashSet()

    /**
     * Provide up to `limit` BIP39 English words that begin with the given `prefix`.
     *
     * If `prefix` has fewer than 2 characters, an empty list is returned. Matching is case-insensitive.
     *
     * @param prefix The prefix to match against words (case-insensitive).
     * @param limit The maximum number of suggestions to return.
     * @return A list of matching words, at most `limit` entries; empty if no matches or `prefix` length < 2.
     */
    fun getSuggestions(prefix: String, limit: Int = 4): List<String> {
        if (prefix.length < 2) return emptyList()
        val lower = prefix.lowercase()
        return WORDS.filter { it.startsWith(lower) }.take(limit)
    }

    /**
 * Checks whether a word is a valid BIP-39 English mnemonic word.
 *
 * @param word The word to validate; comparison is case-insensitive.
 * @return `true` if the word is contained in the BIP-39 English word list, `false` otherwise.
 */
fun isValidWord(word: String): Boolean = wordSet.contains(word.lowercase())
}