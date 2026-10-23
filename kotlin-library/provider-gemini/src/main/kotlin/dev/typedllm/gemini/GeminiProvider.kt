package dev.typedllm.gemini

import dev.typedllm.Completion
import dev.typedllm.CompletionRequest
import dev.typedllm.Provider
import dev.typedllm.ProviderException
import dev.typedllm.Role
import dev.typedllm.TokenUsage
import kotlinx.coroutines.future.await
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Calls Google's Gemini API natively (`models/{model}:generateContent`), not through its OpenAI-compatible shim.
 *
 * With [nativeStructuredOutput] on, JSON requests send the schema as `responseJsonSchema` with
 * `responseMimeType: application/json`, so Gemini constrains generation to it. Gemini ignores schema keywords
 * it doesn't support instead of rejecting them, which is one reason the library validates every response.
 *
 * Message mapping: SYSTEM messages become `systemInstruction`; USER stays `user`; ASSISTANT becomes `model`.
 * Token usage counts thinking tokens as output, since Gemini bills them that way.
 */
class GeminiProvider(
    private val model: String,
    private val apiKey: String,
    private val nativeStructuredOutput: Boolean = true,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val temperature: Double? = null,
    private val timeout: Duration = Duration.ofSeconds(60),
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
) : Provider {

    override val id: String = "gemini:$model"

    override suspend fun complete(request: CompletionRequest): String = completeWithUsage(request).text

    override suspend fun completeWithUsage(request: CompletionRequest): Completion {
        val body = wireJson.encodeToString(toGenerateRequest(request))
        val httpRequest = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/models/$model:generateContent"))
            .timeout(timeout)
            .header("x-goog-api-key", apiKey) // header, not ?key=, so the key never lands in URLs or logs
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            throw ProviderException("Could not reach $baseUrl: ${e.message}", e)
        }

        if (response.statusCode() !in 200..299) {
            throw ProviderException("$baseUrl returned HTTP ${response.statusCode()}: ${response.body().take(MAX_ERROR_CHARS)}")
        }
        return parse(response.body())
    }

    private fun toGenerateRequest(request: CompletionRequest): GenerateRequest {
        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val contents = request.messages.filter { it.role != Role.SYSTEM }.map {
            Content(parts = listOf(Part(text = it.content)), role = if (it.role == Role.ASSISTANT) "model" else "user")
        }
        val schema = request.responseSchema?.takeIf { nativeStructuredOutput }?.let(GeminiSchema::adapt)
        val config = if (schema != null || temperature != null) {
            GenerationConfig(
                responseMimeType = schema?.let { "application/json" },
                responseJsonSchema = schema,
                temperature = temperature,
            )
        } else {
            null
        }
        return GenerateRequest(
            contents = contents,
            systemInstruction = system.takeIf { it.isNotEmpty() }?.let { Content(parts = listOf(Part(text = it))) },
            generationConfig = config,
        )
    }

    private fun parse(body: String): Completion {
        val response = wireJson.decodeFromString<GenerateResponse>(body)
        response.promptFeedback?.blockReason?.let { throw ProviderException("Gemini blocked the prompt: $it") }
        val candidate = response.candidates.firstOrNull() ?: throw ProviderException("The response had no candidates")
        val text = candidate.content?.parts.orEmpty()
            .filter { it.thought != true } // thinking summaries are not the answer
            .mapNotNull { it.text }
            .joinToString("")
        if (text.isEmpty() && candidate.finishReason in BLOCKING_FINISH_REASONS) {
            throw ProviderException("Gemini stopped without an answer: ${candidate.finishReason}")
        }
        val usage = response.usageMetadata?.let {
            TokenUsage(inputTokens = it.promptTokenCount, outputTokens = it.candidatesTokenCount + it.thoughtsTokenCount)
        }
        return Completion(text, usage)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val MAX_ERROR_CHARS = 500
        private val BLOCKING_FINISH_REASONS = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")

        /** Ignores response fields we don't use; never sends unset optional fields. */
        private val wireJson = Json { ignoreUnknownKeys = true }

        /** Reads the key from GEMINI_API_KEY so it never appears in code. */
        fun fromEnvironment(model: String, nativeStructuredOutput: Boolean = true, temperature: Double? = null): GeminiProvider {
            val key = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Set the GEMINI_API_KEY environment variable")
            return GeminiProvider(model, key, nativeStructuredOutput, temperature = temperature)
        }
    }
}
