"""Provider integrations.

Only providers that are part of the base installation are exported here.
Optional providers such as Ollama should be imported from their own module so
installing the core package does not require every provider SDK.
"""

from .base import Message, Provider, ProviderResponse
from .fake import FakeProvider

__all__ = [
    "FakeProvider",
    "Message",
    "Provider",
    "ProviderResponse",
]
