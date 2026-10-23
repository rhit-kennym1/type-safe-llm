package dev.typedllm.demoui

import dev.typedllm.OutputFormat

/** A scripted run: the target type, the message, and the answers a fake model will give, in order. */
data class Scenario(
    val id: String,
    val title: String,
    val description: String,
    val format: OutputFormat,
    val message: String,
    val modelAnswers: List<String>,
    /** Which TargetType this scenario asks the library to produce. */
    val targetId: String,
) {
    fun summary() = ScenarioSummary(id, title, description, format.displayName, message, targetId)
}

object Scenarios {

    // -------------------- EVENT (original scenarios) --------------------

    private const val EVENT_MESSAGE = "hey! lunch thursday 12:30 at Moe's? sam and priya are in"
    private const val EVENT_CHATTY = "Sounds like lunch with Sam and Priya on Thursday at Moe's!"

    private val EVENT_VALID = """
        {
          "title": "Lunch",
          "date": "Thursday 12:30",
          "location": "Moe's",
          "attendees": ["Sam", "Priya"]
        }
    """.trimIndent()

    private val EVENT_NO_DATE = """
        {
          "title": "Lunch",
          "location": "Moe's",
          "attendees": ["Sam", "Priya"]
        }
    """.trimIndent()

    private val EVENT_EXTRA_FIELD = """
        {
          "title": "Lunch",
          "date": "Thursday 12:30",
          "attendees": ["Sam", "Priya"],
          "mood": "excited"
        }
    """.trimIndent()

    private val EVENT_VALID_YAML = """
        title: Lunch
        date: Thursday 12:30
        location: Moe's
        attendees:
          - Sam
          - Priya
    """.trimIndent()

    private val EVENT_NO_DATE_YAML =
        EVENT_VALID_YAML.lines().filterNot { it.startsWith("date:") }.joinToString("\n")

    // -------------------- THREAD (recursive) --------------------

    private const val THREAD_MESSAGE =
        "forum thread: dana posted 'thoughts on the redesign?', " +
        "luis replied 'love the colors', dana said 'me too but the sidebar is cramped', " +
        "sam jumped in on luis's reply saying 'agreed, the greens pop'"

    // Correctly nested: root comment with two levels of replies.
    private val THREAD_VALID = """
        {
          "author": "dana",
          "body": "thoughts on the redesign?",
          "replies": [
            {
              "author": "luis",
              "body": "love the colors",
              "replies": [
                {"author": "sam", "body": "agreed, the greens pop", "replies": []}
              ]
            },
            {
              "author": "dana",
              "body": "me too but the sidebar is cramped",
              "replies": []
            }
          ]
        }
    """.trimIndent()

    // Flat: the model missed that sam was replying to luis — a plausible first-try mistake
    // but still structurally valid, so the retry loop can't fix it. Shown so viewers see what
    // "schema valid, semantically wrong" looks like.
    private val THREAD_FLAT = """
        {
          "author": "dana",
          "body": "thoughts on the redesign?",
          "replies": [
            {"author": "luis", "body": "love the colors", "replies": []},
            {"author": "dana", "body": "me too but the sidebar is cramped", "replies": []},
            {"author": "sam", "body": "agreed, the greens pop", "replies": []}
          ]
        }
    """.trimIndent()

    // Replies is a string instead of a list — the kind of mistake prompt-only output makes.
    private val THREAD_WRONG_SHAPE = """
        {
          "author": "dana",
          "body": "thoughts on the redesign?",
          "replies": "luis said love the colors"
        }
    """.trimIndent()

    // -------------------- PAYMENT (sealed polymorphism) --------------------

    private const val PAYMENT_MESSAGE =
        "order ORD-2041 for \$84.20, customer paid with their Visa ending 4417, name on card Priya Shah"

    private val PAYMENT_VALID_CARD = """
        {
          "id": "ORD-2041",
          "total": 84.20,
          "payment": {
            "type": "card",
            "last4": "4417",
            "holder": "Priya Shah"
          }
        }
    """.trimIndent()

    // Missing the discriminator — the schema's oneOf has no way to pick a branch.
    private val PAYMENT_MISSING_TYPE = """
        {
          "id": "ORD-2041",
          "total": 84.20,
          "payment": {
            "last4": "4417",
            "holder": "Priya Shah"
          }
        }
    """.trimIndent()

