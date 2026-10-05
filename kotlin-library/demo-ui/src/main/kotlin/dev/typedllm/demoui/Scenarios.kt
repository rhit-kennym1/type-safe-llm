package dev.typedllm.demoui

import dev.typedllm.OutputFormat

/** A scripted run: the message, and the answers a fake model will give, in order. */
data class Scenario(
    val id: String,
    val title: String,
    val description: String,
    val format: OutputFormat,
    val message: String,
    val modelAnswers: List<String>,
) {
    fun summary() = ScenarioSummary(id, title, description, format.displayName, message)
}

object Scenarios {
    private const val MESSAGE = "hey! lunch thursday 12:30 at Moe's? sam and priya are in"

    private const val CHATTY = "Sounds like lunch with Sam and Priya on Thursday at Moe's!"

    private val VALID = """
        {
          "title": "Lunch",
          "date": "Thursday 12:30",
          "location": "Moe's",
          "attendees": ["Sam", "Priya"]
        }
    """.trimIndent()

    private val NO_DATE = """
        {
          "title": "Lunch",
          "location": "Moe's",
          "attendees": ["Sam", "Priya"]
        }
    """.trimIndent()

    private val EXTRA_FIELD = """
        {
          "title": "Lunch",
          "date": "Thursday 12:30",
          "attendees": ["Sam", "Priya"],
          "mood": "excited"
        }
    """.trimIndent()

    private val VALID_YAML = """
        title: Lunch
        date: Thursday 12:30
        location: Moe's
        attendees:
          - Sam
          - Priya
    """.trimIndent()

    private val NO_DATE_YAML = VALID_YAML.lines().filterNot { it.startsWith("date:") }.joinToString("\n")

    val all = listOf(
        Scenario(
            id = "first-try",
            title = "Right the first time",
            description = "The first answer has every field, so it's used straight away.",
            format = OutputFormat.JSON,
            message = MESSAGE,
            modelAnswers = listOf(VALID),
        ),
        Scenario(
            id = "self-correction",
            title = "Fixes its own mistakes",
            description = "A chatty reply, then a missing date. Each problem goes back to the model.",
            format = OutputFormat.JSON,
            message = MESSAGE,
            modelAnswers = listOf(CHATTY, NO_DATE, VALID),
        ),
        Scenario(
            id = "yaml",
            title = "Same fields, as YAML",
            description = "The same event, written in YAML instead of JSON.",
            format = OutputFormat.YAML,
            message = MESSAGE,
            modelAnswers = listOf(NO_DATE_YAML, VALID_YAML),
        ),
        Scenario(
            id = "never-partial",
            title = "Never half an answer",
            description = "Every answer is wrong, so the program gets an error, not a half-filled event.",
            format = OutputFormat.JSON,
            message = MESSAGE,
            modelAnswers = listOf(CHATTY, NO_DATE, EXTRA_FIELD),
        ),
    )
}
