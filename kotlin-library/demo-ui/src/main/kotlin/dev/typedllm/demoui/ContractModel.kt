package dev.typedllm.demoui

import kotlinx.serialization.Serializable

// The type the demo asks the model for, from the design doc's contract-processing use case.

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

/** Source shown in the UI next to the generated schema. Keep in sync with the declarations above. */
internal val CONTRACT_SOURCE = """
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
""".trimIndent()
