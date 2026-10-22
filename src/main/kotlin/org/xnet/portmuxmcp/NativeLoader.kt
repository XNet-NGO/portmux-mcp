package org.xnet.portmuxmcp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads the embedded portmux JNI library bundled in the JAR at
 * /native/<os>-<arch>/libportmuxjni.<ext>. The library is extracted to a temp
 * file and loaded with System.load, so no separate portmux install is needed.
 */
object NativeLoader {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val os = osName()
        val arch = archName()
        val ext = when (os) {
            "macos" -> "dylib"
            "windows" -> "dll"
            else -> "so"
        }
        val resource = "/native/$os-$arch/libportmuxjni.$ext"
        val stream = NativeLoader::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "embedded portmux JNI library not found at $resource " +
                    "(build it with native/build-jni.sh for this platform)",
            )
        val tmp = Files.createTempFile("libportmuxjni", ".$ext").toFile()
        tmp.deleteOnExit()
        stream.use { inp ->
            Files.copy(inp, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        System.load(tmp.absolutePath)
        loaded = true
    }

    fun isAvailable(): Boolean = try {
        load(); true
    } catch (_: Throwable) {
        false
    }

    private fun osName(): String {
        val o = System.getProperty("os.name").lowercase()
        return when {
            o.contains("mac") || o.contains("darwin") -> "macos"
            o.contains("win") -> "windows"
            else -> "linux"
        }
    }

    private fun archName(): String {
        val a = System.getProperty("os.arch").lowercase()
        return when (a) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> a
        }
    }

    // Helper so other classes can report the expected resource path.
    fun expectedResource(): String {
        val os = osName()
        val ext = when (os) { "macos" -> "dylib"; "windows" -> "dll"; else -> "so" }
        return "/native/$os-${archName()}/libportmuxjni.$ext"
    }
}
