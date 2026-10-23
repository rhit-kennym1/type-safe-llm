package dev.typedllm.demoui

import dev.typedllm.OutputFormat
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Uses the library's real error messages, so these break if a dependency upgrade changes the wording. */
class ErrorExplainerTest {

    private fun errorFrom(format: OutputFormat, text: String): String =
        try {
            format.decode(serializer<Event>(), text)
            error("Expected decoding to fail")
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }

    private fun explain(format: OutputFormat, text: String) = ErrorExplainer.explain(errorFrom(format, text))

    @Test
    fun `missing field`() {
        assertEquals("Missing the date", explain(OutputFormat.JSON, """{"title":"Lunch","attendees":[]}"""))
    }

    @Test
    fun `several missing fields`() {
        assertEquals("Missing title and date", explain(OutputFormat.JSON, """{"attendees":[]}"""))
    }

    @Test
    fun `extra field`() {
        val text = """{"title":"Lunch","date":"Fri","attendees":[],"mood":"happy"}"""
        assertEquals("Has a field we didn't ask for: mood", explain(OutputFormat.JSON, text))
    }

    @Test
    fun `plain text`() {
        assertEquals("Plain text instead of structured data", explain(OutputFormat.JSON, "Sure! Lunch on Friday."))
    }

    @Test
    fun `missing field in YAML`() {
        assertEquals("Missing the date", explain(OutputFormat.YAML, "title: Lunch\nattendees: []"))
    }

    @Test
    fun `unrecognised errors fall back to a general description`() {
        assertEquals("Doesn't match the fields we asked for", ErrorExplainer.explain("something unexpected"))
    }
}
