package com.oconeco.spring_search_tempo.base.util

/**
 * Sanitizes search-result snippets for safe rendering with `th:utext`.
 *
 * Search snippets are produced by PostgreSQL's `ts_headline` (FTS) or by
 * [highlight] (semantic). Both intentionally wrap matched terms in `<mark>` /
 * `</mark>` so the template can highlight them. Everything else in the
 * snippet originated from user-controlled content (file body text, email
 * bodies, chunk text) and must be HTML-escaped before reaching the browser.
 *
 * This sanitizer enforces a strict whitelist: only the literal tokens
 * `<mark>` and `</mark>` (case-insensitive) pass through; all other markup,
 * including stray `<` / `>` / `&` / quotes from the source text, is escaped.
 */
object SnippetSanitizer {

    private val MARK_TAG = Regex("</?mark>", RegexOption.IGNORE_CASE)

    /**
     * HTML-escape [raw], preserving `<mark>` / `</mark>` markers. Returns
     * `null` for `null` input so callers can pass nullable snippet columns
     * straight through.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null

        val builder = StringBuilder(raw.length + 16)
        var cursor = 0
        for (match in MARK_TAG.findAll(raw)) {
            if (match.range.first > cursor) {
                builder.append(escapeHtml(raw.substring(cursor, match.range.first)))
            }
            // Normalize to lowercase so downstream CSS only has to target one form.
            builder.append(if (match.value.startsWith("</", ignoreCase = true)) "</mark>" else "<mark>")
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            builder.append(escapeHtml(raw.substring(cursor)))
        }
        return builder.toString()
    }

    /**
     * Wrap occurrences of [terms] in [text] with `<mark>` / `</mark>` for
     * display in result lists where no FTS `ts_headline` snippet is
     * available (e.g., semantic-only matches).
     *
     * Trimmed to at most [maxWords] words around the first match — gives a
     * concise preview without dragging the full chunk text into the page.
     * The caller is expected to pass the returned string through [sanitize]
     * before rendering with `th:utext`.
     */
    fun highlight(text: String?, terms: Collection<String>, maxWords: Int = 35): String? {
        if (text.isNullOrBlank()) return text
        val cleaned = terms.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (cleaned.isEmpty()) return abbreviateByWords(text, maxWords)

        // Build a single alternation regex; quote each term so user input
        // (semantic queries are free-form natural language) can't break the
        // pattern. Case-insensitive so "Kotlin" highlights "kotlin" too.
        val pattern = cleaned.joinToString("|") { Regex.escape(it) }
        val regex = Regex("(?i)\\b($pattern)\\b")

        // Window the text around the first match so the snippet stays short.
        val windowed = windowAroundFirstMatch(text, regex, maxWords)
        return regex.replace(windowed) { match -> "<mark>${match.value}</mark>" }
    }

    private fun windowAroundFirstMatch(text: String, regex: Regex, maxWords: Int): String {
        val first = regex.find(text) ?: return abbreviateByWords(text, maxWords)
        val words = text.split(WHITESPACE)
        if (words.size <= maxWords) return text

        // Find the word index that contains the first match by counting
        // characters up to each word boundary.
        var charSoFar = 0
        var matchWordIdx = 0
        for ((i, w) in words.withIndex()) {
            val nextChar = charSoFar + w.length
            if (first.range.first in charSoFar..nextChar) {
                matchWordIdx = i
                break
            }
            charSoFar = nextChar + 1 // +1 for the joining space
        }
        val half = maxWords / 2
        val start = (matchWordIdx - half).coerceAtLeast(0)
        val end = (start + maxWords).coerceAtMost(words.size)
        val slice = words.subList(start, end).joinToString(" ")
        val prefix = if (start > 0) "... " else ""
        val suffix = if (end < words.size) " ..." else ""
        return "$prefix$slice$suffix"
    }

    private fun abbreviateByWords(text: String, maxWords: Int): String {
        val words = text.split(WHITESPACE)
        if (words.size <= maxWords) return text
        return words.subList(0, maxWords).joinToString(" ") + " ..."
    }

    private fun escapeHtml(s: String): String {
        val builder = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(c)
            }
        }
        return builder.toString()
    }

    private val WHITESPACE = Regex("\\s+")
}
