package com.oconeco.spring_search_tempo.base.service

/**
 * Extracts `tag:foo` and `folder:bar` filter tokens from a free-form search
 * query string.
 *
 * The parser strips these tokens out of the residual query that gets passed
 * to PostgreSQL's tsquery parser — feeding `tag:` or `folder:` directly into
 * `to_tsquery` would either error out (on the colon) or, worse, silently
 * match against the unrelated word "tag"/"folder".
 *
 * Supported forms:
 * - `tag:foo`              → tagFilters = ["foo"]
 * - `tag:"foo bar"`        → tagFilters = ["foo bar"]
 * - `folder:Dev/Kotlin`    → folderFilter = "Dev/Kotlin"
 * - `folder:"Bookmarks Toolbar/Dev"` → folderFilter = "Bookmarks Toolbar/Dev"
 *
 * Multiple `tag:` tokens are combined (OR semantics — match any). Multiple
 * `folder:` tokens are combined into a single substring (last one wins) since
 * a bookmark lives in exactly one folder path.
 */
object BookmarkQueryParser {

    private val TOKEN_REGEX = Regex(
        """(?<key>tag|folder):(?:"(?<quoted>[^"]*)"|(?<bare>\S+))""",
        RegexOption.IGNORE_CASE
    )

    data class Parsed(
        val residualQuery: String,
        val tagFilters: Set<String>,
        val folderFilter: String?
    ) {
        val hasFacetFilter: Boolean get() = tagFilters.isNotEmpty() || folderFilter != null
    }

    fun parse(raw: String?): Parsed {
        if (raw.isNullOrBlank()) {
            return Parsed("", emptySet(), null)
        }

        val tags = mutableSetOf<String>()
        var folder: String? = null

        val residual = TOKEN_REGEX.replace(raw) { match ->
            val key = match.groups["key"]!!.value.lowercase()
            val value = match.groups["quoted"]?.value ?: match.groups["bare"]?.value ?: ""
            if (value.isNotBlank()) {
                when (key) {
                    "tag" -> tags.add(value.lowercase())
                    "folder" -> folder = value
                }
            }
            " "
        }.replace(Regex("""\s+"""), " ").trim()

        return Parsed(residual, tags, folder)
    }
}
