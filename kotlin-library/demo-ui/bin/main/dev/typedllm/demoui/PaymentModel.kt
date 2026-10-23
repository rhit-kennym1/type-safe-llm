package dev.typedllm.demoui

import dev.typedllm.Description
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Showcases SEALED-CLASS POLYMORPHISM. The library emits this as `oneOf`, with each branch
// pinning the `type` field to its @SerialName. The model picks the branch; the library
// decodes it into exactly the right concrete subtype.

@Serializable
@Description("A payment method a customer attached to an order.")
sealed interface PaymentMethod {
    @Serializable
    @SerialName("card")
    data class Card(
        @Description("Last four digits of the card number, no spaces")
        val last4: String,
        @Description("Cardholder's name as printed on the card")
        val holder: String,
    ) : PaymentMethod

    @Serializable
    @SerialName("bank")
    data class Bank(
        @Description("Nine-digit US ABA routing number")
        val routing: String,
        @Description("Account number — digits only")
        val account: String,
    ) : PaymentMethod

    @Serializable
    @SerialName("cash")
    data object Cash : PaymentMethod
}

@Serializable
@Description("A single checkout — one payment method per order.")
data class Order(
    @Description("Order identifier shown on receipts, e.g. 'ORD-2041'")
    val id: String,
    val total: Double,
    val payment: PaymentMethod,
)

internal val ORDER_SOURCE = """
    @Serializable
    sealed interface PaymentMethod {
        @Serializable @SerialName("card") data class Card(val last4: String, val holder: String) : PaymentMethod
        @Serializable @SerialName("bank") data class Bank(val routing: String, val account: String) : PaymentMethod
        @Serializable @SerialName("cash") data object Cash : PaymentMethod
    }

    @Serializable
    data class Order(val id: String, val total: Double, val payment: PaymentMethod)
""".trimIndent()