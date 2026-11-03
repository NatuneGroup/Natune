package moe.rukamori.archivetune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.Call

/**
 * First-attempt timeouts for streaming reads. The shared media client stays at its generous
 * timeouts because resolve calls share it — so the fast clock lives on a second client and a
 * timeout on that first attempt escalates once into the slow path, which is exactly the
 * behaviour a slow but honest network already had.
 */
object FastFailReadPolicy {
    /** First-attempt socket timeouts: connect 4 s, read 3 s (Apple's 4 s/2 s, relaxed one tick). */
    const val FAST_CONNECT_TIMEOUT_MS = 4_000L
    const val FAST_READ_TIMEOUT_MS = 3_000L

    /** True when [error] is a timeout-class failure that earns one slow-path attempt. */
    fun shouldEscalateToSlowPath(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SocketTimeoutException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * The spec that re-opens a stream [bytesServed] into [dataSpec] at the right byte, or null
     * when the declared range is already fully served — Media3 rejects a zero-length open, so
     * the caller reports end-of-input instead of opening.
     */
    fun resumeDataSpec(dataSpec: DataSpec, bytesServed: Long): DataSpec? {
        val range = resumeRange(dataSpec.position, dataSpec.length, bytesServed) ?: return null
        return dataSpec.buildUpon().setPosition(range.position).setLength(range.length).build()
    }

    /**
     * The position/length of the resumed open, or null when the declared range is already fully
     * served. Kept free of Media3 types so the policy pins under ordinary unit tests — a
     * DataSpec needs android.net.Uri, which local tests cannot build.
     */
    fun resumeRange(position: Long, length: Long, bytesServed: Long): ResumeRange? {
        if (length != C.LENGTH_UNSET.toLong() && bytesServed >= length) return null
        return ResumeRange(
            position + bytesServed,
            if (length == C.LENGTH_UNSET.toLong()) length else length - bytesServed,
        )
    }

    /** The bounded remainder of a resumed stream open. */
    data class ResumeRange(val position: Long, val length: Long)
}

/**
 * A [DataSource] decorator whose first attempt runs on a fast-clock client and escalates once,
 * on a timeout-class failure, to the shared slow client — reopening mid-stream at the byte the
 * read had already reached so a slow network is never failed outright.
 */
internal class FastFailHttpDataSource private constructor(
    private val fast: DataSource,
    private val slow: DataSource,
) : DataSource {
    private var delegate: DataSource = fast
    private var escalated = false
    private var openedSpec: DataSpec? = null
    private var bytesServed = 0L

    override fun open(dataSpec: DataSpec): Long {
        // Re-arm the fast attempt: a previous stream's escalation must not leak into this
        // open — close() between opens is not guaranteed on a loader retry.
        escalated = false
        delegate = fast
        openedSpec = dataSpec
        bytesServed = 0L
        return try {
            fast.open(dataSpec)
        } catch (e: IOException) {
            if (!FastFailReadPolicy.shouldEscalateToSlowPath(e)) throw e
            escalated = true
            delegate = slow
            slow.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        try {
            delegate.readCounted(buffer, offset, length)
        } catch (e: IOException) {
            escalateOrThrow(e)
        }

    private fun DataSource.readCounted(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = read(buffer, offset, length)
        if (read > 0) bytesServed += read
        return read
    }

    private fun escalateOrThrow(e: IOException): Int {
        val spec = openedSpec
        if (escalated || !FastFailReadPolicy.shouldEscalateToSlowPath(e) || spec == null) throw e
        // Declared range already fully served: the timeout hit at the end of the stream, so
        // the honest answer is end-of-input — a zero-length resume open is invalid anyway.
        val resumed = FastFailReadPolicy.resumeDataSpec(spec, bytesServed) ?: return C.RESULT_END_OF_INPUT
        escalated = true
        runCatching { fast.close() }
        delegate = slow
        slow.open(resumed)
        // No bytes this call: the loader retries the read against the slow stream.
        return 0
    }

    override fun getUri(): Uri? = delegate.uri

    override fun addTransferListener(transferListener: TransferListener) {
        fast.addTransferListener(transferListener)
        slow.addTransferListener(transferListener)
    }

    override fun close() {
        openedSpec = null
        bytesServed = 0L
        runCatching { fast.close() }
        runCatching { slow.close() }
        delegate = fast
    }

    /**
     * Builds a fast/slow [OkHttpDataSource] pair per stream: the fast client carries the short
     * timeouts, the shared client the generous ones resolves already ride.
     */
    class Factory(
        private val fastCallFactory: Call.Factory,
        private val slowCallFactory: Call.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            FastFailHttpDataSource(
                OkHttpDataSource.Factory(fastCallFactory).createDataSource(),
                OkHttpDataSource.Factory(slowCallFactory).createDataSource(),
            )
    }
}
