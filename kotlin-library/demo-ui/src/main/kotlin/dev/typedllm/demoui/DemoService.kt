package dev.typedllm.demoui

import dev.typedllm.Attempt
import dev.typedllm.FakeProvider
import dev.typedllm.OutputFormat
import dev.typedllm.Provider
import dev.typedllm.SchemaGenerator
import dev.typedllm.TypedLLM
import dev.typedllm.TypedOutputException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/** Runs messages through the real library, with a scripted or a live model, and returns API shapes. */
class DemoService(
    private val live: LiveConfig,
    private val scenarios: List<Scenario> = Scenarios.all,
) {
    private val eventSerializer = serializer<Event>()

    val target = TargetType(
        name = "Event",
        fields = FieldDescriber.describe(eventSerializer.descriptor),
        source = EVENT_SOURCE,
        schema = SchemaGenerator.generate(eventSerializer.descriptor),
    )

    fun scenarios(): List<ScenarioSummary> = scenarios.map(Scenario::summary)

    fun liveModels(): List<LiveModelSummary> = live.models.map(LiveModel::summary)

    /** Returns null if no scenario has this id. */
    suspend fun runScenario(id: String): RunResult? {
        val scenario = scenarios.find { it.id == id } ?: return null
        return extract(FakeProvider(*scenario.modelAnswers.toTypedArray()), scenario.format, scenario.message)
    }

    /** Returns null if no live model has this id. Provider failures surface as ProviderException. */
    suspend fun runLive(message: String, modelId: String): RunResult? {
        val model = live.models.find { it.id == modelId } ?: return null
        return extract(live.providerFor(model), OutputFormat.JSON, message)
    }

    private suspend fun extract(provider: Provider, format: OutputFormat, message: String): RunResult {
        val attempts = mutableListOf<AttemptView>()
        val llm = TypedLLM(provider, format, MAX_ATTEMPTS, onAttempt = { attempts += it.toView() })

        val outcome = try {
            val event: Event = llm.call("Pull the event details out of this message:\n\n$message")
            Outcome.Success(Json.encodeToJsonElement(event))
        } catch (e: TypedOutputException) {
            Outcome.Failure(e.attempts.size)
        }
        return RunResult(format.displayName, attempts, outcome)
    }

    private fun Attempt.toView() = AttemptView(
        number = number,
        output = rawOutput,
        accepted = succeeded,
        problem = error?.let(ErrorExplainer::explain),
        error = error,
    )

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
