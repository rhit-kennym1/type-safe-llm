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

/** Anything that turns a conversation into raw text. Real providers (OpenAI, Anthropic, ...) implement this. */
interface Provider {
    suspend fun complete(request: CompletionRequest): String
}

/** Deterministic provider for tests and demos: returns scripted responses in order and records every request. */
class FakeProvider(vararg responses: String) : Provider {
    private val queue = ArrayDeque(responses.toList())
    val received = mutableListOf<CompletionRequest>()

    override suspend fun complete(request: CompletionRequest): String {
        received += request
        return queue.removeFirstOrNull() ?: error("FakeProvider ran out of scripted responses")
    }
}
