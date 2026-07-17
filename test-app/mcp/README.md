# MobileWebView test-app MCP

Stdio MCP server that talks to the test-app localhost control API
(`http://127.0.0.1:17321` by default).

Agent skill (tool-agnostic): [`skills/mobilewebview-test-app/`](../../skills/mobilewebview-test-app/).

## Setup

```bash
cd test-app/mcp
uv sync
```

## MCP host config

Merge [`mcp.json`](mcp.json) into your host config, for example:

- Cursor: project or user `mcp.json`
- Claude Code / other: whatever path that host uses for MCP servers

```json
{
  "mcpServers": {
    "mobilewebview-test-app": {
      "command": "uv",
      "args": ["run", "--directory", "test-app/mcp", "python", "server.py"]
    }
  }
}
```

Use an absolute `--directory` if the host does not start with the repo as cwd.

## Manual smoke

```bash
# terminal 1
./run_macos.sh

# terminal 2
curl -s http://127.0.0.1:17321/health
curl -s http://127.0.0.1:17321/rpc \
  -H 'content-type: application/json' \
  -d '{"method":"list_screens","params":{}}'
```

Env overrides: `MWV_AGENT_PORT`, `MWV_AGENT_URL`.
