package moe.rukamori.archivetune.desktop.potoken

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The minimum of a child process the sidecar supervisor needs; a fake implements this in tests. */
internal interface BgutilProcessHandle {
    val isAlive: Boolean

    /** Graceful stop first, then a bounded forced stop, so app exit never leaves the helper behind. */
    fun destroy()
}

/** Injected so a test can observe the command and drive a fake process instead of spawning a real binary. */
internal fun interface BgutilProcessLauncher {
    fun launch(command: List<String>, workingDirectory: Path?, logFile: Path?): BgutilProcessHandle
}

/**
 * Starts the helper with its stdout and stderr appended to [logFile] and stdin closed. The command is always the
 * verified binary plus `server --host 127.0.0.1 --port <free port>`; nothing from user input reaches it.
 */
internal class ProcessBuilderBgutilLauncher : BgutilProcessLauncher {
    override fun launch(command: List<String>, workingDirectory: Path?, logFile: Path?): BgutilProcessHandle {
        val builder = ProcessBuilder(command)
        workingDirectory?.let {
            builder.directory(it.toFile())
            // Keep the helper's own minter cache beside the downloaded binary instead of in the user's home.
            builder.environment()["CACHE_DIR"] = it.resolve("cache").toString()
        }
        builder.environment()["RUST_LOG"] = "warn"
        builder.redirectErrorStream(true)
        if (logFile != null) {
            logFile.parent?.let { Files.createDirectories(it) }
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        }
        val process = builder.start()
        try {
            process.outputStream.close()
        } catch (_: Exception) {
        }
        // A normal app exit stops the helper through the session owner; this hook also covers a JVM that is
        // terminated before the graph's close runs, so no orphan keeps a loopback port open.
        val shutdownHook =
            Thread(
                {
                    process.destroy()
                    if (process.isAlive) process.destroyForcibly()
                },
                "natune-potoken-provider-stop",
            )
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
        } catch (_: Exception) {
        }
        return object : BgutilProcessHandle {
            override val isAlive: Boolean get() = process.isAlive

            override fun destroy() {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook)
                } catch (_: Exception) {
                }
                process.destroy()
                try {
                    if (!process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    process.destroyForcibly()
                }
            }
        }
    }

    private companion object {
        const val PROCESS_STOP_TIMEOUT_SECONDS = 2L
    }
}
