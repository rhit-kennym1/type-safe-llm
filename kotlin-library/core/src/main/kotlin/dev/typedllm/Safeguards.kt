package dev.typedllm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Optional defences against the model inventing values for fields the source doesn't contain.
 * Each one can be switched on independently so their effect can be measured separately.
 *
 * Without any of them, [TypedLLM.extract] behaves like [TypedLLM.call]: a missing required field is
 * reported back to the model as an error, which pushes it to fill the field in, with or without evidence.
 */
data class Safeguards(
    /** Every field gets a `status` of "found" or "missing", and a null value is allowed, so the model can say "not in the source". */
    val allowMissing: Boolean = false,
    /** Every field carries the exact quote it came from, written before the value. The library checks the quote is in the source and the value is in the quote. */
    val requireEvidence: Boolean = false,
    /** Missing or unsupported fields get one targeted follow-up ("quote it or mark it missing") instead of a generic "fix the error". */
    val targetedRetry: Boolean = false,
    /** A second, differently shaped call asks only for supporting quotes; fields it can't support are flagged. Costs one extra call. */
    val crossCheck: Boolean = false,
) {
    /** True when the model answers in per-field envelopes rather than the plain type. */
    val grounded: Boolean get() = allowMissing || requireEvidence

    companion object {
        val NONE = Safeguards()
        val ALL = Safeguards(allowMissing = true, requireEvidence = true, targetedRetry = true, crossCheck = true)
    }
}

/** How much the library trusts one top-level field of the result. */
enum class FieldStatus {
    /** Present, and supported by the source as far as the enabled checks can tell. */
    FOUND,
    /** The model said the source doesn't contain it. */
    MISSING,
    /** The model gave a value, but its quote isn't in the source or the value isn't in its quote. */
    UNVERIFIED,
    /** The independent cross-check found no supporting text for a value the model gave. */
    DISPUTED,
}

data class FieldReport(
    val name: String,
    val status: FieldStatus,
    val value: JsonElement? = null,
    val quote: String? = null,
    val note: String? = null,
)

/**
 * The result of [TypedLLM.extract]. Every outcome is explicit: the caller never receives an unchecked value.
 * [attempts] are the main loop's round trips; [crossCheck] is the extra call when that safeguard is on.
 */
sealed interface Extraction<out T> {
    val attempts: List<Attempt>
    val fields: List<FieldReport>
    val crossCheck: Attempt?

    /** Every required field present, and nothing flagged by the enabled checks. */
    data class Complete<T>(
        val value: T,
        override val fields: List<FieldReport>,
        override val attempts: List<Attempt>,
        override val crossCheck: Attempt? = null,
    ) : Extraction<T>

    /** The source doesn't contain some required fields. [partial] holds what was found; nothing was invented. */
    data class Incomplete(
        val partial: JsonObject,
        val missing: List<String>,
        override val fields: List<FieldReport>,
        override val attempts: List<Attempt>,
        override val crossCheck: Attempt? = null,
    ) : Extraction<Nothing>

    /** A complete value, but some fields lack support in the source. Treat [flagged] fields as possibly invented. */
    data class Ungrounded<T>(
        val value: T,
        val flagged: List<String>,
        override val fields: List<FieldReport>,
        override val attempts: List<Attempt>,
        override val crossCheck: Attempt? = null,
    ) : Extraction<T>

    /** The model never produced the required structure. */
    data class Failed(
        override val attempts: List<Attempt>,
        override val fields: List<FieldReport> = emptyList(),
        override val crossCheck: Attempt? = null,
    ) : Extraction<Nothing>
}
