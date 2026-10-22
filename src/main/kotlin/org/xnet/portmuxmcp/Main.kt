package org.xnet.portmuxmcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

/**
 * portmux-mcp: an MCP server exposing portmux port-forwarding control over the
 * Streamable HTTP transport, so any MCP client on any platform can connect.
 *
 *   portmux-mcp [PORT]     (default 3339)
 *
 * Binds 127.0.0.1 only, no auth (local use). The forwarding engine is shared
 * across MCP sessions so routes persist for the life of the process.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3339
    val host = "127.0.0.1"

    // Prefer the embedded native engine (portmux compiled into the JAR via
    // JNI). Fall back to the CLI backend only if the native library is not
    // bundled for this platform.
    val engine: PortmuxEngine = if (NativeLoader.isAvailable()) {
        println("engine: embedded native (JNI) backend")
        NativePortmuxEngine()
    } else {
        println("engine: portmux CLI backend (native lib not bundled for this platform; set PORTMUX_BIN)")
        CliPortmuxEngine()
    }

    println("portmux-mcp: Streamable HTTP MCP server on http://$host:$port/mcp")

    embeddedServer(CIO, host = host, port = port) {
        // No-auth Streamable HTTP. mcpStreamableHttp installs the JSON
        // ContentNegotiation (McpJson) itself, so we don't add our own. A
        // fresh MCP server is created per session, all sharing one engine.
        mcpStreamableHttp { buildPortmuxMcpServer(engine) }
    }.start(wait = true)
}
