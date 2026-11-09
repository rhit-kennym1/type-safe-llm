package dev.typedllm

import kotlinx.serialization.json.JsonObject

/** Prompt text used by the retry loops. Keeping it here makes model-facing behavior easy to review. */
internal object PromptTemplates {
    fun structuredOutput(schema: JsonObject, format: OutputFormat): String =
        "You are a structured-output generator. Respond with ONLY a ${format.displayName} document " +
            "whose structure conforms to the JSON Schema below. No prose, no explanation, no extra fields.\n" +
            "Schema:\n$schema"

    fun groundedOutput(schema: JsonObject, safeguards: Safeguards): String =
        "You are a structured-output generator. Respond with ONLY a JSON document whose structure conforms to the " +
            "JSON Schema below. No prose, no explanation, no extra fields.\n" +
            Grounding.instructions(safeguards) + "\n" +
            "Schema:\n$schema"

    fun correction(error: String, format: OutputFormat): String =
        "Your previous response could not be decoded into the required type.\n" +
            "Error: $error\n" +
            "Respond again with ONLY a corrected ${format.displayName} document."

    fun targetedCorrection(missingRequired: List<String>, fields: List<FieldReport>): String = buildString {
        if (missingRequired.isNotEmpty()) {
            append("You marked these required fields as missing: ${missingRequired.joinToString()}. ")
            append("Read the source again. If it states them, give the exact quote and the value. ")
            append("If it does not, keep them missing. Do not guess.\n")
        }

        val quotedButEmpty = fields.filter { it.note == Grounding.QUOTED_NO_VALUE }
        if (quotedButEmpty.isNotEmpty()) {
            append("You quoted source text but gave no value for: ")
            append(quotedButEmpty.joinToString { "${it.name} (\"${it.quote}\")" })
            append(". If that text states the value, give the value.\n")
        }

        val unsupported = fields.filter { it.status == FieldStatus.UNVERIFIED }
        if (unsupported.isNotEmpty()) {
            append("These values are not supported by the source: ")
            append(unsupported.joinToString { "${it.name} (${it.note})" })
            append(". Copy the exact words from the source that state each value, or leave the quote empty ")
            append("and the value null if the source does not state it.\n")
        }

        append("Respond again with ONLY the complete JSON document.")
    }
}
