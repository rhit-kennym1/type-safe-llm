package dev.typedllm.openai

import dev.typedllm.Attempt
import dev.typedllm.TypedLLM
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Calls the real OpenAI API. Skipped unless TYPED_LLM_LIVE=1 and OPENAI_API_KEY are set, so normal
 * test runs and CI never spend money. Models can be overridden with OLD_MODEL / NEW_MODEL.
 */
class LiveOpenAiTest {
    @Serializable enum class ClauseType { PAYMENT, TERMINATION, CONFIDENTIALITY, LIABILITY, OTHER }
    @Serializable data class Clause(val title: String, val type: ClauseType, val summary: String)
    @Serializable data class Contract(
        val parties: List<String>,
        val effectiveDate: String,
        val totalValue: Double,
        val currency: String,
        val clauses: List<Clause>,
        val governingLaw: String? = null,
    )

    private val agreement = """
        This Services Agreement is made effective January 1, 2026 between Acme Corp and Globex LLC.
        Globex will pay Acme a total of USD 120,000, invoiced monthly on net 30 terms.
        Either party may terminate with 60 days written notice. Both parties keep shared information confidential.
        This agreement is governed by the laws of the State of Indiana.
    """.trimIndent()

    private fun extract(model: String, native: Boolean): Contract = runBlocking {
        val log = { a: Attempt -> println("  [$model] attempt ${a.number}: ${a.error ?: "valid"}") }
        val llm = TypedLLM(OpenAiProvider.fromEnvironment(model, native), onAttempt = log)
        llm.call<Contract>("Extract the contract terms from this agreement:\n\n$agreement")
    }

    @Test
    fun `old model without native structured output`() {
        assumeLive()
        val contract = extract(System.getenv("OLD_MODEL") ?: "gpt-3.5-turbo", native = false)
        println("  => $contract")
        assertTrue(contract.parties.isNotEmpty())
    }

    @Test
    fun `new model with native structured output`() {
        assumeLive()
        val contract = extract(System.getenv("NEW_MODEL") ?: "gpt-4o-mini", native = true)
        println("  => $contract")
        assertTrue(contract.parties.isNotEmpty())
    }

    private fun assumeLive() {
        assumeTrue(System.getenv("TYPED_LLM_LIVE") == "1", "Set TYPED_LLM_LIVE=1 to run live tests")
        assumeTrue(!System.getenv("OPENAI_API_KEY").isNullOrBlank(), "Set OPENAI_API_KEY to run live tests")
    }
}
