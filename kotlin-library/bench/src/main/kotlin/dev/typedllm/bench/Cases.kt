package dev.typedllm.bench

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

// The shared case corpus (experiments/cases/cases.json) and the result format both tracks write.
// Field names here are the contract with experiments/compare.py and the Python harness.

@Serializable
data class CaseFile(
    val version: Int,
    @SerialName("max_attempts") val maxAttempts: Int,
    val cases: List<BenchCase>,
)

@Serializable
data class BenchCase(
    val id: String,
    val target: String,
    val category: String,
    val source: String,
    val responses: List<String>,
    val ideal: String,
    val detectable: Boolean,
    val probes: String,
)

@Serializable
data class AttemptRecord(
    val n: Int,
    val ok: Boolean,
    val error: String? = null,
    /** Characters of prompt text sent for this attempt (every message in the request). */
    @SerialName("prompt_chars") val promptChars: Int,
    @SerialName("response_chars") val responseChars: Int,
    /** Provider-reported tokens; null in scripted mode or when the provider doesn't report them. */
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    /** Time spent waiting on the provider for this attempt. */
    @SerialName("latency_ms") val latencyMs: Double,
)

@Serializable
data class CaseRecord(
    val track: String,
    val mode: String,
    val provider: String,
    @SerialName("case_id") val caseId: String,
    val target: String,
    val category: String,
    val repeat: Int,
    val ok: Boolean,
    /** validated | retries_exhausted | provider_error */
    val status: String,
    val attempts: List<AttemptRecord>,
    /** Wall time for the whole extract-validate-retry loop, provider time included. */
    @SerialName("loop_ms") val loopMs: Double,
    /** Scripted mode only: repeated timings of the same loop, for median and p95 of library overhead. */
    @SerialName("overhead_samples_ms") val overheadSamplesMs: List<Double> = emptyList(),
    /** The validated value as the track returned it, so data differences (e.g. whitespace) are visible. */
    val data: JsonElement? = null,
    val error: String? = null,
)

internal val benchJson = Json { ignoreUnknownKeys = true }
internal val recordJson = Json { encodeDefaults = true; explicitNulls = true }

fun loadCases(path: File): CaseFile = benchJson.decodeFromString(CaseFile.serializer(), path.readText())
