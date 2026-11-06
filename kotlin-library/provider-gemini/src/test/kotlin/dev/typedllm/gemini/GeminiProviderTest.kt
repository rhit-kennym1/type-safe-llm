package dev.typedllm.gemini

import com.sun.net.httpserver.HttpServer
import dev.typedllm.CompletionRequest
import dev.typedllm.Message
import dev.typedllm.ProviderException
import dev.typedllm.Role
import dev.typedllm.TokenUsage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the provider against a local stand-in for the Gemini API, so no key or network is needed. */
class GeminiProviderTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var responseBody = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"ok\":true}"}]},"finishReason":"STOP"}]}"""
    private var lastRequest: JsonObject? = null
    private var lastPath: String? = null
    private var lastKeyHeader: String? = null

    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("ok") { put("type", "boolean") } }
        put("additionalProperties", false)
    }
    private val conversation = listOf(
        Message(Role.SYSTEM, "be strict"),
        Message(Role.USER, "extract"),
        Message(Role.ASSISTANT, "oops"),
        Message(Role.USER, "fix it"),
    )

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1beta/models/") { exchange ->
            lastPath = exchange.requestURI.path
            lastKeyHeader = exchange.requestHeaders.getFirst("x-goog-api-key")
            lastRequest = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun provider(native: Boolean) = GeminiProvider(
        "test-model", "secret-key", native, baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
    )

    @Test
    fun `native mode sends responseJsonSchema and maps roles`() = runTest {
        val text = provider(native = true).complete(CompletionRequest(conversation, schema))
        assertEquals("""{"ok":true}""", text)
        assertEquals("/v1beta/models/test-model:generateContent", lastPath)
        assertEquals("secret-key", lastKeyHeader)

        val request = lastRequest!!
        val config = request["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue("responseJsonSchema" in config)
        assertEquals("be strict", request["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val roles = request["contents"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user", "model", "user"), roles)
    }

    @Test
    fun `prompt-only mode sends no generation config`() = runTest {
        provider(native = false).complete(CompletionRequest(conversation, schema))
        assertFalse("generationConfig" in lastRequest!!)
    }

    @Test
    fun `usage counts thinking tokens as output`() = runTest {
        responseBody = """{"candidates":[{"content":{"parts":[{"text":"{}"}]}}],
            "usageMetadata":{"promptTokenCount":200,"candidatesTokenCount":10,"thoughtsTokenCount":40,"totalTokenCount":250}}"""
        val completion = provider(native = true).completeWithUsage(CompletionRequest(conversation, schema))
        assertEquals(TokenUsage(inputTokens = 200, outputTokens = 50), completion.usage)
    }

    @Test
    fun `thought parts are not part of the answer`() = runTest {
        responseBody = """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"{\"ok\":false}"}]}}]}"""
        assertEquals("""{"ok":false}""", provider(native = true).complete(CompletionRequest(conversation, schema)))
    }

    @Test
    fun `missing usage is null`() = runTest {
        assertNull(provider(native = true).completeWithUsage(CompletionRequest(conversation, schema)).usage)
    }

    @Test
    fun `blocked prompts become ProviderException`() = runTest {
        responseBody = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        val e = assertFailsWith<ProviderException> { provider(native = true).complete(CompletionRequest(conversation, schema)) }
        assertTrue(e.message!!.contains("SAFETY"))
    }

    @Test
    fun `HTTP errors become ProviderException without leaking the key`() = runTest {
        status = 403
        responseBody = """{"error":{"message":"permission denied"}}"""
        val e = assertFailsWith<ProviderException> { provider(native = true).complete(CompletionRequest(conversation, schema)) }
        assertTrue(e.message!!.contains("403"))
        assertFalse(e.message!!.contains("secret-key"))
    }
}
