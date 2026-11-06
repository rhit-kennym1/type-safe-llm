package dev.typedllm.demoui

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The service behind both tabs, with live runs pointed at a local stand-in for an OpenAI-compatible API. */
class DemoServiceTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JsonObject>()
    private val replies = ArrayDeque<String>()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requests += Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            val body = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject { put("message", buildJsonObject { put("content", replies.removeFirst()) }) })
                })
                put("usage", buildJsonObject { put("prompt_tokens", 120); put("completion_tokens", 40) })
            }.toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun service() = DemoService(
        LiveConfig.fromEnvironment(mapOf("LIVE_BASE_URL" to "http://127.0.0.1:${server.address.port}/v1")),
    )

    private fun messagesOf(request: JsonObject) = request.getValue("messages").jsonArray.map {
        it.jsonObject.getValue("role").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content
    }

    @Test
    fun `live run sends the prompt unchanged and decodes into the chosen class`() = runBlocking {
        val prompt = "Extract the order from this receipt.\n\nOrder ORD-7 total 12.5 paid cash"
        replies += """{"id":"ORD-7","total":12.5,"payment":{"type":"cash"}}"""

        val result = service().runLive(prompt, "enforced", "order")!!

        val (role, content) = messagesOf(requests.single()).last()
        assertEquals("user" to prompt, role to content)
        assertEquals("order", result.targetId)
        assertTrue(result.enforced)
        assertEquals("llama3.2:3b", result.model)
        assertIs<Outcome.Success>(result.outcome)
        val attempt = result.attempts.single()
        assertEquals(listOf("system", "user"), attempt.sent.map { it.role })
        assertTrue(attempt.sent[0].content.contains("\"payment\""), "the system message carries the generated schema")
        assertEquals(120, attempt.inputTokens)
        assertEquals(40, attempt.outputTokens)
    }

    @Test
    fun `retries show the previous answer and the correction as the new messages`() = runBlocking {
        replies += """{"id":"ORD-7","total":12.5,"payment":{"last4":"4242","holder":"A. Lee"}}"""
        replies += """{"id":"ORD-7","total":12.5,"payment":{"type":"card","last4":"4242","holder":"A. Lee"}}"""

        val result = service().runLive("receipt", "prompt-only", "order")!!

        assertFalse(result.enforced)
        assertEquals(listOf(false, true), result.attempts.map { it.accepted })
        assertEquals("Sealed-type choice", result.attempts[0].check)
        assertEquals("Didn't say which kind it is: the \"type\" field is missing", result.attempts[0].problem)
        assertEquals(listOf("assistant", "user"), result.attempts[1].sent.map { it.role })
        assertTrue(result.attempts[1].sent[1].content.contains("could not be decoded"))
        assertNull(result.attempts[1].check)
    }

    @Test
    fun `unknown class or model is not run`() = runBlocking {
        assertNull(service().runLive("hi", "enforced", "no-such-class"))
        assertNull(service().runLive("hi", "no-such-model", "event"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `every class has a starting prompt and a class name for the page`() {
        val targets = service().targets()
        assertEquals(listOf("event", "contract", "thread", "order"), targets.map { it.id })
        assertTrue(targets.all { it.livePrompt.isNotBlank() })
        assertEquals(listOf("Event", "Contract", "CommentNode", "Order"), targets.map { it.className })
    }

    @Test
    fun `scripted examples record the messages the library built`() = runBlocking {
        val result = service().runScenario("self-correction")!!
        val first = result.attempts.first()
        assertEquals(listOf("system", "user"), first.sent.map { it.role })
        assertTrue(first.sent[1].content.startsWith("Pull the structured data out of this message:"))
        assertNull(result.model)
    }

    @Test
    fun `with safeguards a gap in the prompt comes back as missing instead of invented`() = runBlocking {
        val prompt = "Extract the event.\n\nLunch with dana and luis, place TBD"
        replies += """{"title":{"quote":"Lunch","value":"Lunch"},"date":{"quote":"","value":null},"location":{"quote":"","value":null},"attendees":{"quote":"dana and luis","value":["dana","luis"]}}"""

        val result = service().runLive(prompt, "prompt-only", "event", SafeguardsView(allowMissing = true, requireEvidence = true))!!

        val outcome = assertIs<Outcome.Incomplete>(result.outcome)
        assertEquals(listOf("date"), outcome.missing)
        assertEquals("missing", result.fields.first { it.name == "date" }.status)
    }

    @Test
    fun `grounded live run sends the field envelope and checks quotes against the source`() = runBlocking {
        val prompt = "Extract the order.\n\nOrder ORD-7 total 12.5 paid cash"
        replies += """{"id":{"quote":"Order ORD-7","value":"ORD-7"},"total":{"quote":"total 99","value":99},"payment":{"quote":"paid cash","value":{"type":"cash"}}}"""

        val result = service().runLive(prompt, "prompt-only", "order", SafeguardsView(allowMissing = true, requireEvidence = true))!!

        assertTrue(messagesOf(requests.single()).first().second.contains("leave its quote empty"))
        val outcome = assertIs<Outcome.Unverified>(result.outcome)
        assertEquals(listOf("total"), outcome.flagged)
        assertEquals("quote not found in the source", result.fields.first { it.name == "total" }.note)
    }
}
