package dev.typedllm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Meeting(val title: String, val date: String, val time: String, val attendees: Int, val room: String? = null)

class SafeguardsTest {
    private val source = "Design review on Nov 4 at 2pm. Eight of us, so 8 seats please."
    private val plainValid = """{"title":"Design review","date":"2026-11-04","time":"14:00","attendees":8}"""

    private fun envelope(vararg fields: Pair<String, String>) = fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    private fun found(quote: String, value: String) = """{"quote":${JsonPrimitive(quote)},"value":$value}"""
    private val missing = """{"quote":"","value":null}"""

    @Test
    fun `envelope asks for the quote first, then a nullable value, with no separate status`() {
        val schema = SchemaGenerator.generate(serializer<Meeting>().descriptor)
        val fields = Grounding.fields(schema)!!
        val env = Grounding.envelope(schema, fields, Safeguards(allowMissing = true, requireEvidence = true))
        val date = env["properties"]!!.jsonObject["date"]!!.jsonObject
        assertEquals(listOf("quote", "value"), date["properties"]!!.jsonObject.keys.toList())
        val withoutQuotes = Grounding.envelope(schema, fields, Safeguards(allowMissing = true))["properties"]!!.jsonObject["date"]!!.jsonObject
        assertEquals(listOf("status", "value"), withoutQuotes["properties"]!!.jsonObject.keys.toList())
        val anyOf = date["properties"]!!.jsonObject["value"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals("null", anyOf[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("title", "date", "time", "attendees", "room"), env["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `no safeguards behaves like call and returns a complete value`() = runTest {
        val r = TypedLLM(FakeProvider(plainValid)).extract<Meeting>("extract", source)
        assertIs<Extraction.Complete<Meeting>>(r)
        assertEquals(8, r.value.attendees)
    }

    @Test
    fun `without safeguards the generic retry gets the model to fill a missing field`() = runTest {
        // First answer leaves the date out (it's not in this source); the correction says it's required; the model invents one.
        val src = "Design review at 2pm, 8 people."
        val provider = FakeProvider(
            """{"title":"Design review","time":"14:00","attendees":8}""",
            """{"title":"Design review","date":"2026-11-04","time":"14:00","attendees":8}""",
        )
        val r = TypedLLM(provider).extract<Meeting>("extract", src)
        assertIs<Extraction.Complete<Meeting>>(r)
        assertEquals("2026-11-04", r.value.date)  // accepted, though the source has no date
        assertTrue(provider.received[1].messages.last().content.contains("date"))
    }

    @Test
    fun `allow missing turns an absent required field into an Incomplete result`() = runTest {
        val src = "Design review at 2pm, 8 people."
        val answer = envelope(
            "title" to found("Design review", "\"Design review\""), "date" to missing, "time" to found("2pm", "\"14:00\""),
            "attendees" to found("8 people", "8"), "room" to missing,
        )
        val r = TypedLLM(FakeProvider(answer)).extract<Meeting>("extract", src, Safeguards(allowMissing = true, requireEvidence = true))
        assertIs<Extraction.Incomplete>(r)
        assertEquals(listOf("date"), r.missing)
        assertFalse("room" in r.missing, "room has a default, so its absence is fine")
        assertEquals("Design review", r.partial["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a field quoted from the source but left empty is noted and named in the follow-up`() = runTest {
        val src = "Design review at 2pm, 8 people."
        val contradicting = envelope(
            "title" to """{"quote":"Design review","value":null}""", "date" to missing, "time" to found("2pm", "\"14:00\""),
            "attendees" to found("8 people", "8"), "room" to missing,
        )
        val provider = FakeProvider(contradicting, contradicting)
        val r = TypedLLM(provider).extract<Meeting>("extract", src, Safeguards(allowMissing = true, requireEvidence = true, targetedRetry = true))
        assertIs<Extraction.Incomplete>(r)
        assertEquals(Grounding.QUOTED_NO_VALUE, r.fields.first { it.name == "title" }.note)
        assertNull(r.fields.first { it.name == "date" }.note, "an empty quote is a plain missing")
        assertTrue(provider.received[1].messages.last().content.contains("title (\"Design review\")"))
    }

    @Test
    fun `a quote that isn't in the source flags the value as unverified`() = runTest {
        val answer = envelope(
            "title" to found("Design review", "\"Design review\""), "date" to found("on November 12", "\"2026-11-12\""),
            "time" to found("at 2pm", "\"14:00\""), "attendees" to found("8 seats", "8"), "room" to missing,
        )
        val r = TypedLLM(FakeProvider(answer)).extract<Meeting>("extract", source, Safeguards(allowMissing = true, requireEvidence = true))
        assertIs<Extraction.Ungrounded<Meeting>>(r)
        assertEquals(listOf("date"), r.flagged)
        assertEquals("quote not found in the source", r.fields.first { it.name == "date" }.note)
    }

    @Test
    fun `a real quote with a different value is also flagged`() = runTest {
        val answer = envelope(
            "title" to found("Design review", "\"Design review\""), "date" to found("Nov 4", "\"2026-11-04\""),
            "time" to found("at 2pm", "\"14:00\""), "attendees" to found("Eight of us", "12"), "room" to missing,
        )
        val r = TypedLLM(FakeProvider(answer)).extract<Meeting>("extract", source, Safeguards(allowMissing = true, requireEvidence = true))
        assertIs<Extraction.Ungrounded<Meeting>>(r)
        assertEquals(listOf("attendees"), r.flagged)
        assertEquals("value doesn't appear in its quote", r.fields.first { it.name == "attendees" }.note)
    }

    @Test
    fun `dates, times and numbers written differently in the source still verify`() {
        assertTrue(Evidence.stringInQuote("2026-11-04", "on Nov 4 at 2pm"))
        assertTrue(Evidence.stringInQuote("2026-11-04", "4th November"))
        assertTrue(Evidence.stringInQuote("2026-11-04", "due 11/4"))
        assertFalse(Evidence.stringInQuote("2026-11-04", "Nov 14"))
        assertTrue(Evidence.stringInQuote("october 30", "friday oct 30 at 2pm"))
        assertTrue(Evidence.stringInQuote("Oct 30th, 2026", "friday oct 30"))
        assertTrue(Evidence.stringInQuote("30 October", "due 10/30"))
        assertTrue(Evidence.stringInQuote("10/30", "friday oct 30"))
        assertFalse(Evidence.stringInQuote("october 31", "friday oct 30"))
        assertFalse(Evidence.stringInQuote("2026-10-30", "oct 30, 2025"))
        assertTrue(Evidence.stringInQuote("room 12", "in room 12"), "words that aren't months are still plain text")
        assertTrue(Evidence.stringInQuote("14:00", "at 2pm"))
        assertTrue(Evidence.stringInQuote("14:30", "2:30 p.m."))
        assertTrue(Evidence.stringInQuote("12:00", "lunch at noon"))
        assertFalse(Evidence.stringInQuote("14:00", "at 2am"))
        assertTrue(Evidence.numberInQuote(1250.0, "total due: $1,250.00"))
        assertFalse(Evidence.numberInQuote(125.0, "total due: $1,250.00"))
        assertEquals(1.0, Evidence.locate("“Design  review”", source)?.score)
    }

    @Test
    fun `a slightly misquoted quote is accepted with a note, checked against the source's own words`() = runTest {
        val answer = envelope(
            "title" to found("Design review", "\"Design review\""), "date" to found("Design review on Nov. 4 at 2 pm", "\"2026-11-04\""),
            "time" to found("at 2pm", "\"14:00\""), "attendees" to found("8 seats", "8"), "room" to missing,
        )
        val r = TypedLLM(FakeProvider(answer)).extract<Meeting>("extract", source, Safeguards(allowMissing = true, requireEvidence = true))
        assertIs<Extraction.Complete<Meeting>>(r)
    }

    @Test
    fun `a near quote can't carry a value the source doesn't have`() {
        val src = "The kickoff is on Nov 14 at noon in room B."
        // 97% like the source, but the source says the 14th, so the 4th isn't supported.
        assertTrue(Evidence.locate("The kickoff is on Nov 4 at noon in room B", src)!!.score >= 0.9)
        val field = PlanField("date", JsonObject(emptyMap()), required = true, nullable = false, ValueKind.STRING, null, null)
        assertEquals("value doesn't appear in its quote", Evidence.check(JsonPrimitive("2026-11-04"), field, "The kickoff is on Nov 4 at noon", src).problem)
        val ok = Evidence.check(JsonPrimitive("2026-11-14"), field, "The kickoff is on Nov. 14 at noon", src)
        assertNull(ok.problem)
        assertEquals("quote matched the source approximately (96%)", ok.note)
        assertNull(Evidence.locate("a completely different sentence", src))
    }

    @Test
    fun `a value with the same typo as its quote is corrected to the source's spelling`() {
        val src = "moving the capstone sync to friday oct 30 at 2pm in moench hall F-217."
        val field = PlanField("location", JsonObject(emptyMap()), required = false, nullable = true, ValueKind.STRING, null, null)
        val fixed = Evidence.check(JsonPrimitive("Moech hall F-217"), field, "moech hall F-217", src)
        assertNull(fixed.problem)
        assertEquals(JsonPrimitive("Moench hall F-217"), fixed.value)
        assertEquals("corrected to the source's spelling (model wrote \"Moech hall F-217\")", fixed.note)
        // Digits are never corrected: a wrong room number is still flagged.
        val wrongRoom = Evidence.check(JsonPrimitive("moench hall F-218"), field, "moench hall F-218", src)
        assertEquals("value doesn't appear in its quote", wrongRoom.problem)
    }

    @Test
    fun `number words, currency names and units verify`() {
        assertTrue(Evidence.numberInQuote(8.0, "Eight of us"))
        assertTrue(Evidence.numberInQuote(25.0, "twenty-five people"))
        assertTrue(Evidence.numberInQuote(12.0, "a dozen chairs"))
        assertFalse(Evidence.numberInQuote(9.0, "Eight of us"))
        assertTrue(Evidence.stringInQuote("USD", "paid in US dollars"))
        assertTrue(Evidence.stringInQuote("EUR", "€40 deposit"))
        assertFalse(Evidence.stringInQuote("GBP", "paid in US dollars"))
        assertTrue(Evidence.numberInQuote(2.4, "weighs 2,400 g"))
        assertTrue(Evidence.numberInQuote(2.4, "about 5.3 lb"))
        assertTrue(Evidence.numberInQuote(16.09, "a 10 mile run"))
        assertFalse(Evidence.numberInQuote(3.0, "weighs 2,400 g"))
    }

    @Test
    fun `targeted retry replaces the generic correction and can turn a guess into missing`() = runTest {
        val src = "Design review at 2pm, 8 people."
        val guessed = envelope(
            "title" to found("Design review", "\"Design review\""), "date" to found("next Wednesday", "\"2026-11-04\""),
            "time" to found("at 2pm", "\"14:00\""), "attendees" to found("8 people", "8"), "room" to missing,
        )
        val honest = guessed.replace(found("next Wednesday", "\"2026-11-04\""), missing)
        val provider = FakeProvider(guessed, honest)
        val r = TypedLLM(provider).extract<Meeting>("extract", src, Safeguards(allowMissing = true, requireEvidence = true, targetedRetry = true))
        assertIs<Extraction.Incomplete>(r)
        assertEquals(listOf("date"), r.missing)
        val followUp = provider.received[1].messages.last().content
        assertTrue(followUp.contains("not supported by the source") && followUp.contains("date"))
        assertEquals(2, r.attempts.size)
    }

    @Test
    fun `cross-check disputes a value the independent pass can't find`() = runTest {
        val src = "Design review at 2pm, 8 people."
        val provider = FakeProvider(
            """{"title":"Design review","date":"2026-11-04","time":"14:00","attendees":8}""",
            """{"title":"Design review","date":null,"time":"at 2pm","attendees":"8 people","room":null}""",
        )
        val r = TypedLLM(provider).extract<Meeting>("extract", src, Safeguards(crossCheck = true))
        assertIs<Extraction.Ungrounded<Meeting>>(r)
        assertEquals(listOf("date"), r.flagged)
        assertEquals(FieldStatus.DISPUTED, r.fields.first { it.name == "date" }.status)
        assertTrue(r.crossCheck!!.rawOutput.contains("8 people"))
        assertTrue(provider.received[1].messages[0].content.startsWith("You check whether facts are stated"))
    }

    @Test
    fun `structural problems in a grounded answer are retried like decode errors`() = runTest {
        val good = envelope(
            "title" to found("Design review", "\"Design review\""), "date" to found("Nov 4", "\"2026-11-04\""),
            "time" to found("at 2pm", "\"14:00\""), "attendees" to found("8 seats", "8"), "room" to missing,
        )
        val provider = FakeProvider("""{"title":"Design review"}""", good)
        val r = TypedLLM(provider).extract<Meeting>("extract", source, Safeguards(allowMissing = true, requireEvidence = true))
        assertIs<Extraction.Complete<Meeting>>(r)
        assertTrue(r.attempts[0].error!!.contains("must be an object"))
        assertNull(r.crossCheck)
    }

    @Test
    fun `failed when no attempt produces the structure`() = runTest {
        val r = TypedLLM(FakeProvider("nope", "still no", "no"), maxAttempts = 3).extract<Meeting>("extract", source, Safeguards.ALL)
        assertIs<Extraction.Failed>(r)
        assertEquals(3, r.attempts.size)
    }

    @Test
    fun `array of strings is checked element by element`() {
        val field = PlanField("attendees", JsonObject(emptyMap()), true, false, ValueKind.ARRAY, ValueKind.STRING, null)
        assertTrue(Evidence.valueInQuote(JsonArray(listOf(JsonPrimitive("dana"), JsonPrimitive("luis"))), field, "dana and luis are in"))
        assertFalse(Evidence.valueInQuote(JsonArray(listOf(JsonPrimitive("dana"), JsonPrimitive("sam"))), field, "dana and luis are in"))
    }
}
