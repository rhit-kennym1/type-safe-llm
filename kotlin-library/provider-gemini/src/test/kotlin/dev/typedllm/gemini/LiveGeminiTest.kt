package dev.typedllm.gemini

import dev.typedllm.Attempt
import dev.typedllm.TypedLLM
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Calls the real Gemini API. Skipped unless TYPED_LLM_LIVE=1 and GEMINI_API_KEY are set, so normal test runs
 * and CI never spend quota. Override the model with GEMINI_MODEL.
 */
class LiveGeminiTest {
    @Serializable sealed interface Payment {
        @Serializable @SerialName("card") data class Card(val last4: String, val holder: String) : Payment
        @Serializable @SerialName("cash") data object Cash : Payment
    }
    @Serializable data class Order(val id: String, val total: Double, val payment: Payment)
    @Serializable data class Comment(val author: String, val body: String, val replies: List<Comment> = emptyList())

    private val model = System.getenv("GEMINI_MODEL") ?: "gemini-3.1-flash-lite"

    private fun <T> run(block: suspend (TypedLLM) -> T): T = runBlocking {
        val log = { a: Attempt -> println("  [$model] attempt ${a.number} (${a.durationMs} ms, ${a.usage}): ${a.error ?: "valid"}") }
        block(TypedLLM(GeminiProvider.fromEnvironment(model, nativeStructuredOutput = true, temperature = 0.0), onAttempt = log))
    }

    @Test
    fun `sealed type through native structured output`() {
        assumeLive()
        val order = run { it.call<Order>("Order ORD-2041 for 84.20, paid by Visa ending 4417, cardholder Priya Shah.") }
        println("  => $order")
        assertTrue(order.payment is Payment.Card)
    }

    @Test
    fun `recursive type through native structured output`() {
        assumeLive()
        val thread = run {
            it.call<Comment>("dana: 'thoughts on the redesign?' luis replied 'love the colors', and sam replied to luis 'agreed'.")
        }
        println("  => $thread")
        assertTrue(thread.replies.isNotEmpty())
    }

    private fun assumeLive() {
        assumeTrue(System.getenv("TYPED_LLM_LIVE") == "1", "Set TYPED_LLM_LIVE=1 to run live tests")
        assumeTrue(!System.getenv("GEMINI_API_KEY").isNullOrBlank(), "Set GEMINI_API_KEY to run live tests")
    }
}
