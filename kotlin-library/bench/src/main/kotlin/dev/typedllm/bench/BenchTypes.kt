package dev.typedllm.bench

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

// Benchmark target types, mirrored exactly by mcp-server/src/type_safe_llm/bench/schemas.py.
// Field names, optionality, defaults and domain rules must match, or the experiment measures schema
// differences instead of approach differences. The report diffs both sides' generated schemas to check.
//
// Domain rules live in init blocks: kotlinx.serialization runs them during decoding, and the library treats
// the IllegalArgumentException from require() like any other decode error (it's reported and retried).
// The JSON Schema can't describe these rules, so unlike Pydantic the model is never told about them up front.

private fun rule(ok: Boolean, message: String) = require(ok) { "constraint violated: $message" }

private fun isDate(value: String) = runCatching { LocalDate.parse(value) }.isSuccess

/** Accepts only timestamps with an explicit UTC offset, like Pydantic's AwareDatetime. */
private fun isAwareTimestamp(value: String) = runCatching { OffsetDateTime.parse(value) }.isSuccess

@Serializable
enum class BenchCategory {
    @SerialName("meeting") MEETING,
    @SerialName("workshop") WORKSHOP,
    @SerialName("conference") CONFERENCE,
}

@Serializable
data class BenchLocation(val name: String, val city: String) {
    init {
        rule(name.isNotBlank(), "location.name must not be blank")
        rule(city.isNotBlank(), "location.city must not be blank")
    }
}

@Serializable
data class BenchEvent(
    val title: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("attendee_count") val attendeeCount: Int,
    val category: BenchCategory,
    val location: BenchLocation,
    val tags: List<String> = emptyList(),
    val notes: String? = null,
) {
    init {
        rule(title.isNotBlank(), "title must not be blank")
        rule(isAwareTimestamp(startsAt), "starts_at must be an ISO-8601 timestamp with a UTC offset")
        rule(attendeeCount >= 0, "attendee_count must be >= 0")
    }
}

@Serializable
enum class BenchClauseKind {
    @SerialName("payment") PAYMENT,
    @SerialName("termination") TERMINATION,
    @SerialName("confidentiality") CONFIDENTIALITY,
}

@Serializable
data class BenchClause(val kind: BenchClauseKind, val text: String, val negotiated: Boolean) {
    init {
        rule(text.isNotBlank(), "clause text must not be blank")
    }
}

@Serializable
data class BenchContract(
    val title: String,
    val parties: List<String>,
    @SerialName("effective_date") val effectiveDate: String,
    @SerialName("expiration_date") val expirationDate: String? = null,
    @SerialName("payment_days") val paymentDays: Int,
    @SerialName("auto_renew") val autoRenew: Boolean,
    val clauses: List<BenchClause>,
) {
    init {
        rule(title.isNotBlank(), "title must not be blank")
        rule(parties.size >= 2, "parties must have at least 2 entries")
        rule(parties.all { it.isNotBlank() }, "parties must contain nonempty names")
        rule(isDate(effectiveDate), "effective_date must be a valid YYYY-MM-DD date")
        rule(expirationDate == null || isDate(expirationDate), "expiration_date must be a valid YYYY-MM-DD date")
        rule(paymentDays in 0..365, "payment_days must be between 0 and 365")
        rule(clauses.isNotEmpty(), "clauses must have at least 1 entry")
        if (expirationDate != null) {
            rule(LocalDate.parse(expirationDate) >= LocalDate.parse(effectiveDate), "expiration_date must be on or after effective_date")
        }
    }
}

@Serializable
data class BenchComment(
    val author: String,
    val body: String,
    val replies: List<BenchComment> = emptyList(),
) {
    init {
        rule(author.isNotBlank(), "author must not be blank")
        rule(body.isNotBlank(), "body must not be blank")
    }
}

private val LAST4 = Regex("""^\d{4}$""")
private val ROUTING = Regex("""^\d{9}$""")
private val ACCOUNT = Regex("""^\d{4,17}$""")

@Serializable
sealed interface BenchPayment {
    @Serializable
    @SerialName("card")
    data class Card(val last4: String, val holder: String) : BenchPayment {
        init {
            rule(LAST4.matches(last4), "last4 must be exactly 4 digits")
            rule(holder.isNotBlank(), "holder must not be blank")
        }
    }

    @Serializable
    @SerialName("bank")
    data class Bank(val routing: String, val account: String) : BenchPayment {
        init {
            rule(ROUTING.matches(routing), "routing must be exactly 9 digits")
            rule(ACCOUNT.matches(account), "account must be 4 to 17 digits")
        }
    }

    @Serializable
    @SerialName("cash")
    data object Cash : BenchPayment
}

@Serializable
data class BenchOrder(val id: String, val total: Double, val payment: BenchPayment) {
    init {
        rule(id.isNotBlank(), "id must not be blank")
        rule(total >= 0, "total must be >= 0")
    }
}

/** Case-file target name -> serializer. Must match BENCH_SCHEMAS in the Python harness. */
val BENCH_TARGETS: Map<String, KSerializer<*>> = mapOf(
    "event" to BenchEvent.serializer(),
    "contract" to BenchContract.serializer(),
    "thread" to BenchComment.serializer(),
    "order" to BenchOrder.serializer(),
)
