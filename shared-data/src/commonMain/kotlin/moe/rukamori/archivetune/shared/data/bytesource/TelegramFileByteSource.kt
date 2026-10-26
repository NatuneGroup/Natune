/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.bytesource

import kotlin.math.min
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef

private const val TELEGRAM_PROVIDER = "telegram"

/**
 * The authorization to read one Telegram file through TDLib. It is deliberately not a [ByteSourceLease]. A lease names
 * the http(s) origins that every read must stay inside, and a TDLib file has no network origin. Keeping the two types
 * apart means a local Telegram file can never be mistaken for a network grant, and no cookie or header policy applies.
 */
data class TelegramFileLease(
    /**
     * The signed-in Telegram account that admitted this read. It is never null, because a Telegram file is readable
     * only through an authenticated session, so no anonymous read can be expressed here.
     */
    val account: ByteSourceAccount,
    /** A stable identity for host caches. It carries no TDLib file id, because that id changes with the database. */
    val cacheKey: String,
    /** Null: a TDLib file has no signed expiry, so it is bounded by the Telegram session rather than a timestamp. */
    val expiresAtEpochMs: Long?,
    /** The file length when TDLib knows it. A null length never blocks reading. */
    val lengthBytes: Long?,
) {
    init {
        require(account.provider == TELEGRAM_PROVIDER) { "a Telegram file lease requires the telegram provider" }
        require(account.accountId != null) { "a Telegram file lease names the signed-in account" }
        require(cacheKey.isNotBlank()) { "a Telegram file lease needs a cache key" }
        require(lengthBytes == null || lengthBytes >= 0) { "lengthBytes must be null or non-negative" }
    }
}

/**
 * A seekable Telegram file. It is the non-HTTP counterpart of [RandomAccessByteSource] and keeps the
 * same read contract, so a host can serve both through one engine-facing adapter that branches on the
 * lease type.
 */
interface TelegramRandomAccessSource {
    val lease: TelegramFileLease

    /**
     * Reads up to [length] bytes at [position] into [buffer] at [offset]. Returns a count above 0, 0 when no bytes are
     * available yet so the caller retries later, or [RandomAccessByteSource.END_OF_STREAM] at or beyond the end.
     */
    suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

/**
 * Reads a Telegram file that may still be downloading, through [port]. It applies the same policy as the lease-bound
 * [TelegramPartialFileByteSource], and it never blocks: a position outside the downloaded run triggers a seek.
 */
class TelegramLocalPartialFileSource(
    override val lease: TelegramFileLease,
    private val port: TelegramPartialFilePort,
) : TelegramRandomAccessSource {
    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = readTelegramPartialRun(port, position, buffer, offset, length)
}

/**
 * Passes Telegram partial-file access through only while the account that admitted the lease is still the accepted
 * account. Each state read, seek and byte read re-reads the same accepted-account store, so a sign-out or a replaced
 * account stops the next access even when the TDLib client is still open. A refusal throws [ByteSourceException]
 * rather than returning zero bytes, because zero means "retry later" to the reader and would wait forever.
 */
class AdmittedTelegramPartialFilePort(
    private val admission: AccountAdmission,
    private val accounts: AcceptedAccountReader,
    private val delegate: TelegramPartialFilePort,
) : TelegramPartialFilePort {
    init {
        if (admission.provider != TELEGRAM_PROVIDER) {
            throw ByteSourceException("AdmittedTelegramPartialFilePort requires a telegram admission")
        }
    }

    override suspend fun state(): PartialFileState {
        requireStillAccepted()
        return delegate.state()
    }

    override suspend fun startDownload(offset: Long) {
        requireStillAccepted()
        delegate.startDownload(offset)
    }

    override suspend fun readPart(
        offset: Long,
        count: Int,
    ): ByteArray {
        requireStillAccepted()
        return delegate.readPart(offset, count)
    }

    private suspend fun requireStillAccepted() {
        val current = accounts.acceptedAccount(admission.provider, admission.accountScope)
        if (current != admission.accepted) {
            throw ByteSourceException("The Telegram account is no longer accepted; prepare the track again")
        }
    }
}

/**
 * The lease for one track's file, minted from the admission captured before preparation. Its account is the account
 * that admitted the read, so a lease can never name an anonymous or invented account. The cache key uses the chat, the
 * message and the remote unique id, which identify the track across TDLib databases. The TDLib file id is left out on
 * purpose, and no HTTP origin or cookie is involved.
 */
fun telegramFileLease(
    track: TelegramTrackRef,
    lengthBytes: Long?,
    admission: AccountAdmission,
): TelegramFileLease {
    val identity =
        if (track.fileUniqueId.isEmpty()) {
            "${track.chatId}:${track.messageId}"
        } else {
            "${track.chatId}:${track.messageId}:${track.fileUniqueId}"
        }
    return TelegramFileLease(
        account = admission.toByteSourceAccount(),
        cacheKey = "telegram:$identity",
        expiresAtEpochMs = null,
        lengthBytes = lengthBytes,
    )
}

/**
 * The read policy for a partially downloaded Telegram file, shared by the lease-bound and the local source so that the
 * two cannot drift. It serves only bytes inside the downloaded run, or any byte once the download has completed.
 */
internal suspend fun readTelegramPartialRun(
    port: TelegramPartialFilePort,
    position: Long,
    buffer: ByteArray,
    offset: Int,
    length: Int,
): Int {
    // A negative position is a caller bug, so it is refused here rather than reaching the port as a bad offset.
    if (position < 0L) throw ByteSourceException("Telegram file read before the start of the stream")
    if (length == 0) return 0
    val state = port.state()
    val size = state.expectedSizeBytes?.takeIf { it > 0 }
    var wanted = length.toLong()
    if (size != null) {
        if (position >= size) return RandomAccessByteSource.END_OF_STREAM
        wanted = min(wanted, size - position)
    }
    if (state.isDownloadingCompleted) {
        return copyTelegramPart(port.readPart(position, wanted.toInt()), buffer, offset, length)
    }
    val runStart = state.downloadOffset
    val runEnd = runStart + state.downloadedPrefixBytes
    if (position >= runStart && position < runEnd) {
        val count = min(wanted, runEnd - position).toInt()
        return copyTelegramPart(port.readPart(position, count), buffer, offset, length)
    }
    // Seek only when nothing is downloading or the wanted position is behind the run. A position ahead of an active
    // download is reached by that download on its own, so restarting it there would only waste bandwidth.
    if (!state.isDownloadingActive || runStart > position) {
        port.startDownload(position)
    }
    return 0
}

private fun copyTelegramPart(
    data: ByteArray,
    buffer: ByteArray,
    offset: Int,
    length: Int,
): Int {
    if (data.isEmpty()) return 0
    val count = min(data.size, length)
    data.copyInto(buffer, offset, 0, count)
    return count
}
