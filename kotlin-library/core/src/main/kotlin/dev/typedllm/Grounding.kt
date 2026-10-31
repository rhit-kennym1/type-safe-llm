package dev.typedllm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What kind of value a field holds, as far as the evidence checks care. */
internal enum class ValueKind { STRING, NUMBER, BOOLEAN, ENUM, ARRAY, OBJECT, OTHER }

/** One top-level field of the target type, read from its generated schema. */
internal data class PlanField(
    val name: String,
    val schema: JsonObject,
    val required: Boolean,
    val nullable: Boolean,
    val kind: ValueKind,
    val itemKind: ValueKind?,
    val description: String?,
)

/** What one grounded answer turned into: the plain JSON to decode, a report per field, and required fields the source lacks. */
internal data class Unwrapped(val plain: JsonObject, val fields: List<FieldReport>, val missingRequired: List<String>)

/**
 * Grounded extraction: asks the model for one small object per field (quote, status, value) instead of the plain
 * type, then turns the answer back into the plain type while checking each value against the source text.
 * Works on JSON Schema only, so nothing here depends on Kotlin types.
 * Applies to the top-level fields of an object type; nested objects are treated as one value each.
 */
internal object Grounding {
    const val QUOTE = "quote"
    const val STATUS = "status"
    const val VALUE = "value"
    const val FOUND = "found"
    const val MISSING = "missing"

