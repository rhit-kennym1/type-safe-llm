"""Declared output types; strict validation also applies to nested objects."""

from datetime import date
from typing import Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", str_strip_whitespace=True)


class Location(StrictModel):
    name: str = Field(min_length=1)
    city: str = Field(min_length=1)


class Event(StrictModel):
    title: str = Field(min_length=1)
    starts_at: AwareDatetime
    attendee_count: int = Field(ge=0)
    category: Literal["meeting", "workshop", "conference"]
    location: Location
    tags: list[str] = Field(default_factory=list)
    notes: str | None = None


class Clause(StrictModel):
    kind: Literal["payment", "termination", "confidentiality"]
    text: str = Field(min_length=1)
    negotiated: bool


class Contract(StrictModel):
    title: str = Field(min_length=1)
    parties: list[str] = Field(min_length=2)
    effective_date: date
    expiration_date: date | None = None
    payment_days: int = Field(ge=0, le=365)
    auto_renew: bool
    clauses: list[Clause] = Field(min_length=1)

    @model_validator(mode="after")
    def check_dates(self) -> "Contract":
        if self.expiration_date and self.expiration_date < self.effective_date:
            raise ValueError("expiration_date must be on or after effective_date")
        if any(not party.strip() for party in self.parties):
            raise ValueError("parties must contain nonempty names")
        return self


SCHEMAS: dict[str, type[StrictModel]] = {"event": Event, "contract": Contract}


def get_schema(name: str) -> type[StrictModel]:
    if name not in SCHEMAS:
        raise ValueError(f"Unknown schema {name!r}. Choose: {', '.join(SCHEMAS)}")
    return SCHEMAS[name]
