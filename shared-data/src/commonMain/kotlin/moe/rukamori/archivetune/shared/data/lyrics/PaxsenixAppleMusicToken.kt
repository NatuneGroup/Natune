package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Supplies the Apple Music web player bearer that the Paxsenix Apple Music variant sends to
 * amp-api. Port of the app token helper, which installed a canvas-scraped bearer into the Paxsenix
 * module, and of the canvas refresh rules that produced it.
 *
 * Rules, from the Android code:
 *  - A user-supplied bearer wins while its expiry is unreadable or still in the future. Once it has
 *    expired, the scraped one is used instead.
 *  - A held bearer is reused until it is within 24 hours of expiry.
 *  - A scrape is throttled to one per minute, so an unreachable Apple does not cost a request per
 *    lookup. While throttled, a bearer that is still valid is served, and otherwise nothing is.
 *  - A scrape that yields no usable bearer yields null, and callers report that rather than
 *    sending a request that can only be rejected.
 *
 * Concurrency: callers queue on one lock. A caller that waited behind a scrape reads its result
 * instead of scraping again, so concurrent lookups share one refresh.
 *
 * The bearer is held in memory only and is never persisted. It is short-lived, Apple rotates it,
 * and a stale copy on disk would look valid until every request began to fail.
 *
 * [userDevBearer] stands in for the Android settings value. [nowMillis] exists so expiry and
 * throttle windows can be tested without a real clock.
 */
class PaxsenixAppleMusicToken(
    private val client: HttpClient,
    private val userDevBearer: () -> String? = { null },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** One immutable pair, so a reader never combines a new value with an old expiry. */
    private class Held(
        val bearerValue: String,
        val expAtSec: Long,
    )

    @Volatile
    private var held: Held? = null

    // Touched only under [refreshLock].
    private var lastScrapeAtMs: Long = 0L
    private val refreshLock = Mutex()

    /**
     * Returns a usable bearer, scraping one when none is held or the held one is within the refresh
     * margin, or null when none can be obtained. Cancellation propagates.
     */
    suspend fun ensureFresh(): String? {
        devBearerIfUsable()?.let { return it }
        freshHeld()?.let { return it }

        return refreshLock.withLock {
            // Queued callers read the outcome of the scrape they waited for.
            freshHeld()?.let { return@withLock it }
            val sinceLastMs = nowMillis() - lastScrapeAtMs
            if (sinceLastMs in 1..SCRAPE_THROTTLE_MS) return@withLock heldIfValid(nowSec())
            scrapeAndStore() ?: heldIfValid(nowSec())
        }
    }

    /**
     * Forced refresh after Apple rejected [rejected] with a 401. A bearer that a concurrent caller has
     * already replaced is taken as it stands, so one rejection does not trigger a second scrape.
     */
    suspend fun refreshAfterRejection(rejected: String?): String? =
        refreshLock.withLock {
            val current = held
            if (current != null && current.bearerValue != rejected && current.expAtSec > nowSec()) {
                return@withLock current.bearerValue
            }
            scrapeAndStore()
        }

    /** The user bearer while it is usable: unreadable expiry counts as usable, an expired one does not. */
    private fun devBearerIfUsable(): String? {
        val candidate = userDevBearer()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expSec = AppleWebPlayJwt.expSec(candidate)
        return candidate.takeIf { expSec == 0L || expSec > nowSec() }
    }

    private fun freshHeld(): String? {
        val current = held ?: return null
        return current.bearerValue.takeIf { current.expAtSec - nowSec() >= REFRESH_MARGIN_SEC }
    }

    private fun heldIfValid(nowSec: Long): String? = held?.takeIf { it.expAtSec > nowSec }?.bearerValue

    /** Runs only under [refreshLock], so two scrapes never overlap and the throttle timestamp stays consistent. */
    private suspend fun scrapeAndStore(): String? {
        lastScrapeAtMs = nowMillis()
        val fresh = scrapeBearer() ?: return null
        held = Held(bearerValue = fresh, expAtSec = AppleWebPlayJwt.expSec(fresh))
        return fresh
    }

    /**
     * Reads the landing page, then its bundles, until a usable bearer appears. Transport failures
     * read as no bearer, except cancellation, which must unwind the caller.
     */
    private suspend fun scrapeBearer(): String? =
        try {
            scrapeFromWebPlayer()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            null
        }

    private suspend fun scrapeFromWebPlayer(): String? {
        val html = LyricsHttp.getWithStatus(client, APPLE_MUSIC_WEB_HOME, WEB_HEADERS, defaultUserAgent = false).body
            ?: return null
        val now = nowSec()
        AppleWebPlayJwt.select(html, now)?.let { return it }
        for (bundleUrl in AppleWebPlayJwt.bundleUrls(html)) {
            val script =
                LyricsHttp.getWithStatus(client, bundleUrl, WEB_HEADERS, defaultUserAgent = false).body
                    ?: continue
            AppleWebPlayJwt.select(script, now)?.let { return it }
        }
        return null
    }

    private fun nowSec(): Long = nowMillis() / 1000L

    private companion object {
        const val APPLE_MUSIC_WEB_HOME = "https://music.apple.com/"
        const val APPLE_MUSIC_WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

        // Same Accept headers the canvas web-player client sent; the landing page is served as HTML.
        val WEB_HEADERS: Map<String, String> =
            mapOf(
                HttpHeaders.UserAgent to APPLE_MUSIC_WEB_UA,
                HttpHeaders.Accept to "text/html,application/xhtml+xml,application/javascript,*/*;q=0.8",
                HttpHeaders.AcceptLanguage to "en-US,en;q=0.9",
            )

        // Refresh once the held bearer has under a day left.
        const val REFRESH_MARGIN_SEC = 60L * 60L * 24L

        // Minimum gap between scrapes. A lookup inside the gap is skipped, not sent with a dead bearer.
        const val SCRAPE_THROTTLE_MS = 60_000L
    }
}
