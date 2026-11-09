package dev.typedllm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Implements evidence-aware extraction. This is intentionally separate from [TypedCallRunner]:
 * normal typed calls stay simple, while grounding can evolve independently.
 */
internal class ExtractionRunner(
    private val provider: Provider,
    private val format: OutputFormat,
    private val maxAttempts: Int,
    private val onAttempt: (Attempt) -> Unit,
) {
    suspend fun <T> run(
        prompt: String,
        serializer: KSerializer<T>,
        source: String,
        safeguards: Safeguards,
    ): Extraction<T> {
        val schema = SchemaGenerator.generate(serializer.descriptor)
        val fields = Grounding.fields(schema)
        val grounded = safeguards.grounded && fields != null && format == OutputFormat.JSON
        val modelSchema = if (grounded) Grounding.envelope(schema, fields!!, safeguards) else schema
        val systemPrompt = if (grounded) {
            PromptTemplates.groundedOutput(modelSchema, safeguards)
        } else {
            PromptTemplates.structuredOutput(schema, format)
        }
        val messages = mutableListOf(
            Message(Role.SYSTEM, systemPrompt),
            Message(Role.USER, prompt),
        )
        val nativeSchema = modelSchema.takeIf { format == OutputFormat.JSON }

        val attempts = mutableListOf<Attempt>()
        var candidate: ExtractionCandidate<T>? = null
        var targetedRetryUsed = false

        for (attemptNumber in 1..maxAttempts) {
            val response = provider.completeTimed(CompletionRequest(messages.toList(), nativeSchema))
            val raw = response.completion.text
            val step = decodeStep(raw, serializer, fields, source, safeguards, grounded)
            val error = (step as? ExtractionStep.Invalid)?.error
            val attempt = Attempt(
                number = attemptNumber,
                rawOutput = raw,
                error = error,
                usage = response.completion.usage,
                durationMs = response.durationMs,
            )
            attempts += attempt
            onAttempt(attempt)

            val lastAttempt = attemptNumber == maxAttempts
            when (step) {
                is ExtractionStep.Invalid -> {
                    if (!lastAttempt) {
                        messages += Message(Role.ASSISTANT, raw)
                        messages += Message(Role.USER, PromptTemplates.correction(step.error, format))
                    }
                }

                is ExtractionStep.Valid -> {
                    candidate = step.candidate
                    val shouldTargetRetry = safeguards.targetedRetry &&
                        !targetedRetryUsed &&
                        !lastAttempt &&
                        step.candidate.hasIssues()

                    if (!shouldTargetRetry) break

                    targetedRetryUsed = true
                    messages += Message(Role.ASSISTANT, raw)
                    messages += Message(
                        Role.USER,
                        PromptTemplates.targetedCorrection(
                            step.candidate.missingRequired,
                            step.candidate.fields,
                        ),
                    )
                }
            }
        }

        val found = candidate ?: return Extraction.Failed(attempts)
        return finalizeExtraction(found, fields, source, safeguards, attempts)
    }

    private fun <T> decodeStep(
        raw: String,
        serializer: KSerializer<T>,
        fields: List<PlanField>?,
        source: String,
        safeguards: Safeguards,
        grounded: Boolean,
    ): ExtractionStep<T> = try {
        if (grounded) {
            groundedStep(raw, serializer, requireNotNull(fields), source, safeguards)
        } else {
            plainStep(raw, serializer)
        }
    } catch (error: IllegalArgumentException) {
        ExtractionStep.Invalid(decodeError(error))
    }

    private fun <T> plainStep(raw: String, serializer: KSerializer<T>): ExtractionStep<T> {
        val value = format.decode(serializer, raw)
        val plain = Json.encodeToJsonElement(serializer, value) as? JsonObject ?: JsonObject(emptyMap())
        val reports = plain.map { (name, fieldValue) ->
            FieldReport(name, FieldStatus.FOUND, fieldValue)
        }
        return ExtractionStep.Valid(
            ExtractionCandidate(
                plain = plain,
                value = value,
                fields = reports,
                missingRequired = emptyList(),
            ),
        )
    }

    private fun <T> groundedStep(
        raw: String,
        serializer: KSerializer<T>,
        fields: List<PlanField>,
        source: String,
        safeguards: Safeguards,
    ): ExtractionStep<T> {
        val unwrapped = Grounding.unwrap(raw, fields, source, safeguards)
        val value = if (unwrapped.missingRequired.isEmpty()) {
            OutputFormat.JSON.decode(serializer, unwrapped.plain.toString())
        } else {
            null
        }
        return ExtractionStep.Valid(
            ExtractionCandidate(unwrapped.plain, value, unwrapped.fields, unwrapped.missingRequired),
        )
    }

    private suspend fun <T> finalizeExtraction(
        candidate: ExtractionCandidate<T>,
        fields: List<PlanField>?,
        source: String,
        safeguards: Safeguards,
        attempts: List<Attempt>,
    ): Extraction<T> {
        var reports = candidate.fields
        var crossCheckAttempt: Attempt? = null

        if (safeguards.crossCheck && fields != null) {
            val response = provider.completeTimed(Grounding.crossCheckRequest(fields, source))
            val quotes = Grounding.parseCrossCheck(response.completion.text, fields)
            crossCheckAttempt = Attempt(
                number = attempts.size + 1,
                rawOutput = response.completion.text,
                error = if (quotes == null) {
                    "Cross-check answer was not usable JSON; no fields were flagged by it."
                } else {
                    null
                },
                usage = response.completion.usage,
                durationMs = response.durationMs,
            )
            if (quotes != null) {
                reports = Grounding.applyCrossCheck(reports, quotes, fields, source)
            }
        }

        val flagged = reports
            .filter { it.status == FieldStatus.UNVERIFIED || it.status == FieldStatus.DISPUTED }
            .map { it.name }

        return when {
            candidate.missingRequired.isNotEmpty() || candidate.value == null ->
                Extraction.Incomplete(
                    partial = candidate.plain,
                    missing = candidate.missingRequired,
                    fields = reports,
                    attempts = attempts,
                    crossCheck = crossCheckAttempt,
                )

            flagged.isNotEmpty() ->
                Extraction.Ungrounded(
                    value = candidate.value,
                    flagged = flagged,
                    fields = reports,
                    attempts = attempts,
                    crossCheck = crossCheckAttempt,
                )

            else ->
                Extraction.Complete(
                    value = candidate.value,
                    fields = reports,
                    attempts = attempts,
                    crossCheck = crossCheckAttempt,
                )
        }
    }
}

/** A structurally usable extraction before the final safeguard classification. */
private data class ExtractionCandidate<T>(
    val plain: JsonObject,
    val value: T?,
    val fields: List<FieldReport>,
    val missingRequired: List<String>,
) {
    fun hasIssues(): Boolean =
        missingRequired.isNotEmpty() ||
            fields.any { it.status == FieldStatus.UNVERIFIED || it.note == Grounding.QUOTED_NO_VALUE }
}

private sealed interface ExtractionStep<out T> {
    data class Valid<T>(val candidate: ExtractionCandidate<T>) : ExtractionStep<T>
    data class Invalid(val error: String) : ExtractionStep<Nothing>
}
