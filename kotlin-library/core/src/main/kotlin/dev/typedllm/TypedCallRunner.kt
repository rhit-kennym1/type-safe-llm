package dev.typedllm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer

/** Implements the normal `call<T>()` flow: schema -> model -> strict decode -> retry on failure. */
internal class TypedCallRunner(
    private val provider: Provider,
    private val format: OutputFormat,
    private val maxAttempts: Int,
    private val onAttempt: (Attempt) -> Unit,
) {
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T> run(prompt: String, serializer: KSerializer<T>): T {
        val schema = SchemaGenerator.generate(serializer.descriptor)
        val nativeSchema = schema.takeIf { format == OutputFormat.JSON }
        val messages = mutableListOf(
            Message(Role.SYSTEM, PromptTemplates.structuredOutput(schema, format)),
            Message(Role.USER, prompt),
        )
        val failedAttempts = mutableListOf<Attempt>()

        for (attemptNumber in 1..maxAttempts) {
            val response = provider.completeTimed(CompletionRequest(messages.toList(), nativeSchema))
            val raw = response.completion.text

            try {
                val value = format.decode(serializer, raw)
                onAttempt(
                    Attempt(
                        number = attemptNumber,
                        rawOutput = raw,
                        error = null,
                        usage = response.completion.usage,
                        durationMs = response.durationMs,
                    ),
                )
                return value
            } catch (error: IllegalArgumentException) {
                val message = decodeError(error)
                val attempt = Attempt(
                    number = attemptNumber,
                    rawOutput = raw,
                    error = message,
                    usage = response.completion.usage,
                    durationMs = response.durationMs,
                )
                failedAttempts += attempt
                onAttempt(attempt)

                if (attemptNumber < maxAttempts) {
                    messages += Message(Role.ASSISTANT, raw)
                    messages += Message(Role.USER, PromptTemplates.correction(message, format))
                }
            }
        }

        throw TypedOutputException(serializer.descriptor.serialName, failedAttempts)
    }
}
