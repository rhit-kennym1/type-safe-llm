package dev.typedllm.demoui

import kotlinx.serialization.Serializable

/** The type the demo asks the model for: an event pulled out of a casual message. */
@Serializable
data class Event(
    val title: String,
    val date: String,
    val location: String? = null,
    val attendees: List<String>,
)

/** Source shown under "Show code". Keep in sync with [Event] above. */
internal val EVENT_SOURCE = """
    @Serializable
    data class Event(
        val title: String,
        val date: String,
        val location: String? = null,   // optional
        val attendees: List<String>,
    )
""".trimIndent()
