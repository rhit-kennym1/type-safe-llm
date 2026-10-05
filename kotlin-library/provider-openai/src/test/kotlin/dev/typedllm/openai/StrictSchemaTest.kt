package dev.typedllm.openai

import dev.typedllm.SchemaGenerator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StrictSchemaTest {
    @Serializable private data class Invoice(val number: String, val note: String? = null)
    @Serializable private data class Tags(val values: Map<String, String>)

    private inline fun <reified T> schemaOf() = SchemaGenerator.generate(serializer<T>().descriptor)

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
}
