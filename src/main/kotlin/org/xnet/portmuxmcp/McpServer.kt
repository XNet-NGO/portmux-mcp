package org.xnet.portmuxmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the MCP server exposing portmux control as tools, backed by [engine].
 * Each call to this creates a fresh [Server] bound to the same engine (the
 * Streamable HTTP transport makes one server per session).
 *
 * The tool surface mirrors switch-mcp's PORTABLE subset (same tool names and
 * param schemas: add_route, add_socks, add_tunnel, add_peer, remove_route,
 * list_routes, status, start, stop) so the two MCP servers are consistent. The
 * difference is egress: this desktop server exposes none|source|device
 * (src=IP / dev=IFNAME) rather than switch-mcp's Android wifi|cellular|ethernet
 * network pins.
 */
fun buildPortmuxMcpServer(engine: PortmuxEngine): Server {
    val server = Server(
        Implementation(name = "portmux-mcp", version = "0.2.1"),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
    fun err(text: String) =
        CallToolResult(content = listOf(TextContent("error: $text")), isError = true)

    // Parse the shared egress args. egress: "none"|"default" (default),
    // "source"/"src" (egress_arg = local source IP, SO bind, no privilege), or
    // "device"/"dev" (egress_arg = interface name, SO_BINDTODEVICE, needs
    // CAP_NET_RAW / root). Returns Pair(mode, arg).
    fun parseEgress(a: Map<String, kotlinx.serialization.json.JsonElement>):
        Pair<EgressMode, String> {
        val egress = a["egress"]?.jsonPrimitive?.content?.lowercase()?.trim()
        val egressArg = a["egress_arg"]?.jsonPrimitive?.content ?: ""
        return when (egress) {
            null, "", "none", "default" -> Pair(EgressMode.NONE, "")
            "source", "src" -> Pair(EgressMode.SOURCE, egressArg)
            "device", "dev" -> Pair(EgressMode.DEVICE, egressArg)
            else -> error("egress must be one of: none|source|device")
        }
    }

    // Shared schema fragment for the egress args.
    fun putEgressProps(b: kotlinx.serialization.json.JsonObjectBuilder) {
        b.putJsonObject("egress") {
            put("type", "string")
            put(
                "description",
                "upstream egress: none (default route) | source (bind local " +
                    "source IP via egress_arg, src=IP, no privilege) | device " +
                    "(SO_BINDTODEVICE to interface via egress_arg, dev=IFNAME, " +
                    "needs CAP_NET_RAW / root)",
            )
        }
        b.putJsonObject("egress_arg") {
            put("type", "string")
            put(
                "description",
                "for egress=source: the local source IP to bind (src=); " +
                    "for egress=device: the interface name (dev=)",
            )
        }
    }

    // --- add_route ---
    server.addTool(
        name = "add_route",
        description = "Add a port-forwarding route. proto is 'tcp' or 'udp'. " +
            "Targets must be loopback unless the server was started with " +
            "PORTMUX_ALLOW_ANY_TARGET=1. If forwarding is running, the route " +
            "takes effect immediately.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("proto") {
                    put("type", "string"); put("description", "tcp | udp")
                }
                putJsonObject("listen_addr") {
                    put("type", "string"); put("description", "listen address (default 127.0.0.1)")
                }
                putJsonObject("listen_port") {
                    put("type", "integer"); put("description", "local port to listen on")
                }
                putJsonObject("target_addr") {
                    put("type", "string"); put("description", "target address (default 127.0.0.1)")
                }
                putJsonObject("target_port") {
                    put("type", "integer"); put("description", "target port to forward to")
                }
                putEgressProps(this)
            },
            required = listOf("proto", "listen_port", "target_port"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val proto = when (a["proto"]?.jsonPrimitive?.content?.lowercase()) {
                "udp" -> Proto.UDP
                "tcp", null -> Proto.TCP
                else -> error("proto must be tcp or udp")
            }
            val (egress, egressArg) = parseEgress(a)
            val route = engine.addRoute(
                proto = proto,
                listenAddr = a["listen_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                listenPort = a["listen_port"]!!.jsonPrimitive.content.toInt(),
                targetAddr = a["target_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                targetPort = a["target_port"]!!.jsonPrimitive.content.toInt(),
                egress = egress,
                egressArg = egressArg,
            )
            ok("added route ${route.id}: ${route.summary()}")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- add_socks ---
    server.addTool(
        name = "add_socks",
        description = "Add a SOCKS5 proxy listener (RFC1928 no-auth, CONNECT and " +
            "UDP ASSOCIATE). WARNING: this creates an OPEN PROXY that dials " +
            "ARBITRARY destinations chosen per-connection by the client, so it " +
            "implies arbitrary egress and REQUIRES the server to have been " +
            "started with PORTMUX_ALLOW_ANY_TARGET=1 (otherwise it is rejected). " +
            "Binds loopback by default; do not bind below port 1024. The optional " +
            "egress pins every upstream connection to a local source IP (src=IP) " +
            "or interface (dev=IFNAME). If forwarding is running, the listener " +
            "takes effect immediately.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("listen_addr") {
                    put("type", "string"); put("description", "listen address (default 127.0.0.1)")
                }
                putJsonObject("listen_port") {
                    put("type", "integer"); put("description", "local port to listen on (e.g. 1080)")
                }
                putEgressProps(this)
            },
            required = listOf("listen_port"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val (egress, egressArg) = parseEgress(a)
            val route = engine.addSocks(
                listenAddr = a["listen_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                listenPort = a["listen_port"]!!.jsonPrimitive.content.toInt(),
                egress = egress,
                egressArg = egressArg,
            )
            ok("added socks ${route.id}: ${route.summary()}")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- add_tunnel ---
    server.addTool(
        name = "add_tunnel",
        description = "Add an ENCRYPTED TUNNEL client (Noise NoisePSK_NN: ephemeral " +
            "X25519 + 32-byte pre-shared key; AES-256-GCM or ChaCha20-Poly1305). " +
            "Dials an arbitrary remote server over one outbound connection and " +
            "splices the decrypted stream to a local target (default " +
            "127.0.0.1:5555, i.e. adbd). WARNING: reaching an arbitrary " +
            "off-device server REQUIRES the server to have been started with " +
            "PORTMUX_ALLOW_ANY_TARGET=1. The optional egress pins the outbound " +
            "leg to a local source IP (src=) or interface (dev=). REVERSE-FORWARD " +
            "(v0.4.0): rlisten_port>0 opens a REVERSE LISTENER on the CLIENT " +
            "(ssh -R style): connections there become client-originated reverse " +
            "streams the server splices to rtarget_addr:rtarget_port. rproto sets " +
            "the reverse listener protocol: tcp (default), udp, or both (dual-stack). " +
            "MULTI-HOP JUMPS (v0.5.0): pass jumps as an array of \"addr:port\" " +
            "next-hops (or jump_hops as a comma-separated string) to make this " +
            "client the chain EDGE/originator. jumps[0] MUST equal " +
            "server_addr:server_port (the carrier dest); the OPENJ carries the " +
            "remaining hops + jump_target_addr:jump_target_port (final dest). " +
            "jump_proto is the final leg: tcp (default) or udp. A jump chain " +
            "requires a reverse listener (rlisten_port>0) as the local plaintext " +
            "entry that emits OPENJ. Relay the chain through add_peer nodes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("server_addr") {
                    put("type", "string"); put("description", "rendezvous server IP/host to dial")
                }
                putJsonObject("server_port") {
                    put("type", "integer"); put("description", "rendezvous server port")
                }
                putJsonObject("psk") {
                    put("type", "string")
                    put("description", "pre-shared key, 64 hex chars (32 bytes); must match the server")
                }
                putJsonObject("target_addr") {
                    put("type", "string"); put("description", "local target address (default 127.0.0.1)")
                }
                putJsonObject("target_port") {
                    put("type", "integer"); put("description", "local target port (default 5555 = adbd)")
                }
                putJsonObject("cipher") {
                    put("type", "string"); put("description", "aesgcm (default) | chacha")
                }
                putEgressProps(this)
                putJsonObject("rlisten_addr") {
                    put("type", "string")
                    put("description", "reverse listener address on the client (default 127.0.0.1; v0.4.0)")
                }
                putJsonObject("rlisten_port") {
                    put("type", "integer")
                    put("description", "reverse listener port on the client (0=none; v0.4.0)")
                }
                putJsonObject("rtarget_addr") {
                    put("type", "string")
                    put("description", "reverse target address on the server (default 127.0.0.1; v0.4.0)")
                }
                putJsonObject("rtarget_port") {
                    put("type", "integer")
                    put("description", "reverse target port on the server (required if rlisten_port>0; v0.4.0)")
                }
                putJsonObject("rproto") {
                    put("type", "string")
                    put("description", "reverse listener protocol: tcp (default), udp, or both (dual-stack; v0.4.0)")
                }
                putJsonObject("jumps") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put(
                        "description",
                        "multi-hop jump list: ordered \"addr:port\" next-hops (v0.5.0). " +
                            "jumps[0] MUST equal server_addr:server_port. Requires rlisten_port>0.",
                    )
                }
                putJsonObject("jump_hops") {
                    put("type", "string")
                    put("description", "alternative to jumps: comma-separated \"addr:port\" next-hops, " +
                        "or a JSON array of \"addr:port\" strings (v0.5.0)")
                }
                putJsonObject("jump_target_addr") {
                    put("type", "string")
                    put("description", "final jump destination address (default 127.0.0.1; v0.5.0)")
                }
                putJsonObject("jump_target_port") {
                    put("type", "integer")
                    put("description", "final jump destination port (required if jumps set; v0.5.0)")
                }
                putJsonObject("jump_proto") {
                    put("type", "string")
                    put("description", "final jump leg protocol: tcp (default) or udp (v0.5.0)")
                }
            },
            required = listOf("server_addr", "server_port", "psk"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val (egress, egressArg) = parseEgress(a)
            val cipher = when (a["cipher"]?.jsonPrimitive?.content?.lowercase()) {
                "chacha", "chachapoly" -> 1
                else -> 0
            }
            val rlistenPort = a["rlisten_port"]?.jsonPrimitive?.content?.toInt() ?: 0
            val rtargetPort = a["rtarget_port"]?.jsonPrimitive?.content?.toInt() ?: 0
            val rproto = when (a["rproto"]?.jsonPrimitive?.content?.lowercase()?.trim()) {
                "udp" -> 1
                "both", "tcp+udp", "dual" -> 2
                else -> 0
            }
            // Jumps: accept the hop list as EITHER a JSON array (under `jumps`
            // or `jump_hops`) OR a comma-separated string (under `jump_hops`).
            // Each entry is "addr:port". An array element is read via jsonArray
            // (iterate + .content); a string is split on ",". Empty array and
            // absent both yield an empty list.
            fun hopsFrom(el: kotlinx.serialization.json.JsonElement?): List<String>? = when (el) {
                null -> null
                is kotlinx.serialization.json.JsonArray ->
                    el.map { it.jsonPrimitive.content.trim() }.filter { it.isNotEmpty() }
                else ->
                    el.jsonPrimitive.content.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
            val jumpHops: List<String> =
                hopsFrom(a["jumps"]) ?: hopsFrom(a["jump_hops"]) ?: emptyList()
            val jumpTargetPort = a["jump_target_port"]?.jsonPrimitive?.content?.toInt() ?: 0
            val jumpProto = when (a["jump_proto"]?.jsonPrimitive?.content?.lowercase()?.trim()) {
                "udp" -> 1
                else -> 0
            }
            val route = engine.addTunnel(
                serverAddr = a["server_addr"]!!.jsonPrimitive.content,
                serverPort = a["server_port"]!!.jsonPrimitive.content.toInt(),
                targetAddr = a["target_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                targetPort = a["target_port"]?.jsonPrimitive?.content?.toInt() ?: 5555,
                pskHex = a["psk"]!!.jsonPrimitive.content.trim(),
                cipher = cipher,
                egress = egress,
                egressArg = egressArg,
                revListenAddr = a["rlisten_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                revListenPort = rlistenPort,
                revTargetAddr = a["rtarget_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                revTargetPort = rtargetPort,
                revProto = rproto,
                jumpHops = jumpHops,
                jumpTargetAddr = a["jump_target_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                jumpTargetPort = jumpTargetPort,
                jumpProto = jumpProto,
            )
            ok("added tunnel ${route.id}: ${route.summary()}")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- add_peer ---
    server.addTool(
        name = "add_peer",
        description = "Add a relay PEER (v0.5.0): a single-port peek-demux tunnel " +
            "server (Noise NoisePSK_NN) that accepts encrypted carriers on " +
            "listen_port and either relays an OPENJ multi-hop stream to the next " +
            "hop or connects the terminal target when no hops remain. Peers form " +
            "the intermediate/terminal nodes of a jump chain whose edge is an " +
            "add_tunnel with jumps set; every node in the chain shares one psk/" +
            "cipher. WARNING: a peer forwards to arbitrary next hops / terminal " +
            "targets, so it REQUIRES the server to have been started with " +
            "PORTMUX_ALLOW_ANY_TARGET=1 (otherwise it is rejected). The optional " +
            "egress pins the peer's onward leg to a local source IP (src=) or " +
            "interface (dev=).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("listen_addr") {
                    put("type", "string")
                    put("description", "listen address for encrypted carriers (default 127.0.0.1)")
                }
                putJsonObject("listen_port") {
                    put("type", "integer"); put("description", "local port to accept encrypted carriers on")
                }
                putJsonObject("psk") {
                    put("type", "string")
                    put("description", "pre-shared key, 64 hex chars (32 bytes); shared by the whole chain")
                }
                putJsonObject("cipher") {
                    put("type", "string"); put("description", "aesgcm (default) | chacha")
                }
                putEgressProps(this)
            },
            required = listOf("listen_port", "psk"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val (egress, egressArg) = parseEgress(a)
            val cipher = when (a["cipher"]?.jsonPrimitive?.content?.lowercase()) {
                "chacha", "chachapoly" -> 1
                else -> 0
            }
            val route = engine.addPeer(
                listenAddr = a["listen_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                listenPort = a["listen_port"]!!.jsonPrimitive.content.toInt(),
                pskHex = a["psk"]!!.jsonPrimitive.content.trim(),
                cipher = cipher,
                egress = egress,
                egressArg = egressArg,
            )
            ok("added peer ${route.id}: ${route.summary()}")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- add_tunnel_server ---
    server.addTool(
        name = "add_tunnel_server",
        description = "Add an ENCRYPTED TUNNEL SERVER (Noise NoisePSK_NN responder; " +
            "AES-256-GCM or ChaCha20-Poly1305). Accepts encrypted clients on " +
            "listen_addr:listen_port and bridges each accepted client to a LOCAL " +
            "plaintext service bridge_addr:bridge_port. This is the counterpart to " +
            "add_tunnel (the client): in a simple two-host forward loop the client " +
            "dials this server and this server bridges to its local target. The PSK " +
            "(64 hex chars) and cipher MUST match the client. WARNING: bridging to a " +
            "non-loopback target REQUIRES the server to have been started with " +
            "PORTMUX_ALLOW_ANY_TARGET=1. The server role bridges to a LOCAL target " +
            "and takes no egress of its own.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("listen_addr") {
                    put("type", "string")
                    put("description", "address to accept encrypted clients on (default 0.0.0.0)")
                }
                putJsonObject("listen_port") {
                    put("type", "integer"); put("description", "port to accept encrypted clients on")
                }
                putJsonObject("bridge_addr") {
                    put("type", "string")
                    put("description", "local plaintext service address to bridge to (e.g. 127.0.0.1)")
                }
                putJsonObject("bridge_port") {
                    put("type", "integer"); put("description", "local plaintext service port to bridge to")
                }
                putJsonObject("psk") {
                    put("type", "string")
                    put("description", "pre-shared key, 64 hex chars (32 bytes); must match the client")
                }
                putJsonObject("cipher") {
                    put("type", "string"); put("description", "aesgcm (default) | chacha")
                }
                putEgressProps(this)
            },
            required = listOf("listen_port", "bridge_addr", "bridge_port", "psk"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val (egress, egressArg) = parseEgress(a)
            val cipher = when (a["cipher"]?.jsonPrimitive?.content?.lowercase()) {
                "chacha", "chachapoly" -> 1
                else -> 0
            }
            val route = engine.addTunnelServer(
                listenAddr = a["listen_addr"]?.jsonPrimitive?.content ?: "0.0.0.0",
                listenPort = a["listen_port"]!!.jsonPrimitive.content.toInt(),
                bridgeAddr = a["bridge_addr"]!!.jsonPrimitive.content,
                bridgePort = a["bridge_port"]!!.jsonPrimitive.content.toInt(),
                pskHex = a["psk"]!!.jsonPrimitive.content.trim(),
                cipher = cipher,
                egress = egress,
                egressArg = egressArg,
            )
            ok("added tunnel-server ${route.id}: ${route.summary()}")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- remove_route ---
    server.addTool(
        name = "remove_route",
        description = "Remove a route by its id.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") { put("type", "string"); put("description", "route id") }
            },
            required = listOf("id"),
        ),
    ) { request ->
        try {
            val id = request.arguments?.get("id")?.jsonPrimitive?.content ?: error("missing id")
            if (engine.removeRoute(id)) ok("removed $id") else err("no such route: $id")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- list_routes ---
    server.addTool(
        name = "list_routes",
        description = "List all configured routes.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        val routes = engine.listRoutes()
        if (routes.isEmpty()) ok("(no routes)")
        else ok(routes.joinToString("\n") { "${it.id}  ${it.summary()}" })
    }

    // --- status ---
    server.addTool(
        name = "status",
        description = "Show whether forwarding is running, how many routes are " +
            "configured, and whether PORTMUX_ALLOW_ANY_TARGET is set (required " +
            "for non-loopback targets and for add_socks / add_tunnel / add_peer).",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        val running = engine.isRunning()
        val allowAny = System.getenv("PORTMUX_ALLOW_ANY_TARGET") == "1"
        ok(
            "${if (running) "running" else "stopped"}; " +
                "${engine.listRoutes().size} route(s); allow_any_target=$allowAny",
        )
    }

    // --- start ---
    server.addTool(
        name = "start",
        description = "Start forwarding all configured routes.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        try {
            engine.start(); ok("started")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- stop ---
    server.addTool(
        name = "stop",
        description = "Stop forwarding.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        engine.stop(); ok("stopped")
    }

    return server
}
