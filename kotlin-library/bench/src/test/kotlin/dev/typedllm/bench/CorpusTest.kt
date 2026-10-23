package dev.typedllm.bench

import dev.typedllm.SchemaGenerator
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the shared adversarial corpus through the library with scripted responses and pins the outcome of
 * every case, so a change that weakens a guarantee (or silently changes behavior) fails the build.
 * Gradle runs tests from kotlin-library/bench, so the corpus is two levels up.
 */
class CorpusTest {
    private val file = loadCases(listOf(File("../../experiments/cases/cases.json"), File("experiments/cases/cases.json")).first { it.exists() })

    private suspend fun outcomes(): Map<String, CaseRecord> =
        file.cases.associate { it.id to runCase(it, ScriptedProvider(it.responses), file.maxAttempts, "scripted") }

    @Test
    fun `every target has a schema`() {
        for ((name, serializer) in BENCH_TARGETS) {
            val schema = SchemaGenerator.generate(serializer.descriptor)
            assertTrue(schema.isNotEmpty(), "empty schema for $name")
        }
    }

    @Test
    fun `cases the library should accept are accepted`() = runTest {
        val results = outcomes()
        val expectedAccept = file.cases.filter { it.ideal == "accept" }.map { it.id }
        val wronglyRejected = expectedAccept.filter { !results.getValue(it).ok }
        assertEquals(emptyList(), wronglyRejected, "cases with ideal=accept that the library rejected")
    }

    @Test
    fun `detectable bad output is never returned as valid`() = runTest {
        val results = outcomes()
        // Every case whose responses a schema check *can* catch must end in an error, never in data.
        val leaked = file.cases
            .filter { it.ideal == "reject" && it.detectable }
            .filter { results.getValue(it.id).ok }
            .map { it.id }
        // Known, documented gaps (see experiments/results/REPORT.md); anything else is a regression.
        // kotlinx-serialization's default Json decodes quoted primitives ("40" -> 40, "true" -> true) even with
        // isLenient = false, so the library's "wrong types fail" claim doesn't hold for these two. Fix proposed
        // in the report (type-check the parsed JSON tree against the descriptor before decoding).
        // It also accepts an object with a duplicated key and silently keeps one value.
        val knownGaps = setOf("event-count-as-string", "contract-bool-as-string", "event-duplicate-key")
        assertEquals(emptyList(), leaked - knownGaps, "detectable bad output returned as valid data")
    }

    @Test
    fun `wrong but well-formed output passes, because no schema can see it`() = runTest {
        val results = outcomes()
        val undetectable = file.cases.filter { !it.detectable }
        assertTrue(undetectable.isNotEmpty())
        // This pins the limitation rather than hiding it: validation checks structure, not truth.
        undetectable.forEach { assertTrue(results.getValue(it.id).ok, "${it.id} should pass structural validation") }
    }

    @Test
    fun `a model that never fixes its answer gets exactly maxAttempts tries then an error`() = runTest {
        val stall = runCase(file.cases.single { it.id == "event-stall" }.let { it }, ScriptedProvider(
            file.cases.single { it.id == "event-stall" }.responses,
        ), file.maxAttempts, "scripted")
        assertEquals("retries_exhausted", stall.status)
        assertEquals(file.maxAttempts, stall.attempts.size)
        assertEquals(null, stall.data)
    }

    @Test
    fun `retry prompts grow because the library resends the whole conversation`() = runTest {
        val case = file.cases.single { it.id == "event-two-mistakes" }
        val record = runCase(case, ScriptedProvider(case.responses), file.maxAttempts, "scripted")
        val sizes = record.attempts.map { it.promptChars }
        assertEquals(3, sizes.size)
        assertTrue(sizes[0] < sizes[1] && sizes[1] < sizes[2], "expected growing prompts, got $sizes")
    }
}
