package dev.typedllm.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapts a library schema to OpenAI's strict structured-output dialect, which requires:
 * an object at the root, every property listed as required (optional fields stay nullable instead),
 * and no free-form maps. Returns null when the schema can't be expressed, so the caller falls back
 * to prompt-only mode instead of sending a request OpenAI would reject.
 */
internal object StrictSchema {

    fun fromOrNull(schema: JsonObject): JsonObject? {
        if (schema["type"]?.jsonPrimitive?.content != "object") return null
        return runCatching { adaptObject(schema) }.getOrNull()
    }

    private fun adapt(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> adaptObject(element)
        is JsonArray -> JsonArray(element.map(::adapt))
        else -> element
    }

    private fun adaptObject(node: JsonObject): JsonObject {
        val additional = node["additionalProperties"]
        if (additional != null && additional != JsonPrimitive(false)) throw UnsupportedShape()

        val adapted = node.mapValues { (_, value) -> adapt(value) }.toMutableMap()
        val properties = node["properties"] as? JsonObject
        if (properties != null) adapted["required"] = JsonArray(properties.keys.map(::JsonPrimitive))
        return JsonObject(adapted)
    }

    private class UnsupportedShape : RuntimeException()
}
