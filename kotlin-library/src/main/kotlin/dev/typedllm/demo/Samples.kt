package dev.typedllm.demo

/** Scripted model outputs for tests and the demo. */
object Samples {
    const val PROSE = "Sure! The contract is between Acme Corp and Globex LLC, worth about $120k."

    const val MISSING_FIELD =
        """{"parties":["Acme Corp","Globex LLC"],"totalValue":120000,"currency":"USD","clauses":[]}"""

    const val BAD_ENUM =
        """{"parties":["Acme Corp"],"effectiveDate":"2026-01-01","totalValue":1,"currency":"USD",""" +
            """"clauses":[{"title":"Fees","type":"MONEY","summary":"x"}]}"""

    const val UNKNOWN_FIELD =
        """{"parties":["Acme Corp"],"effectiveDate":"2026-01-01","totalValue":1,"currency":"USD","clauses":[],"notes":"hi"}"""

    val VALID_JSON = """
        ```json
        {
          "parties": ["Acme Corp", "Globex LLC"],
          "effectiveDate": "2026-01-01",
          "totalValue": 120000.0,
          "currency": "USD",
          "clauses": [
            {"title": "Payment Terms", "type": "PAYMENT", "summary": "Net 30 invoicing."},
            {"title": "Termination", "type": "TERMINATION", "summary": "Either party, 60 days notice."}
          ]
        }
        ```
    """.trimIndent()

    val VALID_YAML = """
        parties:
          - Acme Corp
          - Globex LLC
        effectiveDate: "2026-01-01"
        totalValue: 120000.0
        currency: USD
        clauses:
          - title: Payment Terms
            type: PAYMENT
            summary: Net 30 invoicing.
    """.trimIndent()
}
