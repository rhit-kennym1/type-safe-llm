package dev.typedllm

/**
 * One round trip to the model. [usage] is null when the provider doesn't report tokens.
 * [durationMs] covers the provider call only, not decoding.
 */
data class Attempt(
    val number: Int,
    val rawOutput: String,
    val error: String?,
    val usage: TokenUsage? = null,
    val durationMs: Long = 0,
) {
    val succeeded: Boolean get() = error == null
}

/** Thrown when no attempt produced a valid instance of the target type. Carries the full attempt history. */
class TypedOutputException(val target: String, val attempts: List<Attempt>) : RuntimeException(
    "Failed to produce a valid $target after ${attempts.size} attempt(s). Last error: ${attempts.lastOrNull()?.error}"
)

/** Thrown at schema-generation time for types the library can't describe yet (e.g. open polymorphism). */
class UnsupportedTypeException(message: String) : IllegalArgumentException(message)

/**
 * The provider call itself failed: network error, bad API key, rate limit, model refusal.
 * Not retried by the library, because retries exist to correct invalid output, not outages.
 */
class ProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
