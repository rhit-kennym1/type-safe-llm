package dev.typedllm.demoui

/**
 * Rewrites the library's decode errors in plain words for the demo.
 * The original error is still shown alongside, so nothing is hidden.
 */
internal object ErrorExplainer {

    private const val FALLBACK = "Doesn't match the fields we asked for"

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("""Fields \[([^\]]+)] are required""") to { m -> "Missing " + joinNames(m.groupValues[1]) },
        Regex("""(?:Field|Property) '([^']+)' is required""") to { m -> "Missing the ${m.groupValues[1]}" },
        Regex("""[Uu]nknown (?:key|property) '([^']+)'""") to { m -> "Has a field we didn't ask for: ${m.groupValues[1]}" },
        Regex("""Unexpected JSON token at offset 0|Expected an? object, but got""") to { _ -> "Plain text instead of structured data" },
    )

    fun explain(error: String): String =
        rules.firstNotNullOfOrNull { (pattern, describe) -> pattern.find(error)?.let(describe) } ?: FALLBACK

    /** "date, title" -> "date and title"; "a, b, c" -> "a, b and c". */
    private fun joinNames(names: String): String {
        val parts = names.split(",").map(String::trim)
        return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}
