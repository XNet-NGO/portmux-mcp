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
 */
fun buildPortmuxMcpServer(engine: PortmuxEngine): Server {
    val server = Server(
        Implementation(name = "portmux-mcp", version = "0.1.0"),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
    fun err(text: String) =
        CallToolResult(content = listOf(TextContent("error: $text")), isError = true)

    // --- add_route ---
    server.addTool(
        name = "add_route",
        description = "Add a port-forwarding route. proto is 'tcp' or 'udp'. " +
            "Targets must be loopback unless the server allows otherwise. " +
            "If forwarding is running, the route takes effect immediately.",
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
            val route = engine.addRoute(
                proto = proto,
                listenAddr = a["listen_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                listenPort = a["listen_port"]!!.jsonPrimitive.content.toInt(),
                targetAddr = a["target_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                targetPort = a["target_port"]!!.jsonPrimitive.content.toInt(),
            )
            ok("added route ${route.id}: ${route.summary()}")
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
        description = "Show whether forwarding is running and how many routes are configured.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        val running = engine.isRunning()
        ok("${if (running) "running" else "stopped"}; ${engine.listRoutes().size} route(s)")
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
