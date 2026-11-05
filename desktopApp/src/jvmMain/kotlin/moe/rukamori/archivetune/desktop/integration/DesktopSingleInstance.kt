package moe.rukamori.archivetune.desktop.integration

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

private const val SOCKET_NAME = "instance.sock"
private const val MAX_URI_CHARS = 8192
// Unix socket paths are capped near 104 bytes (macOS) and 108 bytes (Linux); refusing earlier gives a clear error.
private const val MAX_SOCKET_PATH_BYTES = 100
private const val PEER_DEADLINE_MILLIS = 1500L
private const val RETRY_PAUSE_MILLIS = 50L
private const val JOIN_MILLIS = 2000L

/**
 * Single-instance ownership for the desktop app. The primary holds an exclusive lock on the per-user instance directory
 * and listens on a Unix-domain socket inside that directory. A second launch that cannot take the lock forwards its
 * deep link over the socket after a shared-secret handshake, then exits. No TCP port is opened, so no other user or
 * network peer can reach the channel, and a platform without Unix-domain sockets refuses to start rather than
 * falling back to a port.
 */
class DesktopSingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocketChannel,
    private val socketPath: Path,
    private val secret: String,
    private val receive: (String?) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val thread = Thread({
        while (!closed.get()) {
            val peer = try {
                server.accept()
            } catch (_: IOException) {
                // Closing the server ends the loop; any other accept failure is retried after a short pause.
                if (closed.get()) break
                Thread.sleep(RETRY_PAUSE_MILLIS)
                continue
            }
            try {
                serve(peer)
            } catch (error: Throwable) {
                // A peer handler failure (IOException inside serve is already swallowed, so this catches
                // unchecked failures) must never kill the loop: a dead thread bricks every later launch.
                System.err.println("instance peer handling failed: ${error.javaClass.simpleName}")
                runCatching { peer.close() }
            }
        }
    }, "archivetune-instance").apply { isDaemon = true; start() }

    // One peer at a time keeps deliveries in arrival order. The deadline stops a silent peer from holding the loop.
    private fun serve(peer: SocketChannel) {
        peer.use { connection ->
            try {
                connection.withDeadline(PEER_DEADLINE_MILLIS) {
                    val input = DataInputStream(Channels.newInputStream(connection))
                    val supplied = input.readUTF()
                    if (!MessageDigest.isEqual(secret.toByteArray(), supplied.toByteArray())) return@withDeadline
                    val raw = input.readUTF()
                    if (raw.length > MAX_URI_CHARS) return@withDeadline
                    receive(raw.ifEmpty { null })
                    DataOutputStream(Channels.newOutputStream(connection)).apply {
                        writeBoolean(true)
                        flush()
                    }
                }
            } catch (_: Exception) {
                // A disconnected or untrusted local peer must not stop the receiver.
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        try {
            thread.join(JOIN_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            // The socket file goes while the lock is still held, so the next primary never meets a stale socket.
            runCatching { Files.deleteIfExists(socketPath) }
        } finally {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
        // Keep the lock inode stable: deleting it allows two primaries after a close/acquire race.
    }

    companion object {
        /** Null means successfully forwarded. A forwarding failure throws, never starts another player. */
        fun acquire(directory: Path, uri: String?, receive: (String?) -> Unit): DesktopSingleInstance? {
            require(uri == null || uri.length <= MAX_URI_CHARS)
            require(!Files.isSymbolicLink(directory))
            Files.createDirectories(directory)
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            }
            val lockPath = directory.resolve("instance.lock")
            require(!Files.isSymbolicLink(lockPath))
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            val endpoint = directory.resolve("instance.endpoint")
            val socketPath = directory.resolve(SOCKET_NAME)
            if (lock == null) {
                channel.close()
                val parts = Files.readString(endpoint).split("\n")
                require(parts.size == 2 && parts[0] == SOCKET_NAME) { "Single-instance endpoint is not the approved socket" }
                forward(socketPath, parts[1], uri.orEmpty())
                return null
            }
            var server: ServerSocketChannel? = null
            var lockOwned = true
            try {
                require(socketPath.toString().toByteArray().size <= MAX_SOCKET_PATH_BYTES) {
                    "Instance directory is too long for a Unix-domain socket"
                }
                require(!Files.isSymbolicLink(endpoint))
                // The lock proves no live primary owns this directory, so an existing socket file can only be stale.
                Files.deleteIfExists(socketPath)
                val listener = openListener(socketPath)
                server = listener
                val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                Files.writeString(endpoint, "$SOCKET_NAME\n$secret", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                val instance = DesktopSingleInstance(channel, lock, listener, socketPath, secret, receive)
                lockOwned = false
                if (uri != null) {
                    try {
                        receive(uri)
                    } catch (failure: Throwable) {
                        // An owner that failed its first delivery never reaches a window, so a later launch
                        // must not be forwarded to it. close() releases the lock, socket and receiver thread.
                        instance.close()
                        throw failure
                    }
                }
                return instance
            } catch (error: Throwable) {
                runCatching { server?.close() }
                // The lock is still ours here, so a socket file left behind is stale. Removing it keeps the instance
                // directory down to the lock and endpoint files after a failed start.
                runCatching { Files.deleteIfExists(socketPath) }
                if (lockOwned) runCatching { lock.release() }
                runCatching { channel.close() }
                throw error
            }
        }

        private fun openListener(socketPath: Path): ServerSocketChannel {
            val listener = try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            } catch (error: UnsupportedOperationException) {
                // A TCP fallback would open a port other local users could reach, so the launch fails instead.
                throw IllegalStateException("Unix-domain sockets are unavailable; single instance refuses to start", error)
            }
            try {
                listener.bind(UnixDomainSocketAddress.of(socketPath))
                if (Files.getFileStore(socketPath).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rwx------"))
                }
            } catch (error: Exception) {
                // A failed bind must not leave an open channel behind, because the caller has no handle to close it.
                listener.close()
                throw error
            }
            return listener
        }

        private fun forward(socketPath: Path, secret: String, uri: String) {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { peer ->
                peer.withDeadline(PEER_DEADLINE_MILLIS) {
                    peer.connect(UnixDomainSocketAddress.of(socketPath))
                    val output = DataOutputStream(Channels.newOutputStream(peer))
                    output.writeUTF(secret)
                    output.writeUTF(uri)
                    output.flush()
                    check(DataInputStream(Channels.newInputStream(peer)).readBoolean()) { "Primary instance rejected delivery" }
                }
            }
        }
    }
}

/**
 * Closes the channel if [block] is still running after [millis]. Closing unblocks the read, write or connect that the
 * block waits in, so a silent or wedged local peer cannot hold the receiver or a launching process indefinitely.
 */
private inline fun SocketChannel.withDeadline(millis: Long, block: () -> Unit) {
    val channel = this
    val watchdog = Thread.ofVirtual().name("archivetune-instance-deadline").start {
        try {
            Thread.sleep(millis)
            channel.close()
        } catch (_: InterruptedException) {
            // The block finished first, so there is nothing left to bound.
        }
    }
    try {
        block()
    } finally {
        watchdog.interrupt()
    }
}
