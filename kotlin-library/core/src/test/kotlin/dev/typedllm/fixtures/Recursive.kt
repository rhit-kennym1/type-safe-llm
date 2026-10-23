package dev.typedllm.fixtures

import dev.typedllm.Description
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Recursive type: a comment that can have child replies ---

@Serializable
@Description("A single comment, optionally with threaded replies.")
data class Comment(
    @Description("The commenter's display name")
    val author: String,
    val body: String,
    val replies: List<Comment> = emptyList(),
)

// --- Mutually recursive types: a folder tree ---

@Serializable
data class Folder(val name: String, val contents: List<Node> = emptyList())

@Serializable
data class Node(val folder: Folder? = null, val fileName: String? = null)

// --- Sealed polymorphism: payment methods ---

@Serializable
sealed interface Payment {
    @Serializable
    @SerialName("card")
    data class Card(
        @Description("Last four digits of the card number")
        val last4: String,
        val holder: String,
    ) : Payment

    @Serializable
    @SerialName("bank")
    data class Bank(val routing: String, val account: String) : Payment

    @Serializable
    @SerialName("cash")
    data object Cash : Payment
}

@Serializable
data class Order(val id: String, val total: Double, val payment: Payment)

// --- A described envelope type to test class-level @Description ---

@Serializable
@Description("A single named task on a to-do list.")
data class Task(
    @Description("Short imperative sentence, e.g. 'Mail the contract'")
    val summary: String,
    val done: Boolean = false,
)