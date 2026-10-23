package dev.typedllm.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Request and response shapes for /chat/completions. Only the fields this provider uses.

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

@Serializable
internal data class ChatMessage(val role: String, val content: String)

@Serializable
internal data class ResponseFormat(
    val type: String = "json_schema",
    @SerialName("json_schema") val jsonSchema: JsonSchemaSpec,
)

@Serializable
internal data class JsonSchemaSpec(
    val name: String = "response",
    val strict: Boolean = true,
    val schema: JsonObject,
)

@Serializable
internal data class ChatResponse(val choices: List<Choice>)

@Serializable
internal data class Choice(val message: ResponseMessage)

@Serializable
internal data class ResponseMessage(val content: String? = null, val refusal: String? = null)
