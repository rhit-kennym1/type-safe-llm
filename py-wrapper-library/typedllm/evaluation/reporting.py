"""Reporting helpers for evaluation runs."""

from __future__ import annotations

import csv
import json
from dataclasses import asdict
from pathlib import Path
from typing import Any

from .harness import EvaluationRun


def run_to_dict(run: EvaluationRun) -> dict[str, Any]:
    """Convert an evaluation run into JSON-serializable data."""

    return {
        "summary": asdict(run.summary),
        "rates": {
            "success_rate": run.summary.success_rate,
            "first_attempt_success_rate": run.summary.first_attempt_success_rate,
            "expectation_match_rate": run.summary.expectation_match_rate,
            "average_attempts": run.summary.average_attempts,
            "average_latency_ms": run.summary.average_latency_ms,
        },
        "results": [asdict(result) for result in run.results],
    }


def write_json_report(
    run: EvaluationRun,
    path: str | Path,
) -> Path:
    """Write a machine-readable JSON report."""

    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(run_to_dict(run), indent=2),
        encoding="utf-8",
    )
    return output


def write_csv_report(
    run: EvaluationRun,
    path: str | Path,
) -> Path:
    """Write one CSV row per evaluation case."""

    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)

    rows = [asdict(result) for result in run.results]

    fieldnames = [
        "name",
        "success",
        "expected_match",
        "attempt_count",
        "first_attempt_success",
        "latency_ms",
        "input_tokens",
        "output_tokens",
        "provider",
        "model",
        "tags",
        "error",
    ]

    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()

        for row in rows:
            row["tags"] = ",".join(row["tags"])
            writer.writerow(row)

    return output


def format_summary(run: EvaluationRun) -> str:
    """Return a compact human-readable summary."""

    summary = run.summary

    lines = [
        f"Cases: {summary.total_cases}",
        f"Successful: {summary.successful_cases}",
        f"Failed: {summary.failed_cases}",
        f"Success rate: {summary.success_rate:.1%}",
        f"First-attempt success: {summary.first_attempt_success_rate:.1%}",
        f"Average attempts: {summary.average_attempts:.2f}",
        f"Average latency: {summary.average_latency_ms:.2f} ms",
    ]

    if summary.expectation_match_rate is not None:
        lines.append(
            f"Expected-output match: {summary.expectation_match_rate:.1%}"
        )

    if summary.total_input_tokens is not None:
        lines.append(f"Input tokens: {summary.total_input_tokens}")

    if summary.total_output_tokens is not None:
        lines.append(f"Output tokens: {summary.total_output_tokens}")

    return "\n".join(lines)
