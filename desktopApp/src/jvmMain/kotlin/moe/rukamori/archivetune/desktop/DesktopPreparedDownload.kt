package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.*
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.desktop.playback.HostPreparedPlaintext
import moe.rukamori.archivetune.desktop.playback.NativeStreamLease
import moe.rukamori.archivetune.shared.data.stream.NativeRouteOutcome
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.toPlaybackLocator
import moe.rukamori.archivetune.shared.domain.source.toPlaybackLocator

/** Reads the same bounded, admitted plaintext that playback decodes; never commits provider ciphertext. */
internal suspend fun writeDesktopPreparedDownload(port: DesktopPlaybackPort, audio: PreparedAudio, target: Path): Long {
    val lease = when (audio) {
        is PreparedAudio.DeezerEncrypted -> NativeStreamLease.forDeezerTrack(audio.toPlaybackLocator())
        is PreparedAudio.Progressive -> {
            if (audio.stream.accountScope == null) NativeStreamLease.forRemote(audio.stream.toPlaybackLocator())
            else (port.prepareNative(audio) as? NativeRouteOutcome.Ready)?.handle?.let { (it as? HostPreparedPlaintext)?.claim() }
        }
        else -> (port.prepareNative(audio) as? NativeRouteOutcome.Ready)?.handle?.let { (it as? HostPreparedPlaintext)?.claim() }
    } ?: error("The source could not prepare downloadable plaintext")
    return coroutineScope {
        // Cancellation interrupts a blocking byte read before the owned transport is closed.
        val interrupt = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { lease.interrupt() }
        }
        try {
            withContext(Dispatchers.IO) {
                val sizing = LongArray(2)
                check(lease.open(sizing) == 0) { "The download stream could not be opened" }
                val maxBytes = 2L * 1024 * 1024 * 1024
                require(sizing[0] <= maxBytes) { "Download exceeds size limit" }
                var bytes = 0L
                var lastProgress = System.nanoTime()
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = lease.read(buffer, buffer.size)
                        if (count == 0) break
                        if (count == -1) {
                            check(System.nanoTime() - lastProgress < 30_000_000_000L) { "Download stream stalled" }
                            delay(5)
                            continue
                        }
                        check(count > 0) { "The download stream was interrupted or refused" }
                        bytes += count
                        require(bytes <= maxBytes) { "Download exceeds size limit" }
                        output.write(buffer, 0, count)
                        lastProgress = System.nanoTime()
                    }
                }
                check(bytes > 0 && (sizing[0] < 0 || bytes == sizing[0])) { "Incomplete plaintext download" }
                bytes
            }
        } finally {
            interrupt.cancelAndJoin()
            lease.close()
        }
    }
}
