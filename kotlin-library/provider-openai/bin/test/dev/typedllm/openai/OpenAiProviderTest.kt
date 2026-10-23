package dev.typedllm.openai

import com.sun.net.httpserver.HttpServer
import dev.typedllm.CompletionRequest
import dev.typedllm.Message
import dev.typedllm.ProviderException
import dev.typedllm.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the provider against a local stand-in for the OpenAI API, so no key or network is needed. */
class OpenAiProviderTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var responseBody = """{"choices":[{"message":{"content":"{\"ok\":true}"}}]}"""
    private var lastRequest: JsonObject? = null

    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("ok") { put("type", "boolean") } }
        put("additionalProperties", false)
    }
    private val request = CompletionRequest(listOf(Message(Role.USER, "hi")), schema)

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            lastRequest = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun provider(native: Boolean) =
        OpenAiProvider("test-model", "test-key", native, baseUrl = "http://127.0.0.1:${server.address.port}/v1")

    @Test
    fun `native mode sends a strict json_schema response format`() = runTest {
        assertEquals("""{"ok":true}""", provider(native = true).complete(request))
        assertTrue("response_format" in lastRequest!!)
    }

    @Test
    fun `prompt-only mode sends no response format`() = runTest {
        provider(native = false).complete(request)
        assertFalse("response_format" in lastRequest!!)
    }

    @Test
    fun `HTTP errors become ProviderException`() = runTest {
        status = 401
        responseBody = """{"error":{"message":"bad key"}}"""
        val e = assertFailsWith<ProviderException> { provider(native = true).complete(request) }
        assertTrue(e.message!!.contains("401"))
    }

    @Test
    fun `refusals become ProviderException`() = runTest {
        responseBody = """{"choices":[{"message":{"content":null,"refusal":"I can't help with that."}}]}"""
        assertFailsWith<ProviderException> { provider(native = true).complete(request) }
    }
}
