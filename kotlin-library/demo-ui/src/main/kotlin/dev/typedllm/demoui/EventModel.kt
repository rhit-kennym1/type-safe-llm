package dev.typedllm.demoui

import dev.typedllm.Description
import kotlinx.serialization.Serializable

/** The type the demo asks the model for: an event pulled out of a casual message. */
@Serializable
data class Event(
    @Description("Short name for the event, e.g. \"lunch\" or \"design review\"")
    val title: String,
    @Description("The day it happens")
    val date: String,
    val location: String? = null,
    @Description("Names of the people going")
    val attendees: List<String>,
)

/** Source shown under "Show code". Keep in sync with [Event] above. */
internal val EVENT_SOURCE = """
    @Serializable
    data class Event(
        @Description("Short name for the event, e.g. \"lunch\" or \"design review\"")
        val title: String,
        @Description("The day it happens")
        val date: String,
        val location: String? = null,   // optional
        @Description("Names of the people going")
        val attendees: List<String>,
    )
""".trimIndent()
