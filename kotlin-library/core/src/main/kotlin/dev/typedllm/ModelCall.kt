package dev.typedllm

/** Provider response plus the time spent waiting for the provider. */
internal data class TimedCompletion(
    val completion: Completion,
    val durationMs: Long,
)

/** Keeps provider timing identical across the normal and grounded call paths. */
internal suspend fun Provider.completeTimed(request: CompletionRequest): TimedCompletion {
    val started = System.nanoTime()
    val completion = completeWithUsage(request)
    return TimedCompletion(
        completion = completion,
        durationMs = (System.nanoTime() - started) / 1_000_000,
    )
}

internal fun decodeError(error: IllegalArgumentException): String =
    error.message ?: error::class.simpleName ?: "Unknown decode error"
