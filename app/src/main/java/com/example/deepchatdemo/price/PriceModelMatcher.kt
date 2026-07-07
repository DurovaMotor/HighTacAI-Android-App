package com.example.deepchatdemo.price

import java.text.Normalizer
import java.util.Locale

internal object PriceModelMatcher {
    private val chunkSeparatorRegex = Regex("[,，/／;；、|\\n\\r]+")
    private val wordRegex = Regex("[\\p{L}\\p{N}]+")
    private val separatorRegex = Regex("[\\s\\-_/\\\\.,;:，。；：、()（）\\[\\]{}]+")

    fun matches(value: String, query: String): Boolean {
        val normalizedQuery = query.normalizeCompact()
        if (normalizedQuery.isBlank()) return false

        if (!normalizedQuery.isAsciiLettersOrDigits()) {
            return value.normalizeCompact().contains(normalizedQuery)
        }

        // Match model tokens: ST matches SYMPHONY ST and ST150, but not ESTATE.
        return value.split(chunkSeparatorRegex)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .any { chunk ->
                val chunkCompact = chunk.normalizeCompact()
                chunkCompact.matchesModelToken(normalizedQuery) ||
                    wordRegex.findAll(chunk.normalizeText())
                        .map { it.value.normalizeCompact() }
                        .any { token -> token.matchesModelToken(normalizedQuery) }
            }
    }

    private fun String.matchesModelToken(query: String): Boolean {
        if (isBlank()) return false
        if (this == query) return true
        if (!startsWith(query)) return false

        val suffix = drop(query.length)
        return suffix.isNotEmpty() && suffix.all { it.isDigit() }
    }

    private fun String.normalizeText(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
    }

    private fun String.normalizeCompact(): String {
        return normalizeText().replace(separatorRegex, "")
    }

    private fun String.isAsciiLettersOrDigits(): Boolean {
        return isNotBlank() && all { it in 'a'..'z' || it in '0'..'9' }
    }
}
