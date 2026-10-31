package dev.typedllm.demoui

import dev.typedllm.Attempt
import dev.typedllm.Completion
import dev.typedllm.CompletionRequest
import dev.typedllm.Extraction
import dev.typedllm.FakeProvider
import dev.typedllm.FieldReport
import dev.typedllm.OutputFormat
import dev.typedllm.Provider
import dev.typedllm.Safeguards
import dev.typedllm.SchemaGenerator
import dev.typedllm.TypedLLM
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Wraps a provider and keeps every request the library sends, so the page can show exactly what the
 * model received on each attempt: the system prompt with the schema, the user's prompt, and the corrections.
 */
private class RecordingProvider(private val delegate: Provider) : Provider {
    val requests = mutableListOf<CompletionRequest>()

    override val id: String get() = delegate.id

    override suspend fun complete(request: CompletionRequest): String {
        requests += request
        return delegate.complete(request)
    }

    override suspend fun completeWithUsage(request: CompletionRequest): Completion {
        requests += request
        return delegate.completeWithUsage(request)
    }
}

internal fun SafeguardsView.toSafeguards() = Safeguards(allowMissing, requireEvidence, targetedRetry, crossCheck)

private fun FieldReport.toView() = FieldReportView(name, status.name.lowercase(), value, quote, note)

/**
 * One target type the demo can extract into. The service holds a registry of these so new
 * target types (recursive, polymorphic, etc.) can be shown without changing the pipeline.
 */
@OptIn(ExperimentalSerializationApi::class) // descriptor.serialName, for the class name shown on the page
private class TargetEntry<T>(
    val id: String,
    val displayName: String,
    val source: String,
    val note: String,
    val livePrompt: String,
    val serializer: KSerializer<T>,
) {
    val view: TargetType = TargetType(
        id = id,
        name = displayName,
        fields = FieldDescriber.describe(serializer.descriptor),
        source = source,
        schema = SchemaGenerator.generate(serializer.descriptor),
        note = note,
        livePrompt = livePrompt,
        className = serializer.descriptor.serialName.substringAfterLast('.'),
    )

    /**
     * Calls the real library with [prompt] exactly as given, against a scripted or live provider.
     * Always goes through [TypedLLM.extract]; with every safeguard off that is the same loop as [TypedLLM.call].
     * The prompt is also the source that evidence quotes are checked against.
     */
    suspend fun run(
        provider: Provider,
        format: OutputFormat,
        prompt: String,
        maxAttempts: Int,
        model: String? = null,
        enforced: Boolean = false,
        safeguards: SafeguardsView = SafeguardsView(),
    ): RunResult {
        val recorder = RecordingProvider(provider)
        val llm = TypedLLM(recorder, format, maxAttempts)
        val extraction = llm.extract(prompt, serializer, source = prompt, safeguards = safeguards.toSafeguards())
        val outcome = when (extraction) {
            is Extraction.Complete -> Outcome.Success(Json.encodeToJsonElement(serializer, extraction.value))
            is Extraction.Ungrounded -> Outcome.Unverified(Json.encodeToJsonElement(serializer, extraction.value), extraction.flagged)
            is Extraction.Incomplete -> Outcome.Incomplete(extraction.partial, extraction.missing)
            is Extraction.Failed -> Outcome.Failure(extraction.attempts.size)
        }
        val views = extraction.attempts.mapIndexed { i, attempt -> attempt.toView(newMessages(recorder.requests, i)) }
        val crossCheck = extraction.crossCheck?.let { cc ->
            val sent = recorder.requests.lastOrNull()?.messages.orEmpty().map { SentMessage(it.role.name.lowercase(), it.content) }
            cc.toView(sent).copy(check = if (cc.error != null) "Cross-check" else null)
        }
        return RunResult(format.displayName, views, outcome, id, model, enforced, safeguards, extraction.fields.map { it.toView() }, crossCheck)
    }

    /** Messages request [index] added on top of the request before it. */
    private fun newMessages(requests: List<CompletionRequest>, index: Int): List<SentMessage> {
        val current = requests.getOrNull(index)?.messages ?: return emptyList()
        val before = if (index == 0) 0 else requests[index - 1].messages.size
        return current.drop(before).map { SentMessage(it.role.name.lowercase(), it.content) }
    }

    private fun Attempt.toView(sent: List<SentMessage>) = AttemptView(
        number = number,
        output = rawOutput,
        accepted = succeeded,
        problem = error?.let(ErrorExplainer::explain),
        error = error,
        check = error?.let(ErrorExplainer::check),
        sent = sent,
        durationMs = durationMs,
        inputTokens = usage?.inputTokens,
        outputTokens = usage?.outputTokens,
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
            livePrompt = LivePrompts.EVENT,
            serializer = serializer<Event>(),
        ),
        TargetEntry(
            id = "contract",
            displayName = "Contract",
            source = CONTRACT_SOURCE,
            note = "Nested objects, a list of clauses and an enum, from the contract-processing use case.",
            livePrompt = LivePrompts.CONTRACT,
            serializer = serializer<Contract>(),
        ),
        TargetEntry(
            id = "thread",
            displayName = "Comment thread",
            source = THREAD_SOURCE,
            note = "A recursive type — comments contain replies that are themselves comments. The library emits \$defs/\$ref so the shape is defined once and referenced wherever it appears.",
            livePrompt = LivePrompts.THREAD,
            serializer = serializer<CommentNode>(),
        ),
        TargetEntry(
            id = "order",
            displayName = "Order with payment",
            source = ORDER_SOURCE,
            note = "A sealed hierarchy — payment can be a card, bank transfer, or cash. The library emits oneOf with each branch pinning the discriminator value.",
            livePrompt = LivePrompts.ORDER,
            serializer = serializer<Order>(),
        ),
    )

    private val targetsById: Map<String, TargetEntry<*>> = targetEntries.associateBy { it.id }

    /** Kept for backward compatibility: the first target is treated as the default. */
    val target: TargetType get() = targetEntries.first().view

    fun targets(): List<TargetType> = targetEntries.map { it.view }

    fun hasTarget(id: String): Boolean = id in targetsById

    fun scenarios(): List<ScenarioSummary> = scenarios.map(Scenario::summary)

    fun liveModels(): List<LiveModelSummary> = live.models.map(LiveModel::summary)

    /** Returns null if no scenario has this id. */
    suspend fun runScenario(id: String): RunResult? {
        val scenario = scenarios.find { it.id == id } ?: return null
        val entry = targetsById[scenario.targetId] ?: return null
        return entry.run(
            provider = FakeProvider(*scenario.modelAnswers.toTypedArray()),
            format = scenario.format,
            prompt = "$SCENARIO_INSTRUCTION\n\n${scenario.message}",
            maxAttempts = MAX_ATTEMPTS,
        )
    }

    /**
     * Sends [prompt] unchanged as the user message and decodes into the chosen target, with the chosen safeguards.
     * Returns null if the model or target is unknown.
     * Provider failures surface as ProviderException.
     */
    suspend fun runLive(
        prompt: String,
        modelId: String,
        targetId: String,
        safeguards: SafeguardsView = SafeguardsView(),
    ): RunResult? {
        val entry = targetsById[targetId] ?: return null
        val model = live.models.find { it.id == modelId } ?: return null
        return entry.run(live.providerFor(model), OutputFormat.JSON, prompt, MAX_ATTEMPTS, model.model, model.enforceSchema, safeguards)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val SCENARIO_INSTRUCTION = "Pull the structured data out of this message:"
    }
}

