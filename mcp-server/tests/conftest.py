import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from type_safe_llm.providers import LiveConfig


@pytest.fixture
def fake_endpoint():
    """Local OpenAI-compatible endpoint; replies are queued by the test."""
    replies: list[str] = []
    seen: list[dict] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            seen.append({"path": self.path, "auth": self.headers["Authorization"],
                         "body": json.loads(self.rfile.read(int(self.headers["Content-Length"])))})
            reply = replies.pop(0)
            if isinstance(reply, int):  # an HTTP error status
                self.send_response(reply)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            message = reply if isinstance(reply, dict) else {"role": "assistant", "content": reply}
            body = json.dumps({"choices": [{"message": message}]}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    config = LiveConfig(base_url=f"http://127.0.0.1:{server.server_port}/v1", model="fake", timeout_seconds=5)
    yield config, replies, seen
    server.shutdown()
