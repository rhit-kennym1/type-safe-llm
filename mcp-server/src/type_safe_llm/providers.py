"""Providers for any OpenAI-compatible chat endpoint: Ollama (default), Gemini, OpenAI, ...

Gemini's free tier works through its OpenAI-compatible endpoint:
  LIVE_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
  LIVE_API_KEY=<key from Google AI Studio>   LIVE_MODEL=gemini-2.5-flash
"""

import json
import os
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

OLLAMA_URL = "http://localhost:11434/v1"
DEFAULT_MODEL = "llama3.2:3b"
MAX_RATE_LIMIT_RETRIES = 2
_sleep = time.sleep  # replaced in tests


def load_dotenv(paths: list[Path] | None = None) -> None:
    """Load KEY=VALUE lines from the first `.env` found into os.environ.

    Looks in the working directory, then the mcp-server folder. Variables already set
    in the real environment win. Supports `#` comment lines and optional quotes.
    """
    for path in paths or [Path.cwd() / ".env", Path(__file__).resolve().parents[2] / ".env"]:
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            os.environ.setdefault(key.strip(), value.strip().strip("\"'"))
        return


class ProviderError(Exception):
    """The model could not be reached or returned something unusable."""


@dataclass(frozen=True)
class LiveConfig:
    base_url: str = OLLAMA_URL
    api_key: str = "ollama"  # Ollama ignores the key, but the header must be present
    model: str = DEFAULT_MODEL
    timeout_seconds: float = 120.0

    @classmethod
    def from_environment(cls, env: Mapping[str, str] | None = None) -> "LiveConfig":
        if env is None:
            load_dotenv()
            env = os.environ
        return cls(
            base_url=env.get("LIVE_BASE_URL", OLLAMA_URL),
            api_key=env.get("LIVE_API_KEY", "ollama"),
            model=env.get("LIVE_MODEL", DEFAULT_MODEL),
        )


def _retry_delay(exc: urllib.error.HTTPError, attempt: int) -> float:
    try:
        return min(float(exc.headers.get("Retry-After", "")), 30.0)
    except (TypeError, ValueError):
        return min(5.0 * (attempt + 1), 30.0)


def _describe_http_error(config: LiveConfig, code: int, with_tools: bool) -> str:
    hints = {
        401: "check LIVE_API_KEY",
        403: "check LIVE_API_KEY and model access",
        404: "check LIVE_BASE_URL and LIVE_MODEL",
        429: "rate limited (free tiers have low limits); wait and retry",
    }
    hint = hints.get(code, "")
    if code == 400 and with_tools:
        hint = "this model or endpoint may not support tool calling"
    return f"{config.base_url} returned HTTP {code}." + (f" {hint}." if hint else "")


def chat_completion(
    config: LiveConfig, messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """POST one chat request and return the assistant message exactly as the provider sent it.

    The raw message is returned (not rebuilt) so providers that attach extra fields to
    tool calls get them back unchanged on the next turn.
    """
    url = config.base_url.rstrip("/") + "/chat/completions"
    payload: dict[str, Any] = {"model": config.model, "messages": messages, "temperature": 0}
    if tools:
        payload["tools"] = tools
    body = json.dumps(payload).encode()
    for attempt in range(MAX_RATE_LIMIT_RETRIES + 1):
        request = urllib.request.Request(url, data=body, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {config.api_key}",
        })
        try:
            with urllib.request.urlopen(request, timeout=config.timeout_seconds) as response:
                data = json.load(response)
            message = data["choices"][0]["message"]
            if not isinstance(message, dict):
                raise TypeError("message is not an object")
            return message
        except urllib.error.HTTPError as exc:
            if exc.code == 429 and attempt < MAX_RATE_LIMIT_RETRIES:
                _sleep(_retry_delay(exc, attempt))
                continue
            raise ProviderError(_describe_http_error(config, exc.code, bool(tools))) from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError, IndexError, TypeError) as exc:
            # Type only: exception text can echo URLs or credentials.
            hint = " The model may still be loading; try again." if isinstance(exc, TimeoutError) else ""
            raise ProviderError(
                f"Could not get a response from {config.base_url} ({type(exc).__name__}).{hint}") from None
    raise ProviderError("Rate limited repeatedly; wait and retry.")  # pragma: no cover


def chat_provider(config: LiveConfig) -> Callable[[str], str]:
    """Return a `generate(prompt) -> text` function for `generate_validated`."""

    def generate(prompt: str) -> str:
        content = chat_completion(config, [{"role": "user", "content": prompt}]).get("content")
        if not isinstance(content, str):
            raise ProviderError("Model returned no text content.")
        return content

    return generate
