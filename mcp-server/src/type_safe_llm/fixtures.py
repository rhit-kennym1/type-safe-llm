"""Synthetic demo data and deliberately invalid simulated model responses."""

import copy
import json

SOURCES = {
    "event": "Type-Safe LLM Demo is a workshop starting 2026-10-05 at 14:00 UTC. "
             "There are 30 attendees at Engineering Hall in Indianapolis. Tag: capstone.",
    "contract": "Demo Services Agreement between Acme Labs and Campus Tech runs "
                "from 2026-10-05 through 2027-10-05. Payment is due within 30 days. "
                "No automatic renewal. The payment clause 'Invoices are due within "
                "30 days.' was negotiated; the confidentiality clause 'Keep shared "
                "information confidential.' was not negotiated.",
}

EXAMPLES = {
    "event": {
        "title": "Type-Safe LLM Demo", "starts_at": "2026-10-05T14:00:00Z",
        "attendee_count": 30, "category": "workshop",
        "location": {"name": "Engineering Hall", "city": "Indianapolis"},
        "tags": ["capstone"],
    },
    "contract": {
        "title": "Demo Services Agreement", "parties": ["Acme Labs", "Campus Tech"],
        "effective_date": "2026-10-05", "expiration_date": "2027-10-05",
        "payment_days": 30, "auto_renew": False,
        "clauses": [
            {"kind": "payment", "text": "Invoices are due within 30 days.", "negotiated": True},
            {"kind": "confidentiality", "text": "Keep shared information confidential.", "negotiated": False},
        ],
    },
}

SCENARIOS = ("valid", "wrong_type", "missing_field", "malformed_json", "domain_error", "exhausted")


def simulated_responses(schema_name: str, scenario: str) -> list[str]:
    if scenario not in SCENARIOS:
        raise ValueError(f"Unknown scenario. Choose: {', '.join(SCENARIOS)}")
    good = json.dumps(EXAMPLES[schema_name])
    bad = copy.deepcopy(EXAMPLES[schema_name])
    count_field = "attendee_count" if schema_name == "event" else "payment_days"
    if scenario == "valid":
        return [good]
    if scenario == "missing_field":
        del bad["title"]
    elif scenario == "domain_error":
        bad[count_field] = -1
    else:
        bad[count_field] = "thirty"
    invalid = '{"title": ' if scenario == "malformed_json" else json.dumps(bad)
    return [invalid] if scenario == "exhausted" else [invalid, good]
