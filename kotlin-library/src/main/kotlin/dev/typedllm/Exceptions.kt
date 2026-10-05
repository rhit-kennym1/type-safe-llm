package dev.typedllm

data class Attempt(val number: Int, val rawOutput: String, val error: String?) {
    val succeeded: Boolean get() = error == null
}

/** Thrown when no attempt produced a valid instance of the target type. Carries the full attempt history. */
class TypedOutputException(val target: String, val attempts: List<Attempt>) : RuntimeException(
    "Failed to produce a valid $target after ${attempts.size} attempt(s). Last error: ${attempts.lastOrNull()?.error}"
)

/** Thrown at schema-generation time for types the library can't describe yet (e.g. polymorphic or recursive). */
class UnsupportedTypeException(message: String) : IllegalArgumentException(message)
