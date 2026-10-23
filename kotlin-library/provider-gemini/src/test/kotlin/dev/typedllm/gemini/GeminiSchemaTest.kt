package dev.typedllm.gemini

import dev.typedllm.SchemaGenerator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GeminiSchemaTest {
    @Serializable private data class Node(val label: String, val children: List<Node> = emptyList())

    @Serializable private sealed interface Pet {
        @Serializable @SerialName("dog") data class Dog(val name: String) : Pet
        @Serializable @SerialName("cat") data class Cat(val lives: Int) : Pet
    }
    @Serializable private data class Owner(val name: String, val pet: Pet)

    private inline fun <reified T> adapted() = GeminiSchema.adapt(SchemaGenerator.generate(serializer<T>().descriptor))

    private fun keys(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.keys + e.values.flatMap(::keys)
        is JsonArray -> e.flatMap(::keys).toSet()
        else -> emptySet()
    }

    @Test
    fun `oneOf becomes anyOf`() {
        val schema = adapted<Owner>()
        assertFalse("oneOf" in keys(schema))
        assertEquals(2, schema["properties"]!!.jsonObject["pet"]!!.jsonObject["anyOf"]!!.jsonArray.size)
    }

    @Test
    fun `recursive root is inlined to an object`() {
        val schema = adapted<Node>()
        assertNull(schema["\$ref"])
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
    }
}
