package dev.typedllm

import kotlinx.serialization.json.JsonObject

enum class Role { SYSTEM, USER, ASSISTANT }

data class Message(val role: Role, val content: String)

/**
 * What the library asks a provider for.
 * [responseSchema] is set when the output must be JSON, so providers with native structured output
 * can enforce it during generation. Providers without that support ignore it: the library validates either way.
 */
data class CompletionRequest(val messages: List<Message>, val responseSchema: JsonObject? = null)

/** Tokens a provider billed for one call, as the provider reported them. */
data class TokenUsage(val inputTokens: Int, val outputTokens: Int) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

/** One provider response: the raw text plus usage when the provider reports it. */
data class Completion(val text: String, val usage: TokenUsage? = null)

/**
 * Anything that turns a conversation into raw text. Real providers (OpenAI-compatible, Gemini, ...) implement this.
 *
 * Implement [complete]. Override [completeWithUsage] too when the provider reports token counts, so callers
 * can measure cost; the default reports no usage.
 */
interface Provider {
    /** Short label for logs and experiment results, e.g. "gemini:gemini-3.1-flash-lite". */
    val id: String get() = this::class.simpleName ?: "provider"

    suspend fun complete(request: CompletionRequest): String

    suspend fun completeWithUsage(request: CompletionRequest): Completion = Completion(complete(request))
}

/** Deterministic provider for tests and demos: returns scripted responses in order and records every request. */
class FakeProvider(vararg responses: String) : Provider {
    private val queue = ArrayDeque(responses.toList())
    val received = mutableListOf<CompletionRequest>()

    override val id: String = "fake"

    override suspend fun complete(request: CompletionRequest): String {
        received += request
        return queue.removeFirstOrNull() ?: error("FakeProvider ran out of scripted responses")
    }
}
