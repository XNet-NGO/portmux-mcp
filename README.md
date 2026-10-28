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
| `add_route` | `proto` (tcp/udp), `listen_addr?`, `listen_port`, `target_addr?`, `target_port`, `egress?`, `egress_arg?` | Add a forwarding route; returns its id. Takes effect immediately if running. |
| `add_socks` | `listen_addr?`, `listen_port`, `egress?`, `egress_arg?` | Add a SOCKS5 proxy listener (no-auth, CONNECT + UDP ASSOCIATE). An **open proxy** to arbitrary per-connection destinations; requires `PORTMUX_ALLOW_ANY_TARGET=1`. |
| `add_tunnel` | `server_addr`, `server_port`, `psk`, `target_addr?`, `target_port?`, `cipher?`, `egress?`, `egress_arg?`, `rlisten_addr?`, `rlisten_port?`, `rtarget_addr?`, `rtarget_port?`, `rproto?`, `jumps?`/`jump_hops?`, `jump_target_addr?`, `jump_target_port?`, `jump_proto?` | Add an encrypted NoisePSK_NN tunnel **client**. Dials a rendezvous server and splices the decrypted stream to a local target. Supports reverse-forward (ssh -R style) and multi-hop jump routing. Reaching an off-device server requires `PORTMUX_ALLOW_ANY_TARGET=1`. |
| `add_peer` | `listen_addr?`, `listen_port`, `psk`, `cipher?`, `egress?`, `egress_arg?` | Add a relay **peer** (single-port peek-demux tunnel server) for multi-hop jump chains. Relays OPENJ to the next hop or connects the terminal target. Requires `PORTMUX_ALLOW_ANY_TARGET=1`. |
| `remove_route` | `id` | Remove a route by id. |
| `list_routes` | — | List configured routes. |
| `status` | — | Running state, route count, and `allow_any_target`. |
| `start` | — | Start forwarding all routes. |
| `stop` | — | Stop forwarding. |

This mirrors the **portable** tool surface of the Android
[switch-mcp](https://github.com/XNet-NGO/switch-mcp) server (same tool names and
params), so the two MCP servers are consistent. The one deliberate difference is
egress: switch-mcp pins to an Android network (`wifi`/`cellular`/`ethernet`),
whereas this desktop server pins with `source`/`device` (see below).

### Egress (desktop interface pinning)

`add_route`, `add_socks`, `add_tunnel`, and `add_peer` all take an optional
`egress` + `egress_arg` pair that selects how every upstream (target) socket
leaves the host:

| `egress` | `egress_arg` | Effect | Privilege |
|----------|--------------|--------|-----------|
| `none` (default) | — | Default route / loopback; no pinning. | none |
| `source` / `src` | local source IP | Bind the upstream socket to that source IP (`src=IP`). | none |
| `device` / `dev` | interface name | `SO_BINDTODEVICE` the upstream socket to that interface (`dev=IFNAME`). | **CAP_NET_RAW / root** |

The Android-only `NETWORK` egress (`android_setsocknetwork`) from switch-mcp is
**not** offered here; on desktop, `device` (privileged) and `source`
(unprivileged) provide the equivalent interface-pinning capability.

## Engine backends

The MCP layer talks to a `PortmuxEngine` interface. Two backends share it:

- **Native (embedded) backend** (`NativePortmuxEngine`, **default**) — portmux is
  compiled from source into a JNI library (`libportmuxjni`) that is bundled in
  the JAR and loaded at runtime. portmux runs **in-process**; nothing is
  installed separately. This is the recommended, self-contained setup.
- **CLI backend** (`CliPortmuxEngine`, fallback) — spawns an external portmux
  binary (`PORTMUX_BIN`). Used automatically only if the native library is not
  bundled for the current platform. It supports `add_route` and `add_socks`
  (with `source`/`device` egress); `add_tunnel`/`add_peer` are **not** available
  on this backend (they are a distinct single-instance CLI subcommand that needs
  a PSK file and cannot be composed with other routes in one invocation) — use
  the embedded native backend for tunnels/peers.

At startup the server picks the native backend when `libportmuxjni` is present
for the platform, otherwise falls back to the CLI backend.

portmux fixes its routes at `run()`, so both backends keep the route set in
memory and re-run the engine whenever routes change while running.

### Building the embedded native library

The native lib is generated from the vendored `native/portmux` submodule (v0.5.0+,
which also vendors hev-task-system and noise-c). The portmux core is built from
source with `-fPIC` — port forwarding, the SOCKS5 proxy (`portmux-socks.c`), and
the encrypted NoisePSK_NN tunnel (`portmux-tunnel.c` + noise-c) — then linked with
the JNI shim (`native/portmux-jni.c`) into `libportmuxjni.<ext>` under
`src/main/resources/native/<os>-<arch>/`, where it gets bundled into the JAR.

```sh
git submodule update --init --recursive
# host arch:
JAVA_HOME=/path/to/jdk ./native/build-jni.sh
# or an explicit target (aarch64 cross-builds via aarch64-linux-gnu-gcc on x86_64):
JAVA_HOME=/path/to/jdk ./native/build-jni.sh aarch64
./gradlew installDist
```

Supported hosts: linux and macOS, x86_64 and aarch64. On a platform without a
built lib, the server runs with the CLI backend instead.

## Safety

An MCP endpoint that can open arbitrary `listen -> target` forwards is a
powerful capability (it can expose local services or bridge networks). Defaults
are conservative:

- **Loopback targets only.** Non-loopback targets are rejected unless
  `PORTMUX_ALLOW_ANY_TARGET=1` is set.
- **SOCKS / tunnels / peers are gated.** `add_socks` (an open proxy),
  `add_tunnel` (reaching an off-device server), and `add_peer` (relaying to
  arbitrary next hops) are powerful network-pivot capabilities and are rejected
  unless `PORTMUX_ALLOW_ANY_TARGET=1` is set. Tunnel/peer PSKs are kept out of
  the `Route` that is returned/logged.
- **`device` egress needs privilege.** `egress=device` uses `SO_BINDTODEVICE`,
  which requires `CAP_NET_RAW` / root; `egress=source` needs no privilege.
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
| `PORTMUX_BIN` | `portmux` (PATH) | portmux executable for the CLI *fallback* backend |
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

Built and run locally with the **embedded native engine**. The full MCP flow
was exercised over Streamable HTTP: `initialize` → `tools/list` (9 tools:
`add_route`, `add_socks`, `add_tunnel`, `add_peer`, `remove_route`,
`list_routes`, `status`, `start`, `stop`) → `add_route` + `add_socks` (with a
`src=` egress) → `start`, and an actual TCP listener was opened in-process by
the native engine — then `stop` closed it. The safety gates (non-loopback
target rejected; `add_socks` rejected without `PORTMUX_ALLOW_ANY_TARGET`; egress
validation) were confirmed.

## Versions

Kotlin 2.4.10 · MCP Kotlin SDK 0.15.0 · Ktor 3.5.1 · JVM toolchain 21 ·
portmux core v0.5.0 (vendored submodule).
