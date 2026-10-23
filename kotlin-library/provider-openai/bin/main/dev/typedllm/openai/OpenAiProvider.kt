package dev.typedllm.openai

import dev.typedllm.CompletionRequest
import dev.typedllm.Provider
import dev.typedllm.ProviderException
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
 * Calls the OpenAI Chat Completions API, or any API that copies it (Azure OpenAI, Ollama, Groq) via [baseUrl].
 *
 * With [nativeStructuredOutput] on, the schema is sent as a strict `json_schema` response format, so the model
 * is constrained to it while generating. Turn it off for models without that feature: the library then relies
 * on the schema in the prompt plus validation and retries.
 */
class OpenAiProvider(
    private val model: String,
    private val apiKey: String,
    private val nativeStructuredOutput: Boolean,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeout: Duration = Duration.ofSeconds(60),
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
) : Provider {

    override suspend fun complete(request: CompletionRequest): String {
        val body = wireJson.encodeToString(toChatRequest(request))
        val httpRequest = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
            .timeout(timeout)
            .header("Authorization", "Bearer $apiKey")
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
        return extractContent(response.body())
    }

    private fun toChatRequest(request: CompletionRequest): ChatRequest {
        val strictSchema = request.responseSchema
            ?.takeIf { nativeStructuredOutput }
            ?.let(StrictSchema::fromOrNull)

        return ChatRequest(
            model = model,
            messages = request.messages.map { ChatMessage(it.role.name.lowercase(), it.content) },
            responseFormat = strictSchema?.let { ResponseFormat(jsonSchema = JsonSchemaSpec(schema = it)) },
        )
    }

    private fun extractContent(body: String): String {
        val message = wireJson.decodeFromString<ChatResponse>(body).choices.firstOrNull()?.message
            ?: throw ProviderException("The response had no choices")
        message.refusal?.let { throw ProviderException("Model refused: $it") }
        return message.content ?: throw ProviderException("The response had no content")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val MAX_ERROR_CHARS = 500

        /** Ignores response fields we don't use; never sends unset optional fields. */
        private val wireJson = Json { ignoreUnknownKeys = true }

        /** Reads the key from the OPENAI_API_KEY environment variable so it never appears in code. */
        fun fromEnvironment(model: String, nativeStructuredOutput: Boolean): OpenAiProvider {
            val key = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Set the OPENAI_API_KEY environment variable")
            return OpenAiProvider(model, key, nativeStructuredOutput)
        }
    }
}
