package dev.typedllm.bench

import dev.typedllm.Attempt
import dev.typedllm.OutputFormat
import dev.typedllm.Provider
import dev.typedllm.ProviderException
import dev.typedllm.SchemaGenerator
import dev.typedllm.TypedLLM
import dev.typedllm.TypedOutputException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/** Runs one case through the real library and records everything the report needs. */
suspend fun runCase(
    case: BenchCase,
    provider: Provider,
    maxAttempts: Int,
    mode: String,
    repeat: Int = 0,
): CaseRecord {
    val serializer = BENCH_TARGETS[case.target] ?: error("Unknown target '${case.target}' in case ${case.id}")
    @Suppress("UNCHECKED_CAST")
    return runTyped(case, serializer as KSerializer<Any?>, provider, maxAttempts, mode, repeat)
}

private suspend fun <T> runTyped(
    case: BenchCase,
    serializer: KSerializer<T>,
    provider: Provider,
    maxAttempts: Int,
    mode: String,
    repeat: Int,
): CaseRecord {
    val measuring = MeasuringProvider(provider)
    val attempts = mutableListOf<Attempt>()
    val llm = TypedLLM(measuring, OutputFormat.JSON, maxAttempts, onAttempt = { attempts += it })

    val started = System.nanoTime()
    var data: JsonElement? = null
    var error: String? = null
    val status = try {
        val value = llm.call(prompt(case.source), serializer)
        data = Json.encodeToJsonElement(serializer, value)
        "validated"
    } catch (e: TypedOutputException) {
        "retries_exhausted"
    } catch (e: ProviderException) {
        error = e.message
        "provider_error"
    }
    val loopMs = (System.nanoTime() - started) / 1e6

    return CaseRecord(
        track = TRACK,
        mode = mode,
        provider = provider.id,
        caseId = case.id,
        target = case.target,
        category = case.category,
        repeat = repeat,
        ok = status == "validated",
        status = status,
        attempts = attempts.mapIndexed { i, a ->
            AttemptRecord(
                n = a.number,
                ok = a.succeeded,
                error = a.error,
                promptChars = measuring.promptChars.getOrElse(i) { 0 },
                responseChars = a.rawOutput.length,
                inputTokens = a.usage?.inputTokens,
                outputTokens = a.usage?.outputTokens,
                latencyMs = a.durationMs.toDouble(),
            )
        },
        loopMs = loopMs,
        data = data,
        error = error,
    )
}

/** The library's user prompt for every case; TypedLLM adds its own system prompt carrying the schema. */
fun prompt(source: String) = "Extract the structured data from this text:\n\n$source"

const val TRACK = "kotlin-library"

private data class Options(
    val cases: File,
    val out: File,
    val provider: String,
    val native: Boolean,
    val repeat: Int,
    val timingRuns: Int,
    val schemasOut: File?,
    val only: Set<String>,
)

private fun parse(args: Array<String>): Options {
    val map = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val provider = map["provider"] ?: "scripted"
    return Options(
        cases = File(map["cases"] ?: "experiments/cases/cases.json"),
        out = File(map["out"] ?: "experiments/results/kotlin-library-$provider.jsonl".replace(':', '_')),
        provider = provider,
        native = map["native"]?.toBooleanStrict() ?: false,
        repeat = map["repeat"]?.toInt() ?: 1,
        timingRuns = map["timing-runs"]?.toInt() ?: if (provider == "scripted") 50 else 0,
        schemasOut = map["schemas-out"]?.let(::File),
        only = map["only"]?.split(',')?.toSet() ?: emptySet(),
    )
}

/**
 * Usage (from the repo root):
 *   ./gradlew -p kotlin-library :bench:run --args="--provider scripted"
 *   ./gradlew -p kotlin-library :bench:run --args="--provider live --native false --repeat 3"
 *   ./gradlew -p kotlin-library :bench:run --args="--provider gemini:gemini-3.1-flash-lite --native true --repeat 3"
 * Options: --cases --out --provider --native --repeat --timing-runs --schemas-out --only id1,id2
 */
fun main(args: Array<String>) = runBlocking {
    val opts = parse(args)
    val file = loadCases(opts.cases)
    val cases = file.cases.filter { opts.only.isEmpty() || it.id in opts.only }
    val scripted = opts.provider == "scripted"
    val mode = if (scripted) "scripted" else "live"

    opts.schemasOut?.let { dir ->
        dir.mkdirs()
        val pretty = Json { prettyPrint = true }
        for ((name, serializer) in BENCH_TARGETS) {
            File(dir, "$name.json").writeText(pretty.encodeToString(JsonElement.serializer(), SchemaGenerator.generate(serializer.descriptor)) + "\n")
        }
        println("wrote ${BENCH_TARGETS.size} schemas to $dir")
    }

    if (scripted && opts.timingRuns > 0) {
        // Warm the JIT so timings measure steady-state library overhead, not class loading.
        repeat(3) { for (case in cases) runCase(case, ScriptedProvider(case.responses), file.maxAttempts, mode) }
    }

    opts.out.parentFile?.mkdirs()
    opts.out.bufferedWriter().use { writer ->
        for (case in cases) {
            for (r in 0 until opts.repeat) {
                val provider = if (scripted) ScriptedProvider(case.responses) else liveProvider(opts.provider, opts.native)
                var record = runCase(case, provider, file.maxAttempts, mode, r)
                if (scripted && opts.timingRuns > 0) {
                    val samples = (1..opts.timingRuns).map {
                        runCase(case, ScriptedProvider(case.responses), file.maxAttempts, mode).loopMs
                    }
                    record = record.copy(overheadSamplesMs = samples.map { Math.round(it * 1000) / 1000.0 })
                }
                if (!scripted && opts.native) record = record.copy(provider = "${record.provider}+native")
                writer.write(recordJson.encodeToString(CaseRecord.serializer(), record))
                writer.newLine()
                val mark = if (record.ok) "ok " else "-- "
                println("$mark ${case.id.padEnd(40)} ${record.status.padEnd(18)} attempts=${record.attempts.size}")
            }
        }
    }
    println("wrote ${opts.out}")
}
