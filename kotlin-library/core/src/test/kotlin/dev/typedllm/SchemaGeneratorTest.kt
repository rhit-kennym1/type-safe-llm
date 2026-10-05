package dev.typedllm

import dev.typedllm.fixtures.Contract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaGeneratorTest {
    private val schema: JsonObject = SchemaGenerator.generate(serializer<Contract>().descriptor)

    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    @Test
    fun `required fields exclude fields with defaults`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("effectiveDate" in required)
        assertFalse("governingLaw" in required)
    }

    @Test
    fun `enums become string enums`() {
        val type = schema.obj("properties").obj("clauses").obj("items").obj("properties").obj("type")
        val values = type["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("PAYMENT", "TERMINATION", "CONFIDENTIALITY", "LIABILITY", "OTHER"), values)
    }

    @Test
    fun `objects forbid extra fields`() {
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
    }
}
