package dev.typedllm

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
