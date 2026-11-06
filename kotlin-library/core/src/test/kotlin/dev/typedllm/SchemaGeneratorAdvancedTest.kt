package dev.typedllm

import dev.typedllm.fixtures.Comment
import dev.typedllm.fixtures.Folder
import dev.typedllm.fixtures.Order
import dev.typedllm.fixtures.Payment
import dev.typedllm.fixtures.Task
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaGeneratorAdvancedTest {

    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    // --- @Description -----------------------------------------------------------

    @Test
    fun `class-level Description emits description`() {
        val schema = SchemaGenerator.generate(serializer<Task>().descriptor)
        assertEquals("A single named task on a to-do list.", schema.str("description"))
    }

    @Test
    fun `property-level Description emits description on the property schema`() {
        val schema = SchemaGenerator.generate(serializer<Task>().descriptor)
        val summary = schema.obj("properties").obj("summary")
        assertEquals(
            "Short imperative sentence, e.g. 'Mail the contract'",
            summary.str("description"),
        )
    }

    @Test
    fun `no Description means no description key`() {
        val schema = SchemaGenerator.generate(serializer<Task>().descriptor)
        val done = schema.obj("properties").obj("done")
        assertNull(done["description"])
    }

    // --- Recursive types --------------------------------------------------------

    @Test
    fun `recursive type is promoted into defs and referenced`() {
        val schema = SchemaGenerator.generate(serializer<Comment>().descriptor)

        // Root must now be a $ref — Comment references itself.
        val ref = schema["\$ref"]?.jsonPrimitive?.content
        assertNotNull(ref, "Root schema should be a \$ref into \$defs")
        assertTrue(ref.startsWith("#/\$defs/"))

        val defs = schema["\$defs"]!!.jsonObject
        val commentDef = defs[ref.removePrefix("#/\$defs/")]!!.jsonObject
        assertEquals("object", commentDef.str("type"))

        // The `replies` array's items must $ref the same definition.
        val repliesItems = commentDef.obj("properties").obj("replies").obj("items")
        assertEquals(ref, repliesItems["\$ref"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non-recursive types stay inlined for simple schemas`() {
        val schema = SchemaGenerator.generate(serializer<Task>().descriptor)
        assertNull(schema["\$defs"], "Non-recursive schemas should not have \$defs")
        assertNull(schema["\$ref"], "Non-recursive schemas should not have \$ref")
    }

    @Test
    fun `mutually recursive types share defs`() {
        val schema = SchemaGenerator.generate(serializer<Folder>().descriptor)
        // Folder → Node → Folder (via the nullable `folder` field) is a cycle.
        // The root must be a $ref and Folder must appear in $defs.
        assertNotNull(schema["\$defs"], "Mutually recursive schemas need \$defs")
    }

    // --- Sealed polymorphism ----------------------------------------------------

    @Test
    fun `sealed hierarchy becomes oneOf`() {
        val schema = SchemaGenerator.generate(serializer<Order>().descriptor)
        val payment = schema.obj("properties").obj("payment")
        val oneOf = payment["oneOf"]!!.jsonArray
        assertEquals(3, oneOf.size, "card, bank, cash — three branches")
    }

    @Test
    fun `each sealed branch pins its discriminator value`() {
        val schema = SchemaGenerator.generate(serializer<Order>().descriptor)
        val oneOf = schema.obj("properties").obj("payment")["oneOf"]!!.jsonArray

        val discriminators = oneOf.map { branch ->
            val b = branch.jsonObject
            val typeField = b.obj("properties").obj("type")
            typeField["enum"]!!.jsonArray.single().jsonPrimitive.content
        }.toSet()

        assertEquals(setOf("card", "bank", "cash"), discriminators)
    }

    @Test
    fun `sealed branch requires its discriminator`() {
        val schema = SchemaGenerator.generate(serializer<Order>().descriptor)
        val cardBranch = schema.obj("properties").obj("payment")["oneOf"]!!
            .jsonArray.map { it.jsonObject }
            .single { b ->
                b.obj("properties").obj("type")["enum"]!!.jsonArray.single()
                    .jsonPrimitive.content == "card"
            }

        val required = cardBranch["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("type" in required)
        assertTrue("last4" in required)
        assertTrue("holder" in required)
    }

    @Test
    fun `description on sealed branch field survives`() {
        val schema = SchemaGenerator.generate(serializer<Order>().descriptor)
        val cardBranch = schema.obj("properties").obj("payment")["oneOf"]!!
            .jsonArray.map { it.jsonObject }
            .single { b ->
                b.obj("properties").obj("type")["enum"]!!.jsonArray.single()
                    .jsonPrimitive.content == "card"
            }
        val last4 = cardBranch.obj("properties").obj("last4")
        assertEquals("Last four digits of the card number", last4.str("description"))
    }
}
