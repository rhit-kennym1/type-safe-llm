package dev.typedllm

import dev.typedllm.demo.ClauseType
import dev.typedllm.demo.Contract
import dev.typedllm.demo.Samples
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypedLLMTest {

    @Test
    fun `returns typed value on first valid response`() = runTest {
        val c: Contract = TypedLLM(FakeProvider(Samples.VALID_JSON)).call("extract")
        assertEquals(listOf("Acme Corp", "Globex LLC"), c.parties)
        assertEquals(ClauseType.PAYMENT, c.clauses.first().type)
        assertNull(c.governingLaw)
    }

    @Test
    fun `retries with the decode error and succeeds`() = runTest {
        val provider = FakeProvider(Samples.PROSE, Samples.MISSING_FIELD, Samples.VALID_JSON)
        val attempts = mutableListOf<Attempt>()
        val c: Contract = TypedLLM(provider, onAttempt = { attempts += it }).call("extract")

        assertEquals("2026-01-01", c.effectiveDate)
        assertEquals(3, attempts.size)
        assertTrue(attempts.last().succeeded)
        assertTrue(attempts[1].error!!.contains("effectiveDate"))
        // The third request must carry the specific error back to the model.
        assertTrue(provider.received[2].last().content.contains("effectiveDate"))
    }

    @Test
    fun `throws typed exception when every attempt is invalid`() = runTest {
        val provider = FakeProvider(Samples.PROSE, Samples.BAD_ENUM, Samples.UNKNOWN_FIELD)
        val e = assertFailsWith<TypedOutputException> {
            TypedLLM(provider, maxAttempts = 3).call<Contract>("extract")
        }
        assertEquals(3, e.attempts.size)
        assertTrue(e.attempts.none { it.succeeded })
    }

    @Test
    fun `rejects invalid enum values and unknown fields`() = runTest {
        assertFailsWith<TypedOutputException> {
            TypedLLM(FakeProvider(Samples.BAD_ENUM), maxAttempts = 1).call<Contract>("extract")
        }
        assertFailsWith<TypedOutputException> {
            TypedLLM(FakeProvider(Samples.UNKNOWN_FIELD), maxAttempts = 1).call<Contract>("extract")
        }
    }

    @Test
    fun `same type decodes from YAML`() = runTest {
        val c: Contract = TypedLLM(FakeProvider(Samples.VALID_YAML), format = OutputFormat.YAML).call("extract")
        assertEquals(120000.0, c.totalValue)
    }

    @Test
    fun `reified generics work for collections`() = runTest {
        val json = """[{"title":"Fees","type":"PAYMENT","summary":"Net 30"}]"""
        val clauses: List<dev.typedllm.demo.Clause> = TypedLLM(FakeProvider(json)).call("extract clauses")
        assertEquals("Fees", clauses.single().title)
    }
}
