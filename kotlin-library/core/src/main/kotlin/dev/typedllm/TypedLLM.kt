package dev.typedllm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Main entry point for typed LLM calls.
 *
 * Most users only need [call]:
 *
 * ```kotlin
 * val event: Event = TypedLLM(provider).call("Extract the event")
 * ```
 *
 * [call] returns a fully decoded value or throws [TypedOutputException] after the configured
 * retry limit. [extract] is the evidence-aware variant for source-grounded extraction.
 *
 * The orchestration lives in small internal runners so this class stays focused on the public API.
 */
class TypedLLM(
    private val provider: Provider,
    val format: OutputFormat = OutputFormat.JSON,
    val maxAttempts: Int = 3,
    private val onAttempt: (Attempt) -> Unit = {},
    val safeguards: Safeguards = Safeguards.NONE,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** Ask the model for [T]. Invalid responses are corrected and retried automatically. */
    suspend inline fun <reified T> call(prompt: String): T = call(prompt, serializer<T>())

    /** Non-reified overload for callers that already have a serializer. */
    suspend fun <T> call(prompt: String, serializer: KSerializer<T>): T =
        TypedCallRunner(provider, format, maxAttempts, onAttempt).run(prompt, serializer)

    /**
     * Extract [T] from [source] while optionally checking that returned values are supported by it.
     * The prompt defaults to being the source, which keeps the common case concise.
     */
    suspend inline fun <reified T> extract(
        prompt: String,
        source: String = prompt,
        safeguards: Safeguards = this.safeguards,
    ): Extraction<T> = extract(prompt, serializer<T>(), source, safeguards)

    /** Non-reified overload for callers that already have a serializer. */
    suspend fun <T> extract(
        prompt: String,
        serializer: KSerializer<T>,
        source: String = prompt,
        safeguards: Safeguards = this.safeguards,
    ): Extraction<T> = ExtractionRunner(provider, format, maxAttempts, onAttempt)
        .run(prompt, serializer, source, safeguards)
}
