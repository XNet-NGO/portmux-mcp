package org.xnet.portmuxmcp

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.thread

/**
 * [PortmuxEngine] that embeds the portmux engine in-process via JNI
 * (libportmuxjni, bundled in the JAR). No separate portmux install.
 *
 * portmux fixes routes at run() and its scheduler is thread-local, so a change
 * while running tears down the native instance and re-runs on a fresh worker
 * thread with the updated route set.
 *
 * @param allowNonLoopbackTargets off by default (loopback-only), same safety
 *   gate as the CLI backend.
 */
class NativePortmuxEngine(
    private val allowNonLoopbackTargets: Boolean =
        System.getenv("PORTMUX_ALLOW_ANY_TARGET") == "1",
) : PortmuxEngine {

    private val lock = ReentrantLock()
    private val routes = LinkedHashMap<String, Route>()
    private var handle: Long = 0L
    private var worker: Thread? = null

    init {
        NativeLoader.load()
    }

    override fun addRoute(
        proto: Proto,
        listenAddr: String,
        listenPort: Int,
        targetAddr: String,
        targetPort: Int,
    ): Route = lock.withLock {
        require(listenPort in 1..65535) { "listen port $listenPort out of range" }
        require(targetPort in 1..65535) { "target port $targetPort out of range" }
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
        if (worker != null) respawn()
        route
    }

    override fun removeRoute(id: String): Boolean = lock.withLock {
        val removed = routes.remove(id) != null
        if (removed && worker != null) {
            if (routes.isEmpty()) stopLocked() else respawn()
        }
        removed
    }

    override fun listRoutes(): List<Route> = lock.withLock { routes.values.toList() }

    override fun isRunning(): Boolean = lock.withLock { worker?.isAlive == true }

    override fun start() {
        lock.withLock {
            if (worker?.isAlive == true) return
            if (routes.isEmpty()) throw IllegalStateException("no routes to start")
            respawn()
        }
    }

    override fun stop() {
        lock.withLock { stopLocked() }
    }

    // --- internals (lock held) ---

    private fun respawn() {
        stopLocked()
        val h = nativeNew()
        if (h == 0L) throw IllegalStateException("portmux_new failed")
        handle = h
        routes.values.forEach { r ->
            val proto = if (r.proto == Proto.TCP) 0 else 1
            val rc = nativeAddRoute(
                h, proto, r.listenAddr, r.listenPort,
                r.targetAddr, r.targetPort, 0, "",
            )
            if (rc != 0) {
                nativeDestroy(h); handle = 0L
                throw IllegalStateException("add_route failed for ${r.summary()}")
            }
        }
        val t = thread(name = "portmux-run", isDaemon = true) {
            nativeRun(h) // blocks until stop
        }
        worker = t
    }

    private fun stopLocked() {
        val h = handle
        val t = worker
        if (h != 0L) {
            nativeStop(h)
            t?.join(3000)
            nativeDestroy(h)
            handle = 0L
        }
        worker = null
    }

    private fun isLoopback(addr: String): Boolean =
        addr.isBlank() || addr == "127.0.0.1" || addr == "::1" || addr == "localhost"

    // JNI instance methods (symbols: Java_org_xnet_portmuxmcp_NativePortmuxEngine_*)
    private external fun nativeNew(): Long
    private external fun nativeAddRoute(
        handle: Long, proto: Int,
        listenAddr: String, listenPort: Int,
        targetAddr: String, targetPort: Int,
        egressKind: Int, egressArg: String,
    ): Int
    private external fun nativeRun(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
