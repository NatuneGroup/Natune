package moe.rukamori.archivetune.desktop.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio

/**
 * The resolved YouTube streams this process may hand out again, replacing the one `lastSuccessful` client hint the
 * desktop resolver used to keep.
 *
 * A googlevideo URL is bound to the video, the requested quality, the account it was minted for and the address that
 * asked for it, and it carries its own `expire`. So an entry is keyed on exactly those four things and is reused only
 * while the URL's authorization window still has [expiryMarginMs] left: the engine needs that margin to open the
 * source, probe the container and read its first chunks, and a URL handed out a second before it dies would fail the
 * load that asked for it. A stream whose provider reported no window is kept for [unknownExpiryTtlMs] only, because
 * nothing else bounds how long YouTube would still accept it.
 *
 * One load per key is in flight at a time: a second caller of the same key waits for the first answer instead of
 * spending another player request on the same video. The map is a bounded LRU, and [invalidate] drops a video's entry
 * when a resolution or a playback attempt was rejected, so a bad URL is never served twice.
 */
internal class DesktopResolvedStreamCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Reuse stops this long before the URL's own expiry, so the engine can still open and read it. */
    private val expiryMarginMs: Long = EXPIRY_MARGIN_MS,
    /** How long a stream without a reported window stays usable. */
    private val unknownExpiryTtlMs: Long = UNKNOWN_EXPIRY_TTL_MS,
) {
    /** Everything one resolution depends on. A value is only reused when all four match. */
    data class Key(
        val videoId: String,
        val quality: String,
        val session: String,
        val network: String,
    )

    private class Entry(val audio: PreparedAudio.Progressive, val usableUntilMs: Long)

    private val lock = Any()

    // Access-ordered, so the least recently used entry is the one the bound drops.
    private val entries = object : LinkedHashMap<Key, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > capacity
    }

    /**
     * One mutex per key being resolved. The map is cleared when it grows past [MAX_FLIGHTS] instead of tracked per
     * holder: a dropped mutex only lets two callers resolve the same key twice, which is a wasted request, not a
     * wrong answer.
     */
    private val flights = HashMap<Key, Mutex>()

    /** The cached stream for [key], or null when there is none or its window has closed. Expired entries are dropped. */
    fun get(key: Key): PreparedAudio.Progressive? {
        val now = nowMs()
        return synchronized(lock) {
            val entry = entries[key] ?: return null
            if (now >= entry.usableUntilMs) {
                entries.remove(key)
                return null
            }
            entry.audio
        }
    }

    /**
     * Keeps [audio] for [key] until its URL stops being safe to reuse. A stream whose window already closed is not
     * stored at all: storing it would only create an entry the next [get] deletes.
     */
    fun put(key: Key, audio: PreparedAudio.Progressive) {
        val now = nowMs()
        val usableUntil = usableUntilMs(audio, now)
        if (usableUntil <= now) return
        synchronized(lock) {
            entries[key] = Entry(audio, usableUntil)
        }
    }

    /** [get] when present, otherwise one [load] shared by every caller of the same key. Null when [load] answers null. */
    suspend fun resolve(key: Key, load: suspend () -> PreparedAudio.Progressive?): PreparedAudio.Progressive? {
        get(key)?.let { return it }
        val flight = flightFor(key)
        return flight.withLock {
            get(key) ?: load()?.also { put(key, it) }
        }
    }

    /** Drops every entry of [videoId]. Called when a resolution or a playback attempt for it was rejected. */
    fun invalidate(videoId: String) {
        synchronized(lock) {
            entries.keys.removeAll { it.videoId == videoId }
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** Entries currently held, for tests and diagnostics. */
    fun size(): Int = synchronized(lock) { entries.size }

    private fun flightFor(key: Key): Mutex = synchronized(lock) {
        if (flights.size > MAX_FLIGHTS) flights.clear()
        flights.getOrPut(key) { Mutex() }
    }

    /**
     * When [audio] may stop being reused. A reported window is honoured minus the margin the engine needs; without one
     * the entry is bounded by [unknownExpiryTtlMs] from now.
     */
    private fun usableUntilMs(audio: PreparedAudio.Progressive, nowMs: Long): Long {
        val expiresAt = audio.stream.expiresAtEpochMs ?: return nowMs + unknownExpiryTtlMs
        return expiresAt - expiryMarginMs
    }

    private companion object {
        const val DEFAULT_CAPACITY = 32
        const val MAX_FLIGHTS = 64

        /** Long enough to open the source and read the startup probe, short enough to never hand out a dying URL. */
        const val EXPIRY_MARGIN_MS = 60_000L

        /** A provider that reports no window still hands out an IP-bound URL; ten minutes keeps a queue run cached. */
        const val UNKNOWN_EXPIRY_TTL_MS = 10 * 60 * 1000L
    }
}
