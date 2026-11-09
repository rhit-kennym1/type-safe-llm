"""Main orchestration pipeline for type-safe JSON generation."""

from __future__ import annotations

from time import perf_counter
from typing import Any

from ..errors import OutputParsingError, TypedOutputError
from ..models.attempt import Attempt
from ..models.request import GenerateRequest
from ..models.result import GenerateResult
from ..providers.base import Message, Provider
from .parsing import parse_json
from .prompts import correction_prompt, structured_output_prompt
from .retry import decide_retry
from .schema import validate_schema
from .validation import validate_output


class GenerationPipeline:
    """Coordinate schema checking, provider calls, parsing, validation, and retry."""

    def __init__(self, provider: Provider) -> None:
        self._provider = provider

    async def run(self, request: GenerateRequest) -> GenerateResult:
        validate_schema(request.schema)

        messages: list[Message] = [
            Message(
                role="system",
                content=structured_output_prompt(request.schema),
            ),
            Message(
                role="user",
                content=request.prompt,
            ),
        ]

        attempts: list[Attempt] = []

        for attempt_number in range(1, request.config.max_attempts + 1):
            started = perf_counter()

            response = await self._provider.complete(
                messages,
                response_schema=request.schema,
            )

            parsed: Any | None = None
            errors: tuple[str, ...]

            try:
                parsed = parse_json(
                    response.text,
                    allow_markdown_fences=request.config.allow_markdown_fences,
                )
            except OutputParsingError as exc:
                errors = (str(exc),)
            else:
                errors = tuple(
                    str(issue)
                    for issue in validate_output(parsed, request.schema)
                )

            duration_ms = (perf_counter() - started) * 1000.0

            attempt = Attempt(
                number=attempt_number,
                raw_output=response.text,
                parsed_output=parsed,
                errors=errors,
                duration_ms=duration_ms,
                provider=response.provider,
                model=response.model,
                input_tokens=response.input_tokens,
                output_tokens=response.output_tokens,
            )
            attempts.append(attempt)

            if not errors:
                return GenerateResult(
                    data=parsed,
                    attempts=tuple(attempts),
                )

            decision = decide_retry(
                attempt_number=attempt_number,
                max_attempts=request.config.max_attempts,
            )

            if not decision.retry:
                break

            messages.extend(
                [
                    Message(role="assistant", content=response.text),
                    Message(
                        role="user",
                        content=correction_prompt(errors),
                    ),
                ]
            )

        raise TypedOutputError(tuple(attempts))
