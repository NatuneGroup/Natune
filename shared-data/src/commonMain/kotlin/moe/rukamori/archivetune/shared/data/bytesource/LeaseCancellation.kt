package moe.rukamori.archivetune.shared.data.bytesource

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/** Thrown by a read whose lease generation was cancelled. It is a cancellation, not a failure. */
class ByteSourceCancelledException : CancellationException("Byte source lease was cancelled")

/**
 * Generation counter that invalidates every outstanding [LeaseTicket] at once. Bumping the
 * generation is O(1) and needs no bookkeeping of individual reads.
 */
class LeaseCancellation {
    private val generation = MutableStateFlow(0L)

    fun currentGeneration(): Long = generation.value

    fun issueTicket(): LeaseTicket = LeaseTicket(this, generation.value)

    fun cancelAll() {
        generation.update { it + 1 }
    }
}

/** A snapshot of a [LeaseCancellation] generation; it stops being current once that generation is cancelled. */
class LeaseTicket internal constructor(
    private val owner: LeaseCancellation,
    val generation: Long,
) {
    val isCurrent: Boolean
        get() = owner.currentGeneration() == generation

    fun throwIfCancelled() {
        if (!isCurrent) throw ByteSourceCancelledException()
    }
}

/**
 * Wraps a source so that a read in flight during a cancellation is discarded. The check runs on
 * both sides of the delegate call, so bytes produced for a stale request never reach the caller.
 */
class GuardedByteSource(
    private val delegate: RandomAccessByteSource,
    private val ticket: LeaseTicket,
) : RandomAccessByteSource {
    override val lease: ByteSourceLease
        get() = delegate.lease

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        ticket.throwIfCancelled()
        // Read into scratch space so a stale delegate never writes into the caller's buffer.
        val scratch = ByteArray(length)
        val read = delegate.readAt(position, scratch, 0, length)
        ticket.throwIfCancelled()
        if (read > 0) scratch.copyInto(buffer, offset, 0, read)
        return read
    }
}
