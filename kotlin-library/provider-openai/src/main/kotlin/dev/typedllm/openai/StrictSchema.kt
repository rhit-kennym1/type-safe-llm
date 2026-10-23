package dev.typedllm.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapts a library schema to OpenAI's strict structured-output dialect, which requires:
 * an object at the root, every property listed as required (optional fields stay nullable instead),
 * no free-form maps, and no `oneOf`. Returns null when the schema can't be expressed, so the caller
 * falls back to prompt-only mode instead of sending a request OpenAI would reject.
 *
 * Rewrites applied:
 *  - A root `$ref` (recursive types) is replaced by the definition it points to, keeping `$defs`,
 *    because strict mode needs an object at the root.
 *  - `oneOf` (sealed classes) becomes `anyOf`. Strict mode rejects `oneOf`; the two are equivalent here
 *    because every branch pins a different discriminator value, so at most one branch can match.
 *  - A single-element `allOf` wrapping a `$ref` collapses to the bare `$ref`. The generator uses that
 *    wrapper to attach descriptions or a discriminator pin, which strict mode doesn't need: the
 *    referenced definition already pins the discriminator.
 */
internal object StrictSchema {

    fun fromOrNull(schema: JsonObject): JsonObject? {
        val root = inlineRootRef(schema) ?: return null
        if (root["type"]?.jsonPrimitive?.content != "object") return null
        return runCatching { adaptObject(root) }.getOrNull()
    }

    /** Returns the schema with a root-level `$ref` replaced by its target, or null if the target is missing. */
    private fun inlineRootRef(schema: JsonObject): JsonObject? {
        val ref = (schema["\$ref"] as? JsonPrimitive)?.content ?: return schema
        val defs = schema["\$defs"] as? JsonObject ?: return null
        val target = defs[ref.removePrefix("#/\$defs/")] as? JsonObject ?: return null
        return JsonObject(target + ("\$defs" to defs))
    }

    private fun adapt(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> adaptObject(element)
        is JsonArray -> JsonArray(element.map(::adapt))
        else -> element
    }

    private fun adaptObject(node: JsonObject): JsonObject {
        collapsedRef(node)?.let { return it }

        val additional = node["additionalProperties"]
        if (additional != null && additional != JsonPrimitive(false)) throw UnsupportedShape()

        val adapted = linkedMapOf<String, JsonElement>()
        for ((key, value) in node) {
            val outKey = if (key == "oneOf") "anyOf" else key
            adapted[outKey] = adapt(value)
        }
        val properties = node["properties"] as? JsonObject
        if (properties != null) adapted["required"] = JsonArray(properties.keys.map(::JsonPrimitive))
        return JsonObject(adapted)
    }

    /** `{"allOf": [{"$ref": ...}], ...}` becomes `{"$ref": ...}`; anything else returns null. */
    private fun collapsedRef(node: JsonObject): JsonObject? {
        val allOf = node["allOf"] as? JsonArray ?: return null
        val only = allOf.singleOrNull() as? JsonObject ?: return null
        if (only.keys != setOf("\$ref")) return null
        return only
    }

    private class UnsupportedShape : RuntimeException()

    /** Visible for tests: walks every object in a schema. */
    internal fun walk(element: JsonElement, visit: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> { visit(element); element.values.forEach { walk(it, visit) } }
            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }
}
