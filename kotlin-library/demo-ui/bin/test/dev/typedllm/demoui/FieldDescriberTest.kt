package dev.typedllm.demoui

import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldDescriberTest {
    @Test
    fun `describes the event fields in plain words`() {
        assertEquals(
            listOf(
                FieldView("title", "text", required = true),
                FieldView("date", "text", required = true),
                FieldView("location", "text", required = false),
                FieldView("attendees", "list of text", required = true),
            ),
            FieldDescriber.describe(serializer<Event>().descriptor),
        )
    }
}
