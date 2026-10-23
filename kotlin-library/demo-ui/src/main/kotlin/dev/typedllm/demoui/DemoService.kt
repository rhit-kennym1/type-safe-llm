package dev.typedllm.demoui

import dev.typedllm.Attempt
import dev.typedllm.FakeProvider
import dev.typedllm.OutputFormat
import dev.typedllm.Provider
import dev.typedllm.SchemaGenerator
import dev.typedllm.TypedLLM
import dev.typedllm.TypedOutputException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/**
 * One target type the demo can extract into. The service holds a registry of these so new
 * target types (recursive, polymorphic, etc.) can be shown without changing the pipeline.
 */
private class TargetEntry<T>(
    val id: String,
    val displayName: String,
    val source: String,
    val note: String,
    val serializer: KSerializer<T>,
) {
    val view: TargetType = TargetType(
        id = id,
        name = displayName,
        fields = FieldDescriber.describe(serializer.descriptor),
        source = source,
        schema = SchemaGenerator.generate(serializer.descriptor),
        note = note,
    )

    /** Decode via the real library against a scripted or live provider. */
    suspend fun run(provider: Provider, format: OutputFormat, message: String, maxAttempts: Int): RunResult {
        val attempts = mutableListOf<AttemptView>()
        val llm = TypedLLM(provider, format, maxAttempts, onAttempt = { attempts += it.toView() })
        val outcome = try {
            val value = llm.call("Pull the structured data out of this message:\n\n$message", serializer)
            Outcome.Success(Json.encodeToJsonElement(serializer, value))
        } catch (e: TypedOutputException) {
            Outcome.Failure(e.attempts.size)
        }
        return RunResult(format.displayName, attempts, outcome, id)
    }

    private fun Attempt.toView() = AttemptView(
        number = number,
        output = rawOutput,
        accepted = succeeded,
        problem = error?.let(ErrorExplainer::explain),
        error = error,
    )
}

/** Runs messages through the real library, with a scripted or a live model, and returns API shapes. */
class DemoService(
    private val live: LiveConfig,
    private val scenarios: List<Scenario> = Scenarios.all,
) {
    // The targets catalogue: order matters, "event" is kept first as the default so the
    // existing API shape (/api/target) still has an obvious answer.
    private val targetEntries: List<TargetEntry<*>> = listOf(
        TargetEntry(
            id = "event",
            displayName = "Event",
            source = EVENT_SOURCE,
            note = "A plain record pulled out of a casual message.",
            serializer = serializer<Event>(),
        ),
        TargetEntry(
            id = "thread",
            displayName = "Comment thread",
            source = THREAD_SOURCE,
            note = "A recursive type — comments contain replies that are themselves comments. The library emits \$defs/\$ref so the shape is defined once and referenced wherever it appears.",
            serializer = serializer<CommentNode>(),
        ),
        TargetEntry(
            id = "order",
            displayName = "Order with payment",
            source = ORDER_SOURCE,
            note = "A sealed hierarchy — payment can be a card, bank transfer, or cash. The library emits oneOf with each branch pinning the discriminator value.",
            serializer = serializer<Order>(),
        ),
    )

    private val targetsById: Map<String, TargetEntry<*>> = targetEntries.associateBy { it.id }

    /** Kept for backward compatibility: the first target is treated as the default. */
    val target: TargetType get() = targetEntries.first().view

    fun targets(): List<TargetType> = targetEntries.map { it.view }

    fun scenarios(): List<ScenarioSummary> = scenarios.map(Scenario::summary)

    fun liveModels(): List<LiveModelSummary> = live.models.map(LiveModel::summary)

    /** Returns null if no scenario has this id. */
    suspend fun runScenario(id: String): RunResult? {
        val scenario = scenarios.find { it.id == id } ?: return null
        val entry = targetsById[scenario.targetId] ?: return null
        return entry.run(
            provider = FakeProvider(*scenario.modelAnswers.toTypedArray()),
            format = scenario.format,
            message = scenario.message,
            maxAttempts = MAX_ATTEMPTS,
        )
    }

    /**
     * Live runs always target the Event type, which is the simplest and the one local models
     * handle reliably. Polymorphic and recursive targets are showcased through scripted scenarios.
     */
    suspend fun runLive(message: String, modelId: String): RunResult? {
        val model = live.models.find { it.id == modelId } ?: return null
        val entry = targetsById.getValue("event")
        return entry.run(
            provider = live.providerFor(model),
            format = OutputFormat.JSON,
            message = message,
            maxAttempts = MAX_ATTEMPTS,
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
