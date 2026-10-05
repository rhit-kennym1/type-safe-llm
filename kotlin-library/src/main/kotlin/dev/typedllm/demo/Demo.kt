package dev.typedllm.demo

import dev.typedllm.Attempt
import dev.typedllm.FakeProvider
import dev.typedllm.OutputFormat
import dev.typedllm.TypedLLM
import dev.typedllm.TypedOutputException
import kotlinx.coroutines.runBlocking

private const val PROMPT = "Extract the contract terms from the attached agreement."

private fun log(a: Attempt) =
    println("  attempt ${a.number}: " + if (a.succeeded) "VALID" else "rejected -> ${a.error?.lineSequence()?.first()}")

fun main(): Unit = runBlocking {
    println("1) Self-correction: prose -> missing field -> valid (JSON)")
    val contract: Contract = TypedLLM(
        FakeProvider(Samples.PROSE, Samples.MISSING_FIELD, Samples.VALID_JSON),
        onAttempt = ::log,
    ).call(PROMPT)
    println("  => $contract\n")

    println("2) Same type, different format (YAML)")
    val fromYaml: Contract = TypedLLM(FakeProvider(Samples.VALID_YAML), format = OutputFormat.YAML, onAttempt = ::log)
        .call(PROMPT)
    println("  => $fromYaml\n")

    println("3) Never a partial object: every attempt invalid -> typed exception")
    try {
        TypedLLM(FakeProvider(Samples.PROSE, Samples.BAD_ENUM, Samples.UNKNOWN_FIELD), onAttempt = ::log)
            .call<Contract>(PROMPT)
    } catch (e: TypedOutputException) {
        println("  => TypedOutputException after ${e.attempts.size} attempts")
    }

}
