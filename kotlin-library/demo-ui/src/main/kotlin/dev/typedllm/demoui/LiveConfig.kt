package dev.typedllm.demoui

import dev.typedllm.Provider
import dev.typedllm.gemini.GeminiProvider
import dev.typedllm.openai.OpenAiProvider
import java.time.Duration

/** Which wire protocol a live model speaks. */
enum class Backend { OPENAI_COMPATIBLE, GEMINI }

/** One choice in the "Live model" tab. */
data class LiveModel(
    val id: String,
    val label: String,
    val description: String,
    val model: String,
    val enforceSchema: Boolean,
    val backend: Backend = Backend.OPENAI_COMPATIBLE,
) {
    fun summary() = LiveModelSummary(id, label, description, model)
}

/**
 * Where live runs go. Defaults to Ollama on this machine: free, no account, no API key.
 * Any OpenAI-compatible endpoint works by setting LIVE_BASE_URL and LIVE_API_KEY.
 * Setting GEMINI_API_KEY adds two native Gemini choices (model from GEMINI_MODEL).
 */
data class LiveConfig(
    val baseUrl: String,
    val apiKey: String,
    val models: List<LiveModel>,
    val geminiApiKey: String? = null,
) {

    fun providerFor(model: LiveModel): Provider = when (model.backend) {
        Backend.OPENAI_COMPATIBLE ->
            OpenAiProvider(model.model, apiKey, model.enforceSchema, baseUrl, timeout = TIMEOUT)
        Backend.GEMINI ->
            GeminiProvider(
                model.model,
                geminiApiKey ?: error("GEMINI_API_KEY is not set"),
                model.enforceSchema,
                timeout = TIMEOUT,
            )
    }

    companion object {
        private const val OLLAMA_URL = "http://localhost:11434/v1"
        private const val DEFAULT_MODEL = "llama3.2:3b"
        private const val DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite"
        private val TIMEOUT: Duration = Duration.ofMinutes(2)

        fun fromEnvironment(env: Map<String, String> = System.getenv()): LiveConfig {
            val model = env["LIVE_MODEL"] ?: DEFAULT_MODEL
            val geminiKey = env["GEMINI_API_KEY"]?.takeIf { it.isNotBlank() }
            val geminiModel = env["GEMINI_MODEL"] ?: DEFAULT_GEMINI_MODEL
            val localModels = listOf(
                LiveModel(
                    id = "prompt-only",
                    label = "Asked nicely",
                    description = "The fields are described in the prompt. Nothing forces the model to follow them.",
                    model = env["LIVE_PROMPT_ONLY_MODEL"] ?: model,
                    enforceSchema = false,
                ),
                LiveModel(
                    id = "enforced",
                    label = "Enforced",
                    description = "The fields are also sent to the provider, which only lets the model write answers that fit.",
                    model = env["LIVE_ENFORCED_MODEL"] ?: model,
                    enforceSchema = true,
                ),
            )
            val geminiModels = if (geminiKey == null) emptyList() else listOf(
                LiveModel(
                    id = "gemini-prompt-only",
                    label = "Gemini, asked nicely",
                    description = "Native Gemini API. Fields described in the prompt only.",
                    model = geminiModel,
                    enforceSchema = false,
                    backend = Backend.GEMINI,
                ),
                LiveModel(
                    id = "gemini-enforced",
                    label = "Gemini, enforced",
                    description = "Native Gemini API with responseJsonSchema. Gemini ignores schema keywords it doesn't support, so answers are still checked.",
                    model = geminiModel,
                    enforceSchema = true,
                    backend = Backend.GEMINI,
                ),
            )
            return LiveConfig(
                baseUrl = env["LIVE_BASE_URL"] ?: OLLAMA_URL,
                apiKey = env["LIVE_API_KEY"] ?: "ollama", // Ollama ignores the key, but the header must be present
                models = localModels + geminiModels,
                geminiApiKey = geminiKey,
            )
        }
    }
}
