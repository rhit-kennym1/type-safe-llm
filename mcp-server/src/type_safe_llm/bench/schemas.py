"""Benchmark target types, mirrored exactly by kotlin-library/bench/.../BenchTypes.kt.

Field names, optionality, defaults and domain rules must match the Kotlin versions, or the comparison
measures schema differences instead of approach differences. The experiment report checks parity by
diffing the JSON Schemas both sides generate.
"""

from datetime import date
from typing import Annotated, Literal

from pydantic import AwareDatetime, Field, model_validator

from ..schemas import StrictModel


class BenchLocation(StrictModel):
    name: str = Field(min_length=1)
    city: str = Field(min_length=1)


class BenchEvent(StrictModel):
    title: str = Field(min_length=1)
    starts_at: AwareDatetime
    attendee_count: int = Field(ge=0)
    category: Literal["meeting", "workshop", "conference"]
    location: BenchLocation
    tags: list[str] = Field(default_factory=list)
    notes: str | None = None


class BenchClause(StrictModel):
    kind: Literal["payment", "termination", "confidentiality"]
    text: str = Field(min_length=1)
    negotiated: bool


class BenchContract(StrictModel):
    title: str = Field(min_length=1)
    parties: list[str] = Field(min_length=2)
    effective_date: date
    expiration_date: date | None = None
    payment_days: int = Field(ge=0, le=365)
    auto_renew: bool
    clauses: list[BenchClause] = Field(min_length=1)

    @model_validator(mode="after")
    def check_dates(self) -> "BenchContract":
        if self.expiration_date and self.expiration_date < self.effective_date:
            raise ValueError("expiration_date must be on or after effective_date")
        if any(not party.strip() for party in self.parties):
            raise ValueError("parties must contain nonempty names")
        return self


class BenchComment(StrictModel):
    author: str = Field(min_length=1)
    body: str = Field(min_length=1)
    replies: list["BenchComment"] = Field(default_factory=list)


class BenchCard(StrictModel):
    type: Literal["card"]
    last4: str = Field(pattern=r"^\d{4}$")
    holder: str = Field(min_length=1)


class BenchBank(StrictModel):
    type: Literal["bank"]
    routing: str = Field(pattern=r"^\d{9}$")
    account: str = Field(pattern=r"^\d{4,17}$")


class BenchCash(StrictModel):
    type: Literal["cash"]


class BenchOrder(StrictModel):
    id: str = Field(min_length=1)
    total: float = Field(ge=0)
    payment: Annotated[BenchCard | BenchBank | BenchCash, Field(discriminator="type")]


BENCH_SCHEMAS: dict[str, type[StrictModel]] = {
    "event": BenchEvent, "contract": BenchContract, "thread": BenchComment, "order": BenchOrder,
}
