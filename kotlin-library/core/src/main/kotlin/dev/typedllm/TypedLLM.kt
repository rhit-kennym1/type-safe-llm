package dev.typedllm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Returns a fully valid instance of the requested type, or throws [TypedOutputException].
 * Nothing in between.
 */
class TypedLLM(
    private val provider: Provider,
    val format: OutputFormat = OutputFormat.JSON,
    val maxAttempts: Int = 3,
    private val onAttempt: (Attempt) -> Unit = {},
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** Reified: the type survives to runtime, so `call<List<Clause>>(...)` just works. No TypeReference. */
    suspend inline fun <reified T> call(prompt: String): T = call(prompt, serializer<T>())

    @OptIn(ExperimentalSerializationApi::class) // descriptor.serialName, used to name the type in errors
    suspend fun <T> call(prompt: String, serializer: KSerializer<T>): T {
        val schema = SchemaGenerator.generate(serializer.descriptor)
        // Native structured output only exists for JSON, so the schema is offered to the provider only then.
        val nativeSchema = schema.takeIf { format == OutputFormat.JSON }
        val messages = mutableListOf(
            Message(Role.SYSTEM, systemPrompt(schema)),
            Message(Role.USER, prompt),
        )
        val attempts = mutableListOf<Attempt>()

        repeat(maxAttempts) { i ->
            val started = System.nanoTime()
            val completion = provider.completeWithUsage(CompletionRequest(messages.toList(), nativeSchema))
            val durationMs = (System.nanoTime() - started) / 1_000_000
            val raw = completion.text
            val error = try {
                val value = format.decode(serializer, raw)
                onAttempt(Attempt(i + 1, raw, null, completion.usage, durationMs))
                return value
            } catch (e: IllegalArgumentException) { // SerializationException is a subclass; so are init-block require() failures
                e.message ?: e::class.simpleName ?: "Unknown decode error"
            }
            val attempt = Attempt(i + 1, raw, error, completion.usage, durationMs)
            attempts += attempt
            onAttempt(attempt)
            messages += Message(Role.ASSISTANT, raw)
            messages += Message(Role.USER, correction(error))
        }
        throw TypedOutputException(serializer.descriptor.serialName, attempts)
    }

    private fun systemPrompt(schema: JsonObject) =
        "You are a structured-output generator. Respond with ONLY a ${format.displayName} document " +
            "whose structure conforms to the JSON Schema below. No prose, no explanation, no extra fields.\n" +
            "Schema:\n$schema"

    private fun correction(error: String) =
        "Your previous response could not be decoded into the required type.\n" +
            "Error: $error\n" +
            "Respond again with ONLY a corrected ${format.displayName} document."
}
