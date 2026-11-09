package dev.typedllm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Deterministic source-evidence checks used by grounded extraction.
 *
 * This file contains the text/date/time/number matching heuristics only. Keeping them separate
 * from [Grounding] makes the extraction protocol and the evidence algorithms easier to read independently.
 */
internal object Evidence {
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    // Free-text dates a model may write as the value: "october 30", "friday oct 30th, 2026", "30 oct", "10/30/2026".
    private val MONTH_DAY = Regex("""^(?:[a-z]+,?\s+)?([a-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?$""")
    private val DAY_MONTH = Regex("""^(?:[a-z]+,?\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?([a-z]{3,9})\.?(?:,?\s+(\d{4}))?$""")
    private val NUMERIC_DATE = Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{4}))?$""")
    private val YEAR = Regex("""(?<!\d)(19|20)\d{2}(?!\d)""")
    private val TIME = Regex("""^(\d{1,2}):(\d{2})$""")
    private val NUMBER = Regex("""-?\d[\d,]*(?:\.\d+)?""")
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val UNIT = Regex("""(-?\d[\d,]*(?:\.\d+)?)\s*([a-z]+)\b""")

    /** Units a quoted number may be written in, with factors to the units a field is likely stored in. */
    private val UNITS = listOf(
        Regex("kg|kilograms?") to listOf(1.0, 1000.0, 2.20462),    // as kg, g, lb
        Regex("g|grams?") to listOf(0.001, 1.0),                   // as kg, g
        Regex("lbs?|pounds?") to listOf(0.453592, 1.0, 453.592),   // as kg, lb, g
        Regex("oz|ounces?") to listOf(0.0283495, 1.0, 28.3495),    // as kg, oz, g
        Regex("km|kilometers?|kilometres?") to listOf(1.0, 0.621371), // as km, mi
        Regex("mi|miles?") to listOf(1.609344, 1.0),               // as km, mi
    )

    private val NUMBER_WORDS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { (i, w) -> w to i } +
        listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety").withIndex().associate { (i, w) -> w to (i + 2) * 10 } +
        mapOf("dozen" to 12, "hundred" to 100)

    /** Ways a currency is written, for matching a code like "USD" against the source. */
    private val CURRENCIES = mapOf(
        "USD" to listOf("$", "usd", "dollar"),
        "EUR" to listOf("€", "eur", "euro"),
        "GBP" to listOf("£", "gbp", "pound", "sterling"),
        "JPY" to listOf("¥", "jpy", "yen"),
        "CAD" to listOf("cad", "canadian dollar"),
        "AUD" to listOf("aud", "australian dollar"),
        "INR" to listOf("₹", "inr", "rupee"),
        "CNY" to listOf("cny", "yuan", "rmb"),
        "CHF" to listOf("chf", "swiss franc"),
    )

    fun normalize(s: String): String = s.lowercase()
        .replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
        .replace('–', '-').replace('—', '-')
        .replace(Regex("""\s+"""), " ").trim()
        .trim('"', '\'', '.', '…', ' ')

    /** Where a quote sits in the source: the matching source text and how closely it matched (1.0 = exact). */
    data class Match(val text: String, val score: Double)

    /**
     * Finds [quote] in [source]. Models often copy a quote with small differences, so a quote of 12+ characters
     * also matches the stretch of the source closest to it by edit distance, if that stretch is at least 90% the
     * same. Sources over 5,000 characters only get exact matching, to keep the scan cheap.
     */
    fun locate(quote: String, source: String): Match? {
        val q = normalize(quote)
        val s = normalize(source)
        if (q.isEmpty()) return null
        if (q in s) return Match(q, 1.0)
        if (q.length < 12 || s.length > 5_000) return null
        // Approximate substring search (Sellers): edit distance from the quote to the best-matching stretch of the
        // source, where that stretch may start and end anywhere. start[j] tracks where the stretch ending at j began.
        var dist = IntArray(s.length + 1)
        var start = IntArray(s.length + 1) { it }
        for (i in 1..q.length) {
            val d = IntArray(s.length + 1).also { it[0] = i }
            val st = IntArray(s.length + 1)
            for (j in 1..s.length) {
                val replace = dist[j - 1] + if (q[i - 1] == s[j - 1]) 0 else 1
                val dropQuoteChar = dist[j] + 1
                val skipSourceChar = d[j - 1] + 1
                d[j] = minOf(replace, dropQuoteChar, skipSourceChar)
                st[j] = when (d[j]) {
                    replace -> start[j - 1]
                    dropQuoteChar -> start[j]
                    else -> st[j - 1]
                }
            }
            dist = d
            start = st
        }
        val end = (1..s.length).minBy { dist[it] }
        val text = s.substring(start[end], end).trim()
        return Match(text, 1.0 - dist[end].toDouble() / maxOf(q.length, text.length)).takeIf { it.score >= 0.9 }
    }

    /** The value to pass on (possibly corrected), why it isn't supported if it isn't, and a note on how it was. */
    data class Checked(val value: JsonElement, val problem: String?, val note: String?)

    /**
     * Checks [value] against the source text its quote matched, not the model's copy, so a misquoted number
     * can't slip through. When the quote matched approximately, words the value repeats from the model's quote
     * are first replaced with the source's spelling ("moech hall" -> "moench hall").
     */
    fun check(value: JsonElement, field: PlanField, quote: String, source: String): Checked {
        if (quote.isBlank()) return Checked(value, "no quote given", null)
        val match = locate(quote, source) ?: return Checked(value, "quote not found in the source", null)
        val corrected = if (match.score < 1.0) correctSpelling(value, wordFixes(normalize(quote), match.text)) else value
        if (!valueInQuote(corrected, field, match.text)) return Checked(value, "value doesn't appear in its quote", null)
        val note = when {
            corrected != value -> "corrected to the source's spelling (model wrote $value)"
            match.score < 1.0 -> "quote matched the source approximately (${(match.score * 100).toInt()}%)"
            else -> null
        }
        return Checked(corrected, null, note)
    }

    /**
     * Pairs of (model's word, source's word) where the model's quote misspelled a source word. Words are aligned
     * in order; only words without digits can pair, so "F-218" is never read as "F-217".
     */
    private fun wordFixes(modelQuote: String, sourceText: String): Map<String, String> {
        val a = WORD.findAll(modelQuote).map { it.value }.toList()
        val b = WORD.findAll(sourceText).map { it.value }.toList()
        fun close(x: String, y: String) = x != y && x.none(Char::isDigit) && y.none(Char::isDigit) &&
            minOf(x.length, y.length) >= 3 && distance(x, y) <= if (maxOf(x.length, y.length) >= 8) 2 else 1
        // Word-level edit distance: keeping a word costs 0, fixing a misspelled one 1, anything else 2.
        val cost = Array(a.size + 1) { i -> IntArray(b.size + 1) { j -> if (i == 0) j * 2 else if (j == 0) i * 2 else 0 } }
        for (i in 1..a.size) for (j in 1..b.size) {
            val pair = if (a[i - 1] == b[j - 1]) 0 else if (close(a[i - 1], b[j - 1])) 1 else 4
            cost[i][j] = minOf(cost[i - 1][j - 1] + pair, cost[i - 1][j] + 2, cost[i][j - 1] + 2)
        }
        val fixes = mutableMapOf<String, String>()
        var i = a.size
        var j = b.size
        while (i > 0 && j > 0) {
            val pair = if (a[i - 1] == b[j - 1]) 0 else if (close(a[i - 1], b[j - 1])) 1 else 4
            when (cost[i][j]) {
                cost[i - 1][j - 1] + pair -> { if (pair == 1) fixes[a[i - 1]] = b[j - 1]; i--; j-- }
                cost[i - 1][j] + 2 -> i--
                else -> j--
            }
        }
        return fixes
    }

    /** Replaces misspelled words in a text value (or each item of a text list), keeping a leading capital. */
    private fun correctSpelling(value: JsonElement, fixes: Map<String, String>): JsonElement {
        if (fixes.isEmpty()) return value
        fun fix(text: String) = WORD.replace(text) { m ->
            fixes[m.value.lowercase()]?.let { if (m.value[0].isUpperCase()) it.replaceFirstChar(Char::uppercase) else it } ?: m.value
        }
        return when {
            value is JsonPrimitive && value.isString -> JsonPrimitive(fix(value.content))
            value is JsonArray -> JsonArray(value.map { if (it is JsonPrimitive && it.isString) JsonPrimitive(fix(it.content)) else it })
            else -> value
        }
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    fun valueInQuote(value: JsonElement, field: PlanField, quote: String): Boolean = when (field.kind) {
        ValueKind.STRING -> (value as? JsonPrimitive)?.contentOrNull?.let { stringInQuote(it, quote) } ?: true
        ValueKind.NUMBER -> (value as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { numberInQuote(it, quote) } ?: true
        ValueKind.ARRAY -> if (field.itemKind == ValueKind.STRING) {
            (value as? JsonArray)?.all { el -> (el as? JsonPrimitive)?.contentOrNull?.let { stringInQuote(it, quote) } ?: true } ?: true
        } else true
        // Enums, booleans and nested objects are often paraphrased ("workshop", "yes"); only the quote is checked.
        else -> true
    }

    fun stringInQuote(value: String, quote: String): Boolean {
        val v = normalize(value)
        val q = normalize(quote)
        if (v.isEmpty() || v in q) return true
        CURRENCIES[value.trim().uppercase()]?.let { names -> if (names.any { it in q }) return true }
        parseDate(v)?.let { (year, month, day) -> return dateInQuote(year, month, day, q) }
        TIME.matchEntire(value.trim())?.let { m -> return timeInQuote(m.groupValues[1].toInt(), m.groupValues[2].toInt(), q) }
        val quoteWords = WORD.findAll(q).map { it.value }.toSet()
        val valueWords = WORD.findAll(v).map { it.value }.toList()
        return valueWords.isNotEmpty() && valueWords.all { it in quoteWords }
    }

    fun numberInQuote(value: Double, quote: String): Boolean {
        val q = normalize(quote)
        val close = { x: Double, tolerance: Double -> kotlin.math.abs(x - value) <= tolerance }
        if (NUMBER.findAll(q).any { m -> m.value.replace(",", "").toDoubleOrNull()?.let { close(it, 1e-6) } == true }) return true
        if (numberWords(q).any { close(it, 1e-6) }) return true
        // A value in a different unit than the source, e.g. weight_kg = 2.4 from "2,400 g" or "5.3 lb".
        return UNIT.findAll(q).any { m ->
            val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@any false
            val factors = UNITS.firstOrNull { (unit, _) -> unit.matches(m.groupValues[2]) }?.second ?: return@any false
            factors.any { close(n * it, maxOf(1e-6, kotlin.math.abs(value) * 0.01)) }
        }
    }

    /** Numbers written as words: "eight", "twenty-five", "a dozen". */
    private fun numberWords(q: String): List<Double> {
        val words = WORD.findAll(q).map { it.value }.toList()
        val found = mutableListOf<Double>()
        for ((i, w) in words.withIndex()) {
            val n = NUMBER_WORDS[w] ?: continue
            val next = words.getOrNull(i + 1)?.let(NUMBER_WORDS::get)
            found += n.toDouble()
            if (n >= 20 && n % 10 == 0 && next != null && next < 10) found += (n + next).toDouble()
        }
        return found
    }

    /** (year or null, month, day) for an ISO or free-text date value, or null if [v] isn't a date. */
    private fun parseDate(v: String): Triple<Int?, Int, Int>? {
        fun month(word: String) = MONTHS.indexOfFirst { it.startsWith(word) } + 1
        ISO_DATE.matchEntire(v)?.let { m -> return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        MONTH_DAY.matchEntire(v)?.let { m -> month(m.groupValues[1]).takeIf { it > 0 }?.let { return Triple(m.groupValues[3].toIntOrNull(), it, m.groupValues[2].toInt()) } }
        DAY_MONTH.matchEntire(v)?.let { m -> month(m.groupValues[2]).takeIf { it > 0 }?.let { return Triple(m.groupValues[3].toIntOrNull(), it, m.groupValues[1].toInt()) } }
        NUMERIC_DATE.matchEntire(v)?.let { m -> return Triple(m.groupValues[3].toIntOrNull(), m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        return null
    }

    /**
     * A date in the quote written with the same day and month, e.g. "2026-11-04" matches "Nov 4", "4 November", "11/4".
     * The year is compared only when both the value and the quote have one.
     */
    private fun dateInQuote(year: Int?, month: Int, day: Int, q: String): Boolean {
        if (month !in 1..12) return false
        val years = YEAR.findAll(q).map { it.value.toInt() }.toList()
        if (year != null && years.isNotEmpty() && year !in years) return false
        val name = MONTHS[month - 1]
        val dayToken = Regex("""(?<!\d)0?$day(?:st|nd|rd|th)?(?!\d)""")
        val hasMonthName = Regex("""\b${name.take(3)}[a-z]*\.?""").containsMatchIn(q)
        val numeric = Regex("""(?<!\d)0?$month[/.-]0?$day(?!\d)""").containsMatchIn(q)
        return numeric || (hasMonthName && dayToken.containsMatchIn(q))
    }

    /** A time in the quote, e.g. "14:00" matches "14:00", "2pm", "2:00 p.m."; "12:00" also matches "noon". */
    private fun timeInQuote(hour: Int, minute: Int, q: String): Boolean {
        val mm = "%02d".format(minute)
        if (Regex("""(?<!\d)0?$hour:$mm(?!\d)""").containsMatchIn(q)) return true
        if (hour == 12 && minute == 0 && "noon" in q) return true
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val suffix = if (hour < 12) "a" else "p"
        val minutePart = if (minute == 0) "(?::00)?" else ":$mm"
        return Regex("""(?<!\d)$h12$minutePart\s*${suffix}\.?m\b""").containsMatchIn(q)
    }
}
