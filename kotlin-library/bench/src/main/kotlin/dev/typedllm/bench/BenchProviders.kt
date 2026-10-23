package dev.typedllm.bench

import dev.typedllm.Completion
import dev.typedllm.CompletionRequest
import dev.typedllm.Provider
import dev.typedllm.gemini.GeminiProvider
import dev.typedllm.openai.OpenAiProvider
import java.time.Duration

/** Returns the case's scripted responses in order, repeating the last one if asked again (like the Python harness). */
class ScriptedProvider(private val responses: List<String>) : Provider {
    init {
        require(responses.isNotEmpty()) { "a case needs at least one scripted response" }
    }

    private var index = 0
    override val id: String = "scripted"

    override suspend fun complete(request: CompletionRequest): String = responses[minOf(index++, responses.lastIndex)]
}

/** Wraps any provider and records how many characters of prompt each request carried. */
class MeasuringProvider(private val inner: Provider) : Provider {
    val promptChars = mutableListOf<Int>()
    override val id: String get() = inner.id

    override suspend fun complete(request: CompletionRequest): String = completeWithUsage(request).text

    override suspend fun completeWithUsage(request: CompletionRequest): Completion {
        promptChars += request.messages.sumOf { it.content.length }
        return inner.completeWithUsage(request)
    }
}

/**
 * Builds a live provider from a spec, using the same environment variables as the Python harness so both
 * tracks can be pointed at the identical endpoint:
 *  - `live`               LIVE_BASE_URL / LIVE_API_KEY / LIVE_MODEL (default: Ollama, llama3.2:3b)
 *  - `ollama:<model>`     local Ollama
 *  - `openai:<model>`     api.openai.com with OPENAI_API_KEY
 *  - `gemini:<model>`     native Gemini API with GEMINI_API_KEY
 * [native] turns on provider-side schema enforcement where the provider supports it.
 */
fun liveProvider(spec: String, native: Boolean, env: Map<String, String> = System.getenv()): Provider {
    val kind = spec.substringBefore(':')
    val model = spec.substringAfter(':', missingDelimiterValue = "")
    val timeout = Duration.ofMinutes(2)
    return when (kind) {
        "live" -> OpenAiProvider(
            model = env["LIVE_MODEL"] ?: "llama3.2:3b",
            apiKey = env["LIVE_API_KEY"] ?: "ollama",
            nativeStructuredOutput = native,
            baseUrl = env["LIVE_BASE_URL"] ?: "http://localhost:11434/v1",
            timeout = timeout,
            temperature = 0.0,
        )
        "ollama" -> OpenAiProvider(model.ifEmpty { "llama3.2:3b" }, "ollama", native, "http://localhost:11434/v1", timeout, temperature = 0.0)
        "openai" -> OpenAiProvider(
            model.ifEmpty { "gpt-4o-mini" },
            env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() } ?: error("Set OPENAI_API_KEY"),
            native, timeout = timeout, temperature = 0.0,
        )
        "gemini" -> GeminiProvider(
            model.ifEmpty { "gemini-3.1-flash-lite" },
            env["GEMINI_API_KEY"]?.takeIf { it.isNotBlank() } ?: error("Set GEMINI_API_KEY"),
            native, temperature = 0.0, timeout = timeout,
        )
        else -> error("Unknown provider spec '$spec'. Use live, ollama:<model>, openai:<model> or gemini:<model>.")
    }
}
