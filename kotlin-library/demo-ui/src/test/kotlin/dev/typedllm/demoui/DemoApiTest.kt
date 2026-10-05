package dev.typedllm.demoui

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DemoApiTest {
    /** Points live runs at a port nothing listens on, so tests never depend on Ollama. */
    private val unreachable = LiveConfig.fromEnvironment(mapOf("LIVE_BASE_URL" to "http://127.0.0.1:1/v1"))

    private fun ApplicationTestBuilder.start() = application { demoModule(unreachable) }

    private suspend fun ApplicationTestBuilder.runScenario(id: String): HttpResponse {
        start()
        return client.post("/api/scenarios/$id/run")
    }

    private suspend fun ApplicationTestBuilder.runLive(body: String): HttpResponse {
        start()
        return client.post("/api/live/run") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun HttpResponse.result(): RunResult = Json.decodeFromString<RunResult>(bodyAsText())

    @Test
    fun `self-correction shows each problem in plain words`() = testApplication {
        val result = runScenario("self-correction").result()

        assertEquals(listOf(false, false, true), result.attempts.map { it.accepted })
        assertEquals("Missing the date", result.attempts[1].problem)
        assertIs<Outcome.Success>(result.outcome)
    }

    @Test
    fun `never-partial returns a failure`() = testApplication {
        val result = runScenario("never-partial").result()

        assertTrue(result.attempts.none { it.accepted })
        assertIs<Outcome.Failure>(result.outcome)
    }

    @Test
    fun `unknown scenario is not found`() = testApplication {
        assertEquals(HttpStatusCode.NotFound, runScenario("no-such-scenario").status)
    }

    @Test
    fun `serves the page, the target type and the scenarios`() = testApplication {
        start()
        assertEquals(HttpStatusCode.OK, client.get("/").status)
        assertTrue(client.get("/api/target").bodyAsText().contains("attendees"))
        assertTrue(client.get("/api/scenarios").bodyAsText().contains("self-correction"))
    }

    @Test
    fun `live run with an empty message is rejected`() = testApplication {
        assertEquals(HttpStatusCode.BadRequest, runLive("""{"message":"  ","modelId":"enforced"}""").status)
    }

    @Test
    fun `live run reports an unreachable provider`() = testApplication {
        val response = runLive("""{"message":"lunch friday?","modelId":"enforced"}""")
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("Could not reach"))
    }
}
