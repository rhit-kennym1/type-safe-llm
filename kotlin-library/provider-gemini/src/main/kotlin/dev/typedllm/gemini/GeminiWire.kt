package dev.typedllm.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Request and response shapes for models/{model}:generateContent. Only the fields this provider uses.
// The REST API uses camelCase JSON, so property names map directly.

@Serializable
internal data class GenerateRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig? = null,
)

@Serializable
internal data class Content(val parts: List<Part>, val role: String? = null)

@Serializable
internal data class Part(val text: String? = null, val thought: Boolean? = null)

@Serializable
internal data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseJsonSchema: JsonObject? = null,
    val temperature: Double? = null,
)

@Serializable
internal data class GenerateResponse(
    val candidates: List<Candidate> = emptyList(),
    val usageMetadata: UsageMetadata? = null,
    val promptFeedback: PromptFeedback? = null,
)

@Serializable
internal data class Candidate(val content: Content? = null, val finishReason: String? = null)

@Serializable
internal data class UsageMetadata(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val thoughtsTokenCount: Int = 0,
)

@Serializable
internal data class PromptFeedback(val blockReason: String? = null)
