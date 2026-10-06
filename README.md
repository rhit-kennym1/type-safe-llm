# Type-Safe LLM

This senior capstone explores two approaches to structured LLM output: an
application library and a discoverable MCP validation server.

| Approach | Folder | Documentation |
| --- | --- | --- |
| Kotlin wrapper library and demo UI | `kotlin-library/` | [Kotlin setup](kotlin-library/README.md) |
| Python MCP server and presentation demo | `mcp-server/` | [MCP setup](mcp-server/README.md) |

The approaches have separate source code, tests, build configuration, and demos.
Project design documents are in [background-information/](background-information/).

## Run the MCP demo

From the repository root in PowerShell:

```powershell
cd mcp-server
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e '.[dev]'
.\.venv\Scripts\python.exe -m type_safe_llm.demo
```

This opens a browser report generated through a real MCP client/server session.
Model responses are simulated, so no API key is needed.

For a presentation without installation, download
[mcp-server/docs/demo-report.html](mcp-server/docs/demo-report.html)
and open it in your browser. It works offline and displays saved results.