    // Discriminator says 'card' but body has bank fields — the schema catches the mismatch.
    private val PAYMENT_MISMATCHED_BRANCH = """
        {
          "id": "ORD-2041",
          "total": 84.20,
          "payment": {
            "type": "card",
            "routing": "021000021",
            "account": "99887766"
          }
        }
    """.trimIndent()

    private val PAYMENT_CASH_MESSAGE =
        "order ORD-3119 for \$12.00, paid in cash at the counter"

    private val PAYMENT_VALID_CASH = """
        {
          "id": "ORD-3119",
          "total": 12.00,
          "payment": {"type": "cash"}
        }
    """.trimIndent()

    // -------------------- SCENARIOS --------------------

    val all = listOf(
        // Original event scenarios
        Scenario(
            id = "first-try",
            title = "Right the first time",
            description = "The first answer has every field, so it's used straight away.",
            format = OutputFormat.JSON,
            message = EVENT_MESSAGE,
            modelAnswers = listOf(EVENT_VALID),
            targetId = "event",
        ),
        Scenario(
            id = "self-correction",
            title = "Fixes its own mistakes",
            description = "A chatty reply, then a missing date. Each problem goes back to the model.",
            format = OutputFormat.JSON,
            message = EVENT_MESSAGE,
            modelAnswers = listOf(EVENT_CHATTY, EVENT_NO_DATE, EVENT_VALID),
            targetId = "event",
        ),
        Scenario(
            id = "yaml",
            title = "Same fields, as YAML",
            description = "The same event, written in YAML instead of JSON.",
            format = OutputFormat.YAML,
            message = EVENT_MESSAGE,
            modelAnswers = listOf(EVENT_NO_DATE_YAML, EVENT_VALID_YAML),
            targetId = "event",
        ),
        Scenario(
            id = "never-partial",
            title = "Never half an answer",
            description = "Every answer is wrong, so the program gets an error, not a half-filled event.",
            format = OutputFormat.JSON,
            message = EVENT_MESSAGE,
            modelAnswers = listOf(EVENT_CHATTY, EVENT_NO_DATE, EVENT_EXTRA_FIELD),
            targetId = "event",
        ),

        // Recursive scenarios
        Scenario(
            id = "thread-nested",
            title = "A nested reply thread",
            description = "The target type refers to itself: comments can contain replies that are themselves comments.",
            format = OutputFormat.JSON,
            message = THREAD_MESSAGE,
            modelAnswers = listOf(THREAD_VALID),
            targetId = "thread",
        ),
        Scenario(
            id = "thread-wrong-shape",
            title = "Fixes a wrong-shape reply",
            description = "First the model returns a string where a list of comments was expected, then corrects to the right nested shape.",
            format = OutputFormat.JSON,
            message = THREAD_MESSAGE,
            modelAnswers = listOf(THREAD_WRONG_SHAPE, THREAD_VALID),
            targetId = "thread",
        ),

        // Polymorphism scenarios
        Scenario(
            id = "pay-card",
            title = "Picks the card branch",
            description = "The schema is a oneOf — the model has to choose a payment type and fill in that type's fields.",
            format = OutputFormat.JSON,
            message = PAYMENT_MESSAGE,
            modelAnswers = listOf(PAYMENT_VALID_CARD),
            targetId = "order",
        ),
        Scenario(
            id = "pay-missing-type",
            title = "Fills in a missing payment type",
            description = "First the model omits the discriminator so the schema can't pick a branch. The error goes back and the retry adds it.",
            format = OutputFormat.JSON,
            message = PAYMENT_MESSAGE,
            modelAnswers = listOf(PAYMENT_MISSING_TYPE, PAYMENT_VALID_CARD),
            targetId = "order",
        ),
        Scenario(
            id = "pay-cash",
            title = "Picks the cash branch",
            description = "A branch with no extra fields — the discriminator alone is enough.",
            format = OutputFormat.JSON,
            message = PAYMENT_CASH_MESSAGE,
            modelAnswers = listOf(PAYMENT_VALID_CASH),
            targetId = "order",
        ),
    )
}