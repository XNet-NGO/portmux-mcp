package org.xnet.portmuxmcp

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [PortmuxEngine] backed by the portmux CLI binary.
 *
 * portmux fixes its routes at run() time, so this backend keeps the route set
 * in memory and (re)spawns the CLI process whenever routes change while
 * running. The CLI is invoked as:
 *
 *   portmux [-v] PROTO:LADDR:LPORT:TADDR:TPORT [ROUTE ...]
 *
 * @param binary path to the portmux executable (default "portmux" on PATH).
 * @param allowNonLoopbackTargets safety gate: when false (default), targets
 *   must be loopback. An MCP endpoint that can forward to arbitrary hosts is a
 *   network-pivot capability, so it is off by default.
 */
class CliPortmuxEngine(
    private val binary: String = System.getenv("PORTMUX_BIN") ?: "portmux",
    private val allowNonLoopbackTargets: Boolean =
        System.getenv("PORTMUX_ALLOW_ANY_TARGET") == "1",
) : PortmuxEngine {

    private val lock = ReentrantLock()
    private val routes = LinkedHashMap<String, Route>()
    private var process: Process? = null

    override fun addRoute(
        proto: Proto,
        listenAddr: String,
        listenPort: Int,
        targetAddr: String,
        targetPort: Int,
    ): Route = lock.withLock {
        validatePort(listenPort, "listen")
        validatePort(targetPort, "target")
        if (!allowNonLoopbackTargets && !isLoopback(targetAddr)) {
            throw IllegalArgumentException(
                "target '$targetAddr' is not loopback; set PORTMUX_ALLOW_ANY_TARGET=1 to allow non-loopback targets",
            )
        }
        val route = Route(
            id = UUID.randomUUID().toString().take(8),
            proto = proto,
            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
            listenPort = listenPort,
            targetAddr = targetAddr.ifBlank { "127.0.0.1" },
            targetPort = targetPort,
        )
        routes[route.id] = route
        if (process != null) respawn()
        route
    }

    override fun removeRoute(id: String): Boolean = lock.withLock {
        val removed = routes.remove(id) != null
        if (removed && process != null) {
            if (routes.isEmpty()) stopLocked() else respawn()
        }
        removed
    }

    override fun listRoutes(): List<Route> = lock.withLock { routes.values.toList() }

    override fun isRunning(): Boolean = lock.withLock { process?.isAlive == true }

    override fun start() {
        lock.withLock {
            if (process?.isAlive == true) return
            if (routes.isEmpty()) throw IllegalStateException("no routes to start")
            respawn()
        }
    }

    override fun stop() {
        lock.withLock { stopLocked() }
    }

    // --- internals (call with lock held) ---

    private fun respawn() {
        stopLocked()
        val args = buildList {
            add(binary)
            routes.values.forEach { r ->
                val p = if (r.proto == Proto.TCP) "tcp" else "udp"
                add("$p:${r.listenAddr}:${r.listenPort}:${r.targetAddr}:${r.targetPort}")
            }
        }
        process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun stopLocked() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
    }

    private fun validatePort(port: Int, which: String) {
        require(port in 1..65535) { "$which port $port out of range (1-65535)" }
    }

    private fun isLoopback(addr: String): Boolean =
        addr.isBlank() || addr == "127.0.0.1" || addr == "::1" || addr == "localhost"
}
