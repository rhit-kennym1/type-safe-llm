package dev.typedllm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Returns a fully valid instance of the requested type, or throws [TypedOutputException].
 * Nothing in between.
 *
 * [call] is the original behaviour. [extract] adds the optional [Safeguards] against invented values and
 * reports every outcome explicitly instead of throwing.
 */
class TypedLLM(
    private val provider: Provider,
    val format: OutputFormat = OutputFormat.JSON,
    val maxAttempts: Int = 3,
    private val onAttempt: (Attempt) -> Unit = {},
    val safeguards: Safeguards = Safeguards.NONE,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** Reified: the type survives to runtime, so `call<List<Clause>>(...)` just works. No TypeReference. */
    suspend inline fun <reified T> call(prompt: String): T = call(prompt, serializer<T>())

    @OptIn(ExperimentalSerializationApi::class) // descriptor.serialName, used to name the type in errors
    suspend fun <T> call(prompt: String, serializer: KSerializer<T>): T {
        val schema = SchemaGenerator.generate(serializer.descriptor)
        // Native structured output only exists for JSON, so the schema is offered to the provider only then.
        val nativeSchema = schema.takeIf { format == OutputFormat.JSON }
        val messages = mutableListOf(
            Message(Role.SYSTEM, systemPrompt(schema)),
            Message(Role.USER, prompt),
        )
        val attempts = mutableListOf<Attempt>()

        repeat(maxAttempts) { i ->
            val started = System.nanoTime()
            val completion = provider.completeWithUsage(CompletionRequest(messages.toList(), nativeSchema))
            val durationMs = (System.nanoTime() - started) / 1_000_000
            val raw = completion.text
            val error = try {
                val value = format.decode(serializer, raw)
                onAttempt(Attempt(i + 1, raw, null, completion.usage, durationMs))
                return value
            } catch (e: IllegalArgumentException) { // SerializationException is a subclass; so are init-block require() failures
                e.message ?: e::class.simpleName ?: "Unknown decode error"
            }
            val attempt = Attempt(i + 1, raw, error, completion.usage, durationMs)
            attempts += attempt
            onAttempt(attempt)
            messages += Message(Role.ASSISTANT, raw)
            messages += Message(Role.USER, correction(error))
        }
        throw TypedOutputException(serializer.descriptor.serialName, attempts)
    }

    suspend inline fun <reified T> extract(prompt: String, source: String = prompt, safeguards: Safeguards = this.safeguards): Extraction<T> =
        extract(prompt, serializer<T>(), source, safeguards)

    /**
     * Like [call], but with [safeguards] against invented values, and every outcome returned rather than thrown.
     * [source] is the text values must come from; it defaults to the whole prompt.
     *
     * With [Safeguards.NONE] the loop is exactly [call]'s, so the two can be compared fairly.
     */
    suspend fun <T> extract(
        prompt: String,
        serializer: KSerializer<T>,
        source: String = prompt,
        safeguards: Safeguards = this.safeguards,
    ): Extraction<T> {
        val schema = SchemaGenerator.generate(serializer.descriptor)
        val fields = Grounding.fields(schema)
        // Grounding needs an object root and JSON output; otherwise fall back to the plain loop.
        val grounded = safeguards.grounded && fields != null && format == OutputFormat.JSON
        val modelSchema = if (grounded) Grounding.envelope(schema, fields!!, safeguards) else schema
        val system = if (grounded) groundedSystemPrompt(modelSchema, safeguards) else systemPrompt(schema)
        val messages = mutableListOf(Message(Role.SYSTEM, system), Message(Role.USER, prompt))
        val nativeSchema = modelSchema.takeIf { format == OutputFormat.JSON }

        val attempts = mutableListOf<Attempt>()
        var candidate: Candidate<T>? = null
        var targetedUsed = false

        for (i in 0 until maxAttempts) {
            val started = System.nanoTime()
            val completion = provider.completeWithUsage(CompletionRequest(messages.toList(), nativeSchema))
            val durationMs = (System.nanoTime() - started) / 1_000_000
            val raw = completion.text

            val step: Step<T> = try {
                if (grounded) groundedStep(raw, serializer, fields!!, source, safeguards) else plainStep(raw, serializer)
            } catch (e: IllegalArgumentException) {
                Step.Invalid(e.message ?: e::class.simpleName ?: "Unknown decode error")
            }

            val error = (step as? Step.Invalid)?.error
            val attempt = Attempt(i + 1, raw, error, completion.usage, durationMs)
            attempts += attempt
            onAttempt(attempt)
            val lastTry = i == maxAttempts - 1

            when (step) {
                is Step.Invalid -> {
                    if (!lastTry) {
                        messages += Message(Role.ASSISTANT, raw)
                        messages += Message(Role.USER, correction(step.error))
                    }
                }
                is Step.Valid -> {
                    candidate = step.candidate
                    val needsFollowUp = safeguards.targetedRetry && !targetedUsed && !lastTry && step.candidate.hasIssues()
                    if (!needsFollowUp) break
                    targetedUsed = true
                    messages += Message(Role.ASSISTANT, raw)
                    messages += Message(Role.USER, targetedFollowUp(step.candidate))
                }
            }
        }

        val found = candidate ?: return Extraction.Failed(attempts)
        var reports = found.fields
        var crossAttempt: Attempt? = null
        if (safeguards.crossCheck && fields != null) {
            val request = Grounding.crossCheckRequest(fields, source)
            val started = System.nanoTime()
            val completion = provider.completeWithUsage(request)
            val quotes = Grounding.parseCrossCheck(completion.text, fields)
            crossAttempt = Attempt(
                number = attempts.size + 1,
                rawOutput = completion.text,
                error = if (quotes == null) "Cross-check answer was not usable JSON; no fields were flagged by it." else null,
                usage = completion.usage,
                durationMs = (System.nanoTime() - started) / 1_000_000,
            )
            if (quotes != null) reports = Grounding.applyCrossCheck(reports, quotes, fields, source)
        }

        val flagged = reports.filter { it.status == FieldStatus.UNVERIFIED || it.status == FieldStatus.DISPUTED }.map { it.name }
        return when {
            found.missingRequired.isNotEmpty() || found.value == null ->
                Extraction.Incomplete(found.plain, found.missingRequired, reports, attempts, crossAttempt)
            flagged.isNotEmpty() -> Extraction.Ungrounded(found.value, flagged, reports, attempts, crossAttempt)
            else -> Extraction.Complete(found.value, reports, attempts, crossAttempt)
        }
    }

    /** One usable answer: the plain JSON, the decoded value (null when required fields are missing), and its field report. */
    private class Candidate<T>(val plain: JsonObject, val value: T?, val fields: List<FieldReport>, val missingRequired: List<String>) {
        fun hasIssues() = missingRequired.isNotEmpty() || fields.any { it.status == FieldStatus.UNVERIFIED || it.note == Grounding.QUOTED_NO_VALUE }
    }

    private sealed interface Step<out T> {
        class Valid<T>(val candidate: Candidate<T>) : Step<T>
        class Invalid(val error: String) : Step<Nothing>
    }

    private fun <T> plainStep(raw: String, serializer: KSerializer<T>): Step<T> {
        val value = format.decode(serializer, raw)
        val plain = Json.encodeToJsonElement(serializer, value) as? JsonObject ?: JsonObject(emptyMap())
        val reports = plain.map { (name, v) -> FieldReport(name, FieldStatus.FOUND, v) }
        return Step.Valid(Candidate(plain, value, reports, emptyList()))
    }

    private fun <T> groundedStep(raw: String, serializer: KSerializer<T>, fields: List<PlanField>, source: String, safeguards: Safeguards): Step<T> {
        val unwrapped = Grounding.unwrap(raw, fields, source, safeguards)
        // Required fields the source lacks mean the type can't be built: that's an Incomplete result, not a decode error.
        val value = if (unwrapped.missingRequired.isEmpty()) OutputFormat.JSON.decode(serializer, unwrapped.plain.toString()) else null
        return Step.Valid(Candidate(unwrapped.plain, value, unwrapped.fields, unwrapped.missingRequired))
    }

    private fun systemPrompt(schema: JsonObject) =
        "You are a structured-output generator. Respond with ONLY a ${format.displayName} document " +
            "whose structure conforms to the JSON Schema below. No prose, no explanation, no extra fields.\n" +
            "Schema:\n$schema"

    private fun groundedSystemPrompt(schema: JsonObject, safeguards: Safeguards) =
        "You are a structured-output generator. Respond with ONLY a JSON document whose structure conforms to the " +
            "JSON Schema below. No prose, no explanation, no extra fields.\n" +
            Grounding.instructions(safeguards) + "\n" +
            "Schema:\n$schema"

    private fun correction(error: String) =
        "Your previous response could not be decoded into the required type.\n" +
            "Error: $error\n" +
            "Respond again with ONLY a corrected ${format.displayName} document."

    private fun targetedFollowUp(candidate: Candidate<*>): String = buildString {
        if (candidate.missingRequired.isNotEmpty()) {
            append("You marked these required fields as missing: ${candidate.missingRequired.joinToString()}. ")
            append("Read the source again. If it states them, give the exact quote and the value. If it does not, keep them missing. Do not guess.\n")
        }
        val quotedButEmpty = candidate.fields.filter { it.note == Grounding.QUOTED_NO_VALUE }
        if (quotedButEmpty.isNotEmpty()) {
            append("You quoted source text but gave no value for: ")
            append(quotedButEmpty.joinToString { "${it.name} (\"${it.quote}\")" })
            append(". If that text states the value, give the value.\n")
        }
        val unsupported = candidate.fields.filter { it.status == FieldStatus.UNVERIFIED }
        if (unsupported.isNotEmpty()) {
            append("These values are not supported by the source: ")
            append(unsupported.joinToString { "${it.name} (${it.note})" })
            append(". Copy the exact words from the source that state each value, or leave the quote empty and the value null if the source does not state it.\n")
        }
        append("Respond again with ONLY the complete JSON document.")
    }
}
