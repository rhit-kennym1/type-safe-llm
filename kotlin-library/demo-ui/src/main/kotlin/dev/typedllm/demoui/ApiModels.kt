package dev.typedllm.demoui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Shapes the HTTP API sends and receives. The browser only ever sees these.

@Serializable
data class FieldView(val name: String, val type: String, val required: Boolean)

@Serializable
data class TargetType(val name: String, val fields: List<FieldView>, val source: String, val schema: JsonObject)

@Serializable
data class ScenarioSummary(val id: String, val title: String, val description: String, val format: String, val message: String)

@Serializable
data class LiveModelSummary(val id: String, val label: String, val description: String, val model: String)

@Serializable
data class LiveRunRequest(val message: String, val modelId: String)

/** One answer from the model. [problem] is a plain-language version of [error]. */
@Serializable
data class AttemptView(val number: Int, val output: String, val accepted: Boolean, val problem: String?, val error: String?)

@Serializable
sealed interface Outcome {
    @Serializable
    @SerialName("success")
    data class Success(val value: JsonElement) : Outcome

    @Serializable
    @SerialName("failure")
    data class Failure(val attempts: Int) : Outcome
}

@Serializable
data class RunResult(val format: String, val attempts: List<AttemptView>, val outcome: Outcome)

@Serializable
data class ApiError(val error: String)
