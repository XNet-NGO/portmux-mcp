package org.xnet.portmuxmcp

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [PortmuxEngine] backed by the portmux CLI binary (the fallback backend used
 * only when the embedded JNI library is not bundled for the platform).
 *
 * portmux fixes its routes at run() time, so this backend keeps the route set
 * in memory and (re)spawns the CLI process whenever routes change while
 * running. Plain routes and SOCKS listeners are expressed as CLI route specs:
 *
 *   portmux [-v] PROTO:LADDR:LPORT:TADDR:TPORT[:EGRESS]  (route)
 *   portmux socks:LADDR:LPORT[:EGRESS]                   (SOCKS5 proxy)
 *     EGRESS = dev=IFNAME | src=IP
 *
 * Encrypted tunnels (add_tunnel / add_peer) are a distinct, single-instance CLI
 * subcommand that needs a PSK file and cannot be composed with other routes in
 * one invocation, so they are NOT supported by this multi-route CLI backend.
 * Use the embedded native backend (the default) for tunnels/peers.
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
        egress: EgressMode,
        egressArg: String,
    ): Route = lock.withLock {
        validatePort(listenPort, "listen")
        validatePort(targetPort, "target")
        val target = targetAddr.ifBlank { "127.0.0.1" }
        if (!allowNonLoopbackTargets && !isLoopback(target)) {
            throw IllegalArgumentException(
                "target '$target' is not loopback; set PORTMUX_ALLOW_ANY_TARGET=1 to allow non-loopback targets",
            )
        }
        validateEgress(egress, egressArg)
        val route = Route(
            id = UUID.randomUUID().toString().take(8),
            proto = proto,
            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
            listenPort = listenPort,
            targetAddr = target,
            targetPort = targetPort,
            egress = egress,
            egressArg = egressArg,
        )
        routes[route.id] = route
        if (process != null) respawn()
        route
    }

    override fun addSocks(
        listenAddr: String,
        listenPort: Int,
        egress: EgressMode,
        egressArg: String,
    ): Route = lock.withLock {
        validatePort(listenPort, "listen")
        if (!allowNonLoopbackTargets) {
            throw IllegalStateException(
                "add_socks creates an OPEN PROXY to arbitrary destinations; " +
                    "set PORTMUX_ALLOW_ANY_TARGET=1 to allow it",
            )
        }
        validateEgress(egress, egressArg)
        val route = Route(
            id = UUID.randomUUID().toString().take(8),
            proto = Proto.TCP,
            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
            listenPort = listenPort,
            targetAddr = "",
            targetPort = 0,
            isSocks = true,
            egress = egress,
            egressArg = egressArg,
        )
        routes[route.id] = route
        if (process != null) respawn()
        route
    }

    override fun addTunnel(
        serverAddr: String,
        serverPort: Int,
        targetAddr: String,
        targetPort: Int,
        pskHex: String,
        cipher: Int,
        egress: EgressMode,
        egressArg: String,
        revListenAddr: String,
        revListenPort: Int,
        revTargetAddr: String,
        revTargetPort: Int,
        revProto: Int,
        jumpHops: List<String>,
        jumpTargetAddr: String,
        jumpTargetPort: Int,
        jumpProto: Int,
    ): Route = throw UnsupportedOperationException(
        "add_tunnel is not supported by the CLI fallback backend; use the embedded native backend",
    )

    override fun addPeer(
        listenAddr: String,
        listenPort: Int,
        pskHex: String,
        cipher: Int,
        egress: EgressMode,
        egressArg: String,
    ): Route = throw UnsupportedOperationException(
        "add_peer is not supported by the CLI fallback backend; use the embedded native backend",
    )

    override fun addTunnelServer(
        listenAddr: String,
        listenPort: Int,
        bridgeAddr: String,
        bridgePort: Int,
        pskHex: String,
        cipher: Int,
        egress: EgressMode,
        egressArg: String,
    ): Route = throw UnsupportedOperationException(
        "add_tunnel_server is not supported by the CLI fallback backend; use the embedded native backend",
    )

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

    private fun egressSuffix(route: Route): String = when (route.egress) {
        EgressMode.NONE -> ""
        EgressMode.SOURCE -> ":src=${route.egressArg}"
        EgressMode.DEVICE -> ":dev=${route.egressArg}"
    }

    private fun respawn() {
        stopLocked()
        val args = buildList {
            add(binary)
            routes.values.forEach { r ->
                if (r.isSocks) {
                    add("socks:${r.listenAddr}:${r.listenPort}${egressSuffix(r)}")
                } else {
                    val p = if (r.proto == Proto.TCP) "tcp" else "udp"
                    add("$p:${r.listenAddr}:${r.listenPort}:${r.targetAddr}:${r.targetPort}${egressSuffix(r)}")
                }
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

    private fun validateEgress(egress: EgressMode, egressArg: String) {
        if (egress == EgressMode.SOURCE && egressArg.isBlank()) {
            throw IllegalArgumentException("SOURCE egress requires a source IP (egress_arg / src=)")
        }
        if (egress == EgressMode.DEVICE && egressArg.isBlank()) {
            throw IllegalArgumentException("DEVICE egress requires an interface name (egress_arg / dev=)")
        }
    }

    private fun isLoopback(addr: String): Boolean =
        addr.isBlank() || addr == "127.0.0.1" || addr == "::1" || addr == "localhost"
}
