package dev.typedllm.openai

import dev.typedllm.SchemaGenerator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictSchemaTest {
    @Serializable private data class Invoice(val number: String, val note: String? = null)
    @Serializable private data class Tags(val values: Map<String, String>)
    @Serializable private data class Node(val label: String, val children: List<Node> = emptyList())

    @Serializable private sealed interface Shape {
        @Serializable @SerialName("circle") data class Circle(val radius: Double) : Shape
        @Serializable @SerialName("square") data class Square(val side: Double) : Shape
    }
    @Serializable private data class Drawing(val title: String, val shape: Shape)

    private inline fun <reified T> schemaOf() = SchemaGenerator.generate(serializer<T>().descriptor)

    private fun keysAnywhere(schema: JsonObject): Set<String> {
        val keys = mutableSetOf<String>()
        StrictSchema.walk(schema) { keys += it.keys }
        return keys
    }

    @Test
    fun `every property becomes required`() {
        val strict = assertNotNull(StrictSchema.fromOrNull(schemaOf<Invoice>()))
        val required = strict["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf("number", "note"), required.toSet())
    }

    @Test
    fun `maps and non-object roots fall back to prompt-only`() {
        assertNull(StrictSchema.fromOrNull(schemaOf<Tags>()))
        assertNull(StrictSchema.fromOrNull(schemaOf<List<Invoice>>()))
    }

    @Test
    fun `recursive root ref is inlined so native mode still applies`() {
        val strict = assertNotNull(StrictSchema.fromOrNull(schemaOf<Node>()), "recursive types must not fall back")
        assertEquals("object", strict["type"]!!.jsonPrimitive.content)
        assertNull(strict["\$ref"])
        // The children still point at the shared definition, which is kept.
        val items = strict["properties"]!!.jsonObject["children"]!!.jsonObject["items"]!!.jsonObject
        val ref = items["\$ref"]!!.jsonPrimitive.content
        assertTrue(strict["\$defs"]!!.jsonObject.containsKey(ref.removePrefix("#/\$defs/")))
    }

    @Test
    fun `sealed classes use anyOf because strict mode rejects oneOf`() {
        val strict = assertNotNull(StrictSchema.fromOrNull(schemaOf<Drawing>()))
        val keys = keysAnywhere(strict)
        assertFalse("oneOf" in keys)
        val branches = strict["properties"]!!.jsonObject["shape"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(2, branches.size)
    }
}
