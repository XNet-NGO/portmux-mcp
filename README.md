# portmux-mcp

An **MCP server** that exposes [portmux](https://github.com/XNet-NGO/portmux)
port-forwarding control as tools, over the **Streamable HTTP** transport — so
any MCP client on any platform (desktop agents, IDEs, other devices) can create
and manage port forwards programmatically.

Built with the official [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
(`io.modelcontextprotocol:kotlin-sdk-server`) on Ktor.

- **Transport:** Streamable HTTP (current MCP standard; single `/mcp` endpoint,
  POST + optional SSE streaming). The deprecated standalone HTTP+SSE transport
  is not used.
- **Bind:** `127.0.0.1` only, **no auth** (local use by default).
- **License:** MIT

## Tools

| Tool | Arguments | Purpose |
|------|-----------|---------|
| `add_route` | `proto` (tcp/udp), `listen_addr?`, `listen_port`, `target_addr?`, `target_port` | Add a forwarding route; returns its id. Takes effect immediately if running. |
| `remove_route` | `id` | Remove a route by id. |
| `list_routes` | — | List configured routes. |
| `status` | — | Running state + route count. |
| `start` | — | Start forwarding all routes. |
| `stop` | — | Stop forwarding. |

## Engine backends

The MCP layer talks to a `PortmuxEngine` interface. Two backends share it:

- **CLI backend** (default, `CliPortmuxEngine`) — spawns the portmux CLI binary.
  Cross-platform; the right fit on desktop and on Android via Termux/Shizuku.
  Uses the published [portmux release artifacts](https://github.com/XNet-NGO/portmux/releases).
- **Native/JNI backend** (for in-process use on Android) — can implement the
  same interface against `libportmux.a`, reusing the JNI shim from the
  Switchboard app, without any change to the MCP tool layer.

portmux fixes its routes at `run()`, so the CLI backend keeps the route set in
memory and (re)spawns the CLI whenever routes change while running.

## Safety

An MCP endpoint that can open arbitrary `listen -> target` forwards is a
powerful capability (it can expose local services or bridge networks). Defaults
are conservative:

- **Loopback targets only.** Non-loopback targets are rejected unless
  `PORTMUX_ALLOW_ANY_TARGET=1` is set.
- **Local bind, no auth.** The server binds `127.0.0.1`; any local process can
  drive it. For shared/remote use, put it behind a reverse proxy with auth, or
  extend it with the SDK's bearer-auth transport.

## Build & run

```sh
./gradlew installDist
PORTMUX_BIN=/path/to/portmux build/install/portmux-mcp/bin/portmux-mcp 3339
# -> Streamable HTTP MCP server on http://127.0.0.1:3339/mcp
```

Environment:

| Var | Default | Meaning |
|-----|---------|---------|
| `PORTMUX_BIN` | `portmux` (PATH) | portmux executable for the CLI backend |
| `PORTMUX_ALLOW_ANY_TARGET` | unset | `1` allows non-loopback targets |

## Connect an MCP client

Point any Streamable-HTTP MCP client at `http://127.0.0.1:3339/mcp`. Example
raw handshake:

```sh
curl -s -X POST http://127.0.0.1:3339/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"c","version":"1"}}}'
# returns serverInfo + an mcp-session-id header; reuse that header on subsequent calls
```

## Verified

Built and run locally; the full MCP flow was exercised over Streamable HTTP:
`initialize` → `tools/list` (6 tools) → `add_route` → `list_routes` → `status`,
plus the loopback safety gate (non-loopback target rejected) and graceful
error when the portmux binary is absent.

## Versions

Kotlin 2.4.10 · MCP Kotlin SDK 0.15.0 · Ktor 3.5.1 · JVM toolchain 21.
