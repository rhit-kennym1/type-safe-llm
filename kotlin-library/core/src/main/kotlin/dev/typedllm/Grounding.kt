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
 * Grounded extraction: asks the model for one small object per field (quote and value, plus a status when there are
 * no quotes) instead of the plain type, then turns the answer back into the plain type while checking each value against the source text.
 * Works on JSON Schema only, so nothing here depends on Kotlin types.
 * Applies to the top-level fields of an object type; nested objects are treated as one value each.
 */
internal object Grounding {
    const val QUOTE = "quote"
    const val STATUS = "status"
    const val VALUE = "value"
    const val FOUND = "found"
    const val MISSING = "missing"
    const val QUOTED_NO_VALUE = "quoted source text but gave no value"

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
            if (usesStatus(safeguards)) putJsonObject(STATUS) {
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
            if (usesStatus(safeguards)) add(STATUS)
            add(VALUE)
        }
        put("additionalProperties", false)
    }

    /** Instructions added to the system message so the model knows what each envelope key means. */
    fun instructions(safeguards: Safeguards): String = buildString {
        append("Each field of the result is an object. ")
        if (safeguards.requireEvidence) append("\"quote\" holds the exact words copied from the source that state the value, written first. ")
        if (usesStatus(safeguards)) append("\"status\" is \"found\" or \"missing\". ")
        append("\"value\" holds the field's value. ")
        if (safeguards.allowMissing) {
            append("A field is stated if the source gives it in any words, even informally (\"lunch\" can be a title). ")
            if (safeguards.requireEvidence) append("Only if nothing in the source states a field, leave its quote empty and set value to null. ")
            else append("Only if nothing in the source states a field, set status to \"missing\" and value to null. ")
            append("Don't invent values.")
        }
    }.trim()

    /** With evidence quotes, an empty quote already means "missing", so the model isn't asked for a separate status. */
    private fun usesStatus(safeguards: Safeguards) = safeguards.allowMissing && !safeguards.requireEvidence

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

            val status = if (usesStatus(safeguards)) {
                val s = (envelope[STATUS] as? JsonPrimitive)?.contentOrNull
                require(s == FOUND || s == MISSING) { "Field '${field.name}' needs status \"found\" or \"missing\"." }
                s
            } else FOUND
            val value = envelope[VALUE] ?: JsonNull
            val quote = if (safeguards.requireEvidence) (envelope[QUOTE] as? JsonPrimitive)?.contentOrNull.orEmpty() else null

            // With quotes, a null value means missing; a quote that is real source text then contradicts it.
            val markedMissing = safeguards.allowMissing && (status == MISSING || value is JsonNull)
            if (markedMissing) {
                recordMissing(field, plain, missingRequired)
                val quoted = quote?.let { Evidence.locate(it, source) }?.text
                reports += FieldReport(field.name, FieldStatus.MISSING, quote = quote?.ifBlank { null },
                    note = quoted?.let { QUOTED_NO_VALUE })
                continue
            }

            if (quote == null) {
                plain[field.name] = value
                reports += FieldReport(field.name, FieldStatus.FOUND, value)
            } else {
                val result = Evidence.check(value, field, quote, source)
                plain[field.name] = result.value
                reports += FieldReport(
                    field.name, if (result.problem == null) FieldStatus.FOUND else FieldStatus.UNVERIFIED,
                    result.value, quote.ifBlank { null }, result.problem ?: result.note,
                )
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
        if (usesStatus(safeguards)) add(STATUS)
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