    /** The top-level fields of [schema], or null when the root isn't an object with properties (lists, sealed roots). */
    fun fields(schema: JsonObject): List<PlanField>? {
        val root = resolveRoot(schema) ?: return null
        val properties = root["properties"] as? JsonObject ?: return null
        val required = (root["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        return properties.map { (name, element) ->
            val fieldSchema = element.jsonObject
            val (inner, nullable) = unwrapNullable(fieldSchema)
            val kind = kindOf(inner)
            val itemKind = if (kind == ValueKind.ARRAY) (inner["items"] as? JsonObject)?.let { kindOf(unwrapNullable(it).first) } else null
            PlanField(
                name = name,
                schema = fieldSchema,
                required = name in required,
                nullable = nullable,
                kind = kind,
                itemKind = itemKind,
                description = (fieldSchema["description"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    /** The schema the model is asked to follow in grounded mode. `$defs` are kept so nested references still resolve. */
    fun envelope(schema: JsonObject, fields: List<PlanField>, safeguards: Safeguards): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (field in fields) put(field.name, fieldEnvelope(field, safeguards))
        }
        putJsonArray("required") { fields.forEach { add(it.name) } }
        put("additionalProperties", false)
        schema["\$defs"]?.let { put("\$defs", it) }
    }

    private fun fieldEnvelope(field: PlanField, safeguards: Safeguards): JsonObject = buildJsonObject {
        put("type", "object")
        field.description?.let { put("description", it) }
        putJsonObject("properties") {
            // Order matters: the model writes the quote first, so the value is conditioned on evidence.
            if (safeguards.requireEvidence) putJsonObject(QUOTE) {
                put("type", "string")
                put("description", "The exact words copied from the source that state this value. Empty if the source does not state it.")
            }
            if (safeguards.allowMissing) putJsonObject(STATUS) {
                put("type", "string")
                putJsonArray("enum") { add(FOUND); add(MISSING) }
            }
            if (safeguards.allowMissing && !field.nullable) {
                putJsonObject(VALUE) { putJsonArray("anyOf") { add(field.schema); add(buildJsonObject { put("type", "null") }) } }
            } else {
                put(VALUE, field.schema)
            }
        }
        putJsonArray("required") {
            if (safeguards.requireEvidence) add(QUOTE)
            if (safeguards.allowMissing) add(STATUS)
            add(VALUE)
        }
        put("additionalProperties", false)
    }

    /** Instructions added to the system message so the model knows what each envelope key means. */
    fun instructions(safeguards: Safeguards): String = buildString {
        append("Each field of the result is an object. ")
        if (safeguards.requireEvidence) append("\"quote\" holds the exact words copied from the source that state the value, written first. ")
        if (safeguards.allowMissing) append("\"status\" is \"found\" or \"missing\". ")
        append("\"value\" holds the field's value. ")
        if (safeguards.allowMissing) append("If the source does not state a field, set status to \"missing\" and value to null. Never guess or invent a value.")
    }.trim()

    /**
     * Turns a grounded answer into plain JSON and a per-field report. Throws IllegalArgumentException for structural
     * problems (not JSON, wrong shape), which the caller treats like any decode error and retries.
     */
    fun unwrap(text: String, fields: List<PlanField>, source: String, safeguards: Safeguards): Unwrapped {
        val root = StrictJson.parse(stripFences(text))
        require(root is JsonObject) { "Expected a JSON object with one entry per field." }
        val unknown = root.keys - fields.map { it.name }.toSet()
        require(unknown.isEmpty()) { "Unknown field(s): ${unknown.joinToString()}. Only these fields are allowed: ${fields.joinToString { it.name }}." }

        val plain = mutableMapOf<String, JsonElement>()
        val reports = mutableListOf<FieldReport>()
        val missingRequired = mutableListOf<String>()

        for (field in fields) {
            val envelope = root[field.name]
            if (envelope == null) {
                require(safeguards.allowMissing) { "Field '${field.name}' is required." }
                recordMissing(field, plain, missingRequired)
                reports += FieldReport(field.name, FieldStatus.MISSING, note = "left out of the answer")
                continue
            }
            require(envelope is JsonObject) { "Field '${field.name}' must be an object with ${expectedKeys(safeguards)}." }
            val extra = envelope.keys - expectedKeys(safeguards).toSet()
            require(extra.isEmpty()) { "Field '${field.name}' has unexpected key(s): ${extra.joinToString()}." }

            val status = if (safeguards.allowMissing) {
                val s = (envelope[STATUS] as? JsonPrimitive)?.contentOrNull
                require(s == FOUND || s == MISSING) { "Field '${field.name}' needs status \"found\" or \"missing\"." }
                s
            } else FOUND
            val value = envelope[VALUE] ?: JsonNull
            val quote = if (safeguards.requireEvidence) (envelope[QUOTE] as? JsonPrimitive)?.contentOrNull.orEmpty() else null

            val markedMissing = safeguards.allowMissing && (status == MISSING || (value is JsonNull && !field.nullable))
            if (markedMissing) {
                recordMissing(field, plain, missingRequired)
                reports += FieldReport(field.name, FieldStatus.MISSING, quote = quote?.ifBlank { null })
                continue
            }

            plain[field.name] = value
            reports += if (quote == null) {
                FieldReport(field.name, FieldStatus.FOUND, value)
            } else {
                val (problem, note) = Evidence.check(value, field, quote, source)
                FieldReport(field.name, if (problem == null) FieldStatus.FOUND else FieldStatus.UNVERIFIED, value, quote.ifBlank { null }, problem ?: note)
            }
        }
        return Unwrapped(JsonObject(plain), reports, missingRequired)
    }

    private fun recordMissing(field: PlanField, plain: MutableMap<String, JsonElement>, missingRequired: MutableList<String>) {
        when {
            !field.required -> Unit                 // has a default: leave it out
            field.nullable -> plain[field.name] = JsonNull
            else -> missingRequired += field.name   // the type can't be built without it
        }
    }

    private fun expectedKeys(safeguards: Safeguards) = buildList {
        if (safeguards.requireEvidence) add(QUOTE)
        if (safeguards.allowMissing) add(STATUS)
        add(VALUE)
    }

    // ---------------------------------------------------------------- cross-check

    fun crossCheckRequest(fields: List<PlanField>, source: String): CompletionRequest {
        val system = "You check whether facts are stated in a source text. For each field listed, copy the exact words " +
            "from the source that state its value. Use null if the source does not state it. Do not infer or guess. " +
            "Respond with ONLY a JSON object with one key per field."
        val list = fields.joinToString("\n") { f -> "- ${f.name}" + (f.description?.let { ": $it" } ?: "") + " (${f.kind.name.lowercase()})" }
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for (f in fields) putJsonObject(f.name) { putJsonArray("anyOf") { add(buildJsonObject { put("type", "string") }); add(buildJsonObject { put("type", "null") }) } }
            }
            putJsonArray("required") { fields.forEach { add(it.name) } }
            put("additionalProperties", false)
        }
        return CompletionRequest(
            listOf(Message(Role.SYSTEM, system), Message(Role.USER, "Fields:\n$list\n\nSource:\n$source")),
            schema,
        )
    }

    /** The cross-check's quote per field, or null if its answer wasn't usable. */
    fun parseCrossCheck(text: String, fields: List<PlanField>): Map<String, String?>? = runCatching {
        val root = Json.parseToJsonElement(stripFences(text)).jsonObject
        fields.associate { f -> f.name to (root[f.name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifBlank { null } }
    }.getOrNull()

    /** Flags values the independent pass couldn't support, and notes possible text for fields marked missing. */
    fun applyCrossCheck(reports: List<FieldReport>, quotes: Map<String, String?>, fields: List<PlanField>, source: String): List<FieldReport> {
        val byName = fields.associateBy { it.name }
        return reports.map { r ->
            val field = byName[r.name] ?: return@map r
            val q = quotes[r.name]?.let { Evidence.locate(it, source)?.text }
            when (r.status) {
                FieldStatus.FOUND -> when {
                    q == null -> r.copy(status = FieldStatus.DISPUTED, note = "independent check found no supporting text in the source")
                    r.value != null && !Evidence.valueInQuote(r.value, field, q) ->
                        r.copy(status = FieldStatus.DISPUTED, note = "independent check's quote doesn't contain this value: \"$q\"")
                    else -> r
                }
                FieldStatus.MISSING -> if (q != null) r.copy(note = "independent check found possible text: \"$q\"") else r
                else -> r
            }
        }
    }

    // ---------------------------------------------------------------- schema helpers

    private fun resolveRoot(schema: JsonObject): JsonObject? {
        val ref = (schema["\$ref"] as? JsonPrimitive)?.contentOrNull ?: return schema.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "object" }
        val name = ref.removePrefix("#/\$defs/")
        return (schema["\$defs"] as? JsonObject)?.get(name) as? JsonObject
    }

    private fun unwrapNullable(schema: JsonObject): Pair<JsonObject, Boolean> {
        val anyOf = schema["anyOf"] as? JsonArray ?: return schema to false
        val branches = anyOf.map { it.jsonObject }
        val isNull = { b: JsonObject -> b["type"]?.jsonPrimitive?.contentOrNull == "null" }
        val others = branches.filterNot(isNull)
        return if (others.size == 1 && others.size < branches.size) others[0] to true else schema to false
    }

    private fun kindOf(schema: JsonObject): ValueKind = when {
        schema["enum"] != null -> ValueKind.ENUM
        else -> when (schema["type"]?.jsonPrimitive?.contentOrNull) {
            "string" -> ValueKind.STRING
            "integer", "number" -> ValueKind.NUMBER
            "boolean" -> ValueKind.BOOLEAN
            "array" -> ValueKind.ARRAY
            "object" -> ValueKind.OBJECT
            null -> if (schema["\$ref"] != null || schema["oneOf"] != null || schema["allOf"] != null) ValueKind.OBJECT else ValueKind.OTHER
            else -> ValueKind.OTHER
        }
    }
}

/**
 * Deterministic checks of a value against the source text. No model involved, so they can't be talked around,
 * but they are heuristics: they confirm the value is written in the quote, not that the model read it correctly.
 */
internal object Evidence {
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
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
     * also matches a stretch of the source that is at least 90% the same (by edit distance). Sources over 5,000
 * characters only get exact matching, to keep the scan cheap.
     */
    fun locate(quote: String, source: String): Match? {
        val q = normalize(quote)
        val s = normalize(source)
        if (q.isEmpty()) return null
        if (q in s) return Match(q, 1.0)
        if (q.length < 12 || s.length > 5_000) return null
        val best = (0..maxOf(0, s.length - q.length)).maxBy { similarity(q, s.substring(it, minOf(s.length, it + q.length))) }
        val spread = maxOf(2, q.length / 10)
        return (q.length - spread..q.length + spread)
            .mapNotNull { len -> s.substring(best, minOf(s.length, best + len)).takeIf { it.isNotEmpty() } }
            .map { Match(it, similarity(q, it)) }
            .maxBy { it.score }
            .takeIf { it.score >= 0.9 }
    }

    /**
     * Why a found value isn't supported (first), and a note on how it was supported (second). The value is
     * checked against the matching source text, not the model's copy, so a misquoted number can't slip through.
     */
    fun check(value: JsonElement, field: PlanField, quote: String, source: String): Pair<String?, String?> {
        if (quote.isBlank()) return "no quote given" to null
        val match = locate(quote, source) ?: return "quote not found in the source" to null
        if (!valueInQuote(value, field, match.text)) return "value doesn't appear in its quote" to null
        return null to if (match.score < 1.0) "quote matched the source approximately (${(match.score * 100).toInt()}%)" else null
    }

    private fun similarity(a: String, b: String): Double {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
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
        ISO_DATE.matchEntire(value.trim())?.let { m -> return dateInQuote(m.groupValues[2].toInt(), m.groupValues[3].toInt(), q) }
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

    /** A date in the quote written with the same day and month, e.g. "2026-11-04" matches "Nov 4", "4 November", "11/4". */
    private fun dateInQuote(month: Int, day: Int, q: String): Boolean {
        if (month !in 1..12) return false
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
