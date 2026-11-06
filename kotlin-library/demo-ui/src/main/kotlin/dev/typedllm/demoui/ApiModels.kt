package dev.typedllm.demoui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Shapes the HTTP API sends and receives. The browser only ever sees these.

@Serializable
data class FieldView(val name: String, val type: String, val required: Boolean)

/**
 * A type the demo can extract into. One per request shape. The browser renders the fields
 * table, source, and schema from these so a new target doesn't require UI changes.
 */
@Serializable
data class TargetType(
    val id: String,
    val name: String,
    val fields: List<FieldView>,
    val source: String,
    val schema: JsonObject,
    /** One-sentence note shown under the target name — explains why this target is worth showing. */
    val note: String = "",
    /** Prompt prefilled in the Live tab when this target is picked: an instruction plus realistic input text. */
    val livePrompt: String = "",
    /** The Kotlin class name, e.g. "CommentNode", for showing the call as llm.call<CommentNode>(prompt). */
    val className: String = "",
)

@Serializable
data class ScenarioSummary(
    val id: String,
    val title: String,
    val description: String,
    val format: String,
    val message: String,
    /** Which target in /api/targets this scenario asks the library to produce. */
    val targetId: String,
)

@Serializable
data class LiveModelSummary(val id: String, val label: String, val description: String, val model: String)

/** The four safeguards against invented values, as toggled on the page. */
@Serializable
data class SafeguardsView(
    val allowMissing: Boolean = false,
    val requireEvidence: Boolean = false,
    val targetedRetry: Boolean = false,
    val crossCheck: Boolean = false,
)

/** The Live tab's request: which class to decode into, the exact prompt, which model, and which safeguards. */
@Serializable
data class LiveRunRequest(
    val prompt: String,
    val modelId: String,
    val targetId: String = "event",
    val safeguards: SafeguardsView = SafeguardsView(),
)

/** How the library judged one top-level field: "found", "missing", "unverified" or "disputed". */
@Serializable
data class FieldReportView(
    val name: String,
    val status: String,
    val value: JsonElement? = null,
    val quote: String? = null,
    val note: String? = null,
)

/** One chat message exactly as the library sent it to the provider. */
@Serializable
data class SentMessage(val role: String, val content: String)

/**
 * One round trip. [sent] holds only the messages new in this attempt: the system prompt and the user's
 * prompt on the first try, then the previous answer and the library's correction on each retry.
 * [check] names which check failed; [problem] says it in plain words; [error] is the library's exact text.
 */
@Serializable
data class AttemptView(
    val number: Int,
    val output: String,
    val accepted: Boolean,
    val problem: String?,
    val error: String?,
    val check: String? = null,
    val sent: List<SentMessage> = emptyList(),
    val durationMs: Long = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

@Serializable
sealed interface Outcome {
    @Serializable
    @SerialName("success")
    data class Success(val value: JsonElement) : Outcome

    @Serializable
    @SerialName("failure")
    data class Failure(val attempts: Int) : Outcome

    /** The source lacks some required fields; nothing was invented. */
    @Serializable
    @SerialName("incomplete")
    data class Incomplete(val partial: JsonObject, val missing: List<String>) : Outcome

    /** A complete value, but the safeguards couldn't support some fields. */
    @Serializable
    @SerialName("unverified")
    data class Unverified(val value: JsonElement, val flagged: List<String>) : Outcome
}

@Serializable
data class RunResult(
    val format: String,
    val attempts: List<AttemptView>,
    val outcome: Outcome,
    /** Which target was decoded, so the browser can label the result and look up the field schema. */
    val targetId: String,
    /** Model name for live runs; null for scripted examples. */
    val model: String? = null,
    /** True when the schema also went to the provider's structured-output field, not just into the prompt. */
    val enforced: Boolean = false,
    val safeguards: SafeguardsView = SafeguardsView(),
    /** One line per top-level field of the result. */
    val fields: List<FieldReportView> = emptyList(),
    /** The extra cross-check call, when that safeguard is on. */
    val crossCheck: AttemptView? = null,
)

@Serializable
data class ApiError(val error: String)