/** Starting prompts for the Live tab: an instruction plus realistic input. Editable on the page. */
internal object LivePrompts {
    val EVENT = """
        Extract the event from this message.

        hey all, moving the capstone sync to friday oct 30 at 2pm in moench hall F-217. dana, luis and priya can make it, sam is out
    """.trimIndent()

    val CONTRACT = """
        Extract the contract from this excerpt.

        This Services Agreement takes effect on March 1, 2026 between Acme Labs LLC and Campus Tech Inc.
        Acme Labs will pay Campus Tech a total of 48,000 US dollars for the work described below.
        Payment: invoices are due within 30 days of receipt.
        Termination: either party may end this agreement with 60 days' written notice.
        Confidentiality: both parties will keep shared data confidential for three years after termination.
        This agreement is governed by the laws of the State of Indiana.
    """.trimIndent()

    val THREAD = """
        Turn this chat log into a comment thread. Keep each reply nested under the comment it answers.

        dana: thoughts on the redesign?
          luis (replying to dana): love the colors
            sam (replying to luis): agreed, the greens pop
          dana (replying to her own post): the sidebar feels cramped though
            priya (replying to that): could we collapse it on small screens?
    """.trimIndent()

    val ORDER = """
        Extract the order from this receipt.

        CAMPUS STORE · Order ORD-2041
        2 x Engineering notebook ........ 18.00
        1 x Graph paper pad ............. 5.50
        TOTAL ........................... 23.50
        Paid with VISA ending in 4242 · Cardholder: A. LEE
    """.trimIndent()
}
