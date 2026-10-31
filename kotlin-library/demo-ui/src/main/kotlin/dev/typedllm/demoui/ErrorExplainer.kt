package dev.typedllm.demoui

/**
 * Rewrites the library's decode errors in plain words for the demo.
 * The original error is still shown alongside, so nothing is hidden.
 */
internal object ErrorExplainer {

    private const val FALLBACK = "Doesn't match the fields we asked for"

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // The library's own type check (StrictJson) lists every problem, one "$.path: ..." per line.
        Regex("""\$\.(\S+): required field is missing""") to { m -> all(m).let { if (it.size == 1) "Missing the ${it[0]}" else "Missing " + joinNames(it.joinToString(", ")) } },
        Regex("""\$\.(\S+): unknown field""") to { m -> "Has a field we didn't ask for: " + joinNames(all(m).joinToString(", ")) },
        Regex("""missing "type", which must be one of""") to { _ -> "Didn't say which kind it is: the \"type\" field is missing" },
        Regex(""""([^"]+)" is not one of the allowed kinds""") to { m -> "Not one of the allowed kinds: \"${m.groupValues[1]}\"" },
        Regex("""\$\.(\S+): duplicate key""") to { m -> "Gives the same field twice: " + joinNames(all(m).joinToString(", ")) },
        Regex("""\$\.(\S+): expected ([^,]+), got the text""") to { m -> "The ${m.groupValues[1]} is written as text in quotes; it should be ${m.groupValues[2]}" },
        Regex("""\$\.(\S+): .+ is not one of """) to { m -> "The ${m.groupValues[1]} isn't one of the allowed values" },
        Regex("""Fields \[([^\]]+)] are required""") to { m -> "Missing " + joinNames(m.groupValues[1]) },
        Regex("""(?:Field|Property) '([^']+)' is required""") to { m -> "Missing the ${m.groupValues[1]}" },
        Regex("""[Uu]nknown (?:key|property) '([^']+)'""") to { m -> "Has a field we didn't ask for: ${m.groupValues[1]}" },
        Regex("""Class discriminator was missing""") to { _ -> "Didn't say which kind it is: the \"type\" field is missing" },
        Regex("""[Ss]erializer for subclass '([^']+)' is not found""") to { m -> "Not one of the allowed kinds: \"${m.groupValues[1]}\"" },
        Regex("""Unexpected JSON token at offset 0|Expected an? object, but got|response is plain text""") to { _ -> "Plain text instead of structured data" },
    )

    /**
     * Which check a decode error came from, in the order decoding runs them. Order matters here:
     * kotlinx prefixes many messages with "Unexpected JSON token", so syntax is matched last.
     */
    private val checks: List<Pair<Regex, String>> = listOf(
        Regex("""duplicate key""") to "Duplicate field",
        Regex("""[Uu]nknown (?:key|property|field)""") to "Unknown field",
        Regex("""are required|is required|required field is missing""") to "Missing field",
        Regex("""[Cc]lass discriminator|[Pp]olymorphic|[Ss]erializer for subclass|missing "type"|allowed kinds""") to "Sealed-type choice",
        Regex("""does not contain element with name|is not one of""") to "Enum value",
        Regex("""constraint violated|Failed requirement""") to "Domain rule",
        Regex("""Unexpected JSON token at offset 0|response is plain text|Expected start of the object|[Tt]railing comma|EOF|Expected quotation mark""") to "JSON syntax",
    )

    fun check(error: String): String =
        checks.firstOrNull { (pattern, _) -> pattern.containsMatchIn(error) }?.second ?: "Type or value"

    fun explain(error: String): String =
        rules.firstNotNullOfOrNull { (pattern, describe) -> pattern.find(error)?.let(describe) } ?: FALLBACK

    /** The first capture group of this match and every later match, e.g. each missing field. */
    private fun all(m: MatchResult) = generateSequence(m) { it.next() }.map { it.groupValues[1] }.toList()

    /** "date, title" -> "date and title"; "a, b, c" -> "a, b and c". */
    private fun joinNames(names: String): String {
        val parts = names.split(",").map(String::trim)
        return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}
