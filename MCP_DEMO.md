# Demo MCP server

This repository now includes a tiny MCP server at
[`demo_mcp_server.py`](./demo_mcp_server.py). It communicates over stdio, so it
works with MCP clients that launch local servers.

The server uses Pydantic for argument validation and JSON Schema generation.
Install its dependency once from the project directory:

```powershell
& .venv\Scripts\python.exe -m pip install -r requirements-mcp.txt
```

## Configure a client

Use this command in the client’s MCP server configuration:

```json
{
  "mcpServers": {
    "type-safe-llm-demo": {
      "command": "python",
      "args": ["C:/Users/rameshr/Desktop/Senior Capstone/type-safe-llm/demo_mcp_server.py"]
    }
  }
}
```

The server exposes:

- `greet(name)` — returns a friendly greeting.
- `add(a, b)` — returns the sum of two numbers.

The server writes protocol messages only to stdout. If you add diagnostics,
write them to stderr so the MCP stream remains valid.
