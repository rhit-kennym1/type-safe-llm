package dev.typedllm.demoui

import dev.typedllm.Provider
import dev.typedllm.openai.OpenAiProvider
import java.time.Duration

/** One choice in the "Live model" tab. */
data class LiveModel(
    val id: String,
    val label: String,
    val description: String,
    val model: String,
    val enforceSchema: Boolean,
) {
    fun summary() = LiveModelSummary(id, label, description, model)
}

/**
 * Where live runs go. Defaults to Ollama on this machine: free, no account, no API key.
 * Any OpenAI-compatible endpoint works by setting LIVE_BASE_URL and LIVE_API_KEY.
 */
data class LiveConfig(val baseUrl: String, val apiKey: String, val models: List<LiveModel>) {

    fun providerFor(model: LiveModel): Provider =
        OpenAiProvider(model.model, apiKey, model.enforceSchema, baseUrl, timeout = Duration.ofMinutes(2))

    companion object {
        private const val OLLAMA_URL = "http://localhost:11434/v1"
        private const val DEFAULT_MODEL = "llama3.2:3b"

        fun fromEnvironment(env: Map<String, String> = System.getenv()): LiveConfig {
            val model = env["LIVE_MODEL"] ?: DEFAULT_MODEL
            return LiveConfig(
                baseUrl = env["LIVE_BASE_URL"] ?: OLLAMA_URL,
                apiKey = env["LIVE_API_KEY"] ?: "ollama", // Ollama ignores the key, but the header must be present
                models = listOf(
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
                ),
            )
        }
    }
}
