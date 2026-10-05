package dev.typedllm.fixtures

import kotlinx.serialization.Serializable

@Serializable
enum class ClauseType { PAYMENT, TERMINATION, CONFIDENTIALITY, LIABILITY, OTHER }

@Serializable
data class Clause(val title: String, val type: ClauseType, val summary: String)

@Serializable
data class Contract(
    val parties: List<String>,
    val effectiveDate: String,
    val totalValue: Double,
    val currency: String,
    val clauses: List<Clause>,
    val governingLaw: String? = null,
)
