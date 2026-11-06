package dev.typedllm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
private data class Ticket(val title: String, val seats: Int, val paid: Boolean, val tags: List<String> = emptyList(), val venue: Venue)

@Serializable
private data class Venue(val name: String, val capacity: Int)

@Serializable
private sealed interface Payment

@Serializable
@SerialName("cash")
private data object Cash : Payment

@Serializable
@SerialName("card")
private data class Card(val last4: String) : Payment

class StrictJsonTest {
    private inline fun <reified T> problems(json: String): List<String> =
        assertFailsWith<IllegalArgumentException> { StrictJson.check(serializer<T>().descriptor, StrictJson.parse(json)) }
            .message!!.lines().drop(1)

    @Test
    fun `a valid answer passes`() {
        StrictJson.check(serializer<Ticket>().descriptor, StrictJson.parse("""{"title":"Gig","seats":2,"paid":true,"venue":{"name":"Hall","capacity":300}}"""))
    }

    @Test
    fun `quoted numbers and booleans are rejected, and every problem is listed`() {
        assertEquals(
            listOf(
                "$.seats: expected a whole number, got the text \"2\"",
                "$.paid: expected true or false, got the text \"true\"",
                "$.venue.capacity: expected a whole number, got 3.5",
                "$.venue.name: required field is missing",
            ).sorted(),
            problems<Ticket>("""{"title":"Gig","seats":"2","paid":"true","venue":{"capacity":3.5}}""").sorted(),
        )
    }

    @Test
    fun `duplicate keys are rejected instead of keeping one value`() {
        assertEquals(listOf("$.venue.name: duplicate key"), problems<Ticket>("""{"title":"Gig","seats":2,"paid":true,"venue":{"name":"A","name":"B","capacity":1}}"""))
    }

    @Test
    fun `unknown fields and sealed types are checked`() {
        assertEquals(listOf("$.mood: unknown field"), problems<Ticket>("""{"title":"Gig","seats":2,"paid":true,"mood":"good","venue":{"name":"A","capacity":1}}"""))
        assertEquals(listOf("$: missing \"type\", which must be one of card, cash"), problems<Payment>("""{"last4":"4242"}"""))
        assertEquals(listOf("$.last4: expected text, got 4242"), problems<Payment>("""{"type":"card","last4":4242}"""))
    }
}
