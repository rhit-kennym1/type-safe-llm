package dev.typedllm

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A wire format the model writes in. Decoding is strict: unknown keys, missing required
 * fields, wrong types, and invalid enum values all fail. That strictness *is* the validation.
 */
sealed interface OutputFormat {
    val displayName: String
    fun <T> decode(serializer: KSerializer<T>, text: String): T

    data object JSON : OutputFormat {
        override val displayName = "JSON"
        override fun <T> decode(serializer: KSerializer<T>, text: String): T =
            Json.decodeFromString(serializer, stripFences(text))
    }

    data object YAML : OutputFormat {
        override val displayName = "YAML"
        override fun <T> decode(serializer: KSerializer<T>, text: String): T =
            Yaml.default.decodeFromString(serializer, stripFences(text))
    }
}

/** Removes a surrounding Markdown code fence (```json ... ```), which models add even when told not to. */
internal fun stripFences(text: String): String {
    val t = text.trim()
    if (!t.startsWith("```")) return t
    return t.removePrefix("```").substringAfter('\n').substringBeforeLast("```").trim()
}
