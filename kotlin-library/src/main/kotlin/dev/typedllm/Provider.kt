package dev.typedllm

enum class Role { SYSTEM, USER, ASSISTANT }

data class Message(val role: Role, val content: String)

/** Anything that turns a conversation into raw text. Real providers (OpenAI, Anthropic, ...) implement this. */
interface Provider {
    suspend fun complete(messages: List<Message>): String
}

/** Deterministic provider for tests and demos: returns scripted responses in order and records every request. */
class FakeProvider(vararg responses: String) : Provider {
    private val queue = ArrayDeque(responses.toList())
    val received = mutableListOf<List<Message>>()

    override suspend fun complete(messages: List<Message>): String {
        received += messages.toList()
        return queue.removeFirstOrNull() ?: error("FakeProvider ran out of scripted responses")
    }
}
