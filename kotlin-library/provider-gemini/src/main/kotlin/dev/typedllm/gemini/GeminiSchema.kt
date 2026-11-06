package dev.typedllm.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Adapts a library schema for Gemini's `responseJsonSchema`.
 *
 * Gemini accepts a subset of JSON Schema and silently ignores keywords it doesn't support, so a schema
 * that "works" may not actually be enforced. The library validates every response anyway; this adapter
 * only rewrites what would otherwise be lost:
 *  - `oneOf` (sealed classes) becomes `anyOf`, which Google documents as supported. Equivalent here
 *    because every branch pins a different discriminator value.
 *  - A root `$ref` (recursive types) is replaced by its definition, keeping `$defs`, so the root is an object.
 *  - A single-element `allOf` around a `$ref` collapses to the bare `$ref`.
 */
internal object GeminiSchema {

    fun adapt(schema: JsonObject): JsonObject = adaptObject(inlineRootRef(schema))

    private fun inlineRootRef(schema: JsonObject): JsonObject {
        val ref = (schema["\$ref"] as? JsonPrimitive)?.content ?: return schema
        val defs = schema["\$defs"] as? JsonObject ?: return schema
        val target = defs[ref.removePrefix("#/\$defs/")] as? JsonObject ?: return schema
        return JsonObject(target + ("\$defs" to defs))
    }

    private fun adaptElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> adaptObject(element)
        is JsonArray -> JsonArray(element.map(::adaptElement))
        else -> element
    }

    private fun adaptObject(node: JsonObject): JsonObject {
        val allOf = node["allOf"] as? JsonArray
        val only = allOf?.singleOrNull() as? JsonObject
        if (only != null && only.keys == setOf("\$ref")) return only

        val out = linkedMapOf<String, JsonElement>()
        for ((key, value) in node) out[if (key == "oneOf") "anyOf" else key] = adaptElement(value)
        return JsonObject(out)
    }
}
