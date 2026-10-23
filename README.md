# Type-Safe LLM

This senior capstone explores two approaches to structured LLM output: an
application library and a discoverable MCP validation server.

| Approach | Folder | Documentation |
| --- | --- | --- |
| Kotlin wrapper library and demo UI | `kotlin-library/` | [Kotlin setup](kotlin-library/README.md) |
| Python MCP server and presentation demo | `mcp-server/` | [MCP setup](mcp-server/README.md) |

| Experiments comparing both | `experiments/` | [Method and commands](experiments/README.md) · [Latest report](experiments/results/REPORT.md) |

The approaches have separate source code, tests, build configuration, and demos.

## Design documents

| Document | Covers |
| --- | --- |
| [Design document v2](background-information/design-document-v2.md) | Current design, results, open questions |
| [Architecture](background-information/architecture.md) | Diagrams of both approaches and the harness |
| [Design decisions](background-information/design-decisions.md) | Why each choice was made |
| [Market landscape](background-information/market-landscape.md) | Existing tools and what MCP provides natively |
| [Deliverables audit](background-information/deliverables-audit.md) | Status of every commitment, by track |

The original design document and architecture spike are kept in the same folder.

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
