/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.stream.dash

import kotlin.time.Clock
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.toByteSourceAccount
import moe.rukamori.archivetune.shared.data.stream.NativeCiphertextOpener
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.NativePreparation
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio

/**
 * Turns a TIDAL manifest into a plaintext view that a native engine reads through its lease. Both retained routes are
 * served here: the progressive FLAC remux and the full-file concatenation. No plaintext temp file is written.
 *
 * Segments are opened through the host's [NativeCiphertextOpener], which must return range-readable sources for the
 * segment URLs. TIDAL segment URLs are unauthenticated, so the account scope only reaches the opener for its generation
 * check. The lease covers only the segment origins, so the engine cannot reach another host through this stream.
 */
object TidalDashPreparation {
    /**
     * Prepares [prepared] for a native engine. [admission] is the TIDAL account admitted for this manifest, or null
     * for an anonymous one. The lease and every segment open carry exactly that account and its entry scope, so cookies
     * come only from the entry that admitted the stream. A manifest recorded under another scope is refused, and a
     * manifest that names no scope counts as anonymous. Returns [NativePreparation.Refused] when the manifest has no
     * usable segments, a segment cannot be opened or indexed, the FLAC structure is invalid, or a signed segment
     * authorization cannot be read. It is also refused when the authorization has already lapsed or lapses within a
     * minute. It never substitutes a progressive URL or a fresh default lifetime for a signature that is present. A
     * stalled transport throws `ByteSourceException`, which is not converted into a refusal.
     */
    suspend fun prepare(
        prepared: PreparedAudio.TidalManifest,
        opener: NativeCiphertextOpener,
        admission: AccountAdmission?,
        nowEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): NativePreparation {
        val accountScope = admission?.accountScope ?: ANONYMOUS_ACCOUNT_SCOPE
        if (admission != null && admission.provider != ContentProvider.TIDAL.id) {
            return NativePreparation.Refused("TIDAL DASH needs a TIDAL account admission or none")
        }
        if ((prepared.accountScope ?: ANONYMOUS_ACCOUNT_SCOPE) != accountScope) {
            return NativePreparation.Refused("TIDAL DASH manifest belongs to another account, prepare it again")
        }
        val manifest =
            TidalDashManifest.parse(prepared.manifestText)
                ?: return NativePreparation.Refused("TIDAL DASH manifest had no playable segments")
        val urls = manifest.segmentUrls
        val origins = urls.mapNotNull { StreamOrigin.parse(it) }
        if (origins.size != urls.size) {
            return NativePreparation.Refused("TIDAL DASH manifest listed a non-HTTP segment")
        }
        // The retained FLAC route needs the init segment plus at least one media segment; a lone BaseURL file cannot be remuxed.
        if (manifest.isFlac && urls.size < 2) {
            return NativePreparation.Refused("TIDAL FLAC DASH manifest did not list an init and media segments")
        }
        // Each segment contributes its own signature window and the lease takes the earliest, so no segment outlives
        // its authorization. A window that is present but unreadable, negative or beyond the signing limit is refused
        // rather than defaulted: a fresh lease would extend an authorization the URL no longer has.
        var expiresAtEpochMs = Long.MAX_VALUE
        for (url in urls) {
            val windowEndMs =
                expiryEpochMs(url, nowEpochMs)
                    ?: return NativePreparation.Refused("TIDAL DASH segment authorization is unreadable; re-prepare")
            expiresAtEpochMs = minOf(expiresAtEpochMs, windowEndMs)
        }
        // The window is never stretched to make a short signature usable. A lapsed or nearly lapsed authorization is
        // refused, so the caller prepares the track again from a fresh manifest.
        if (expiresAtEpochMs - nowEpochMs < MIN_USABLE_LIFETIME_MS) {
            return NativePreparation.Refused("TIDAL DASH authorization expired or too short; re-prepare")
        }
        val leaseAccount =
            admission?.toByteSourceAccount()
                ?: ByteSourceAccount(provider = ContentProvider.TIDAL.id, accountId = null)
        val lease =
            ByteSourceLease(
                account = leaseAccount,
                primaryOrigin = origins.first(),
                permittedOrigins = origins.toSet(),
                // The digest keeps the signed segment URLs out of host cache keys and logs.
                cacheKey = "tidal-dash:" + ContentDigest.sha256(urls.joinToString("\n").encodeToByteArray()),
                expiresAtEpochMs = expiresAtEpochMs,
                lengthBytes = null,
            )
        val view = TidalDashSource(lease, urls, manifest.isFlac, accountScope, opener)
        try {
            view.indexOpeningSegments()
        } catch (refusal: DashStreamException) {
            return NativePreparation.Refused(refusal.message ?: "TIDAL DASH stream was rejected")
        }
        return NativePreparation.Ready(
            NativePlaintext(
                source = view,
                mimeType = if (manifest.isFlac) FLAC_MIME_TYPE else manifest.mimeType,
                codec = if (manifest.isFlac) FLAC_CODEC else manifest.codecs,
                // The total is unknown until every segment is indexed, so the engine must not assume a length.
                length = null,
                expiresAtEpochMs = lease.expiresAtEpochMs,
            ),
        )
    }

    /**
     * The instant one segment URL stops being authorized, or null when its signature is present but unreadable.
     *
     * A URL with no X-Amz parameters carries no authorization to honour, so it receives the bounded 45-minute lease
     * and nothing longer. A URL that carries any signature parameter is judged only on its own signature: the issue
     * time and the window must each appear exactly once and be readable. The window must also lie within the signing
     * limit. Its lifetime is the issue time plus that window, less a 30-second guard. Anything else returns null, and
     * the caller refuses the preparation rather than defaulting, because a fresh default would extend an authorization
     * the signature no longer grants. The baseline cache floor of one minute is deliberately not applied here.
     */
    private fun expiryEpochMs(
        url: String,
        now: Long,
    ): Long? {
        val parameters = queryParameters(url)
        val expires = parameters[X_AMZ_EXPIRES]
        val date = parameters[X_AMZ_DATE]
        if (expires == null && date == null) return now + DEFAULT_LIFETIME_MS
        val expiresSeconds = expires?.singleOrNull()?.toLongOrNull() ?: return null
        val issuedAt = date?.singleOrNull()?.let { parseAmzDate(it) } ?: return null
        if (expiresSeconds !in 0L..MAX_SIGNED_WINDOW_SECONDS) return null
        return issuedAt + expiresSeconds * 1_000L - EXPIRY_GUARD_MS
    }

    /**
     * Groups the query parameters of [url] by lower-cased name and keeps every occurrence. A repeated signature
     * parameter therefore stays detectable instead of silently collapsing to whichever value came last.
     */
    private fun queryParameters(url: String): Map<String, List<String>> {
        val query = url.substringBefore('#').substringAfter('?', "")
        val parameters = HashMap<String, MutableList<String>>()
        for (part in query.split('&')) {
            val name = part.substringBefore('=').lowercase()
            if (name.isEmpty()) continue
            parameters.getOrPut(name) { ArrayList(1) }.add(part.substringAfter('=', ""))
        }
        return parameters
    }

    /** Parses the compact UTC form X-Amz-Date uses, such as 20261025T120000Z, into epoch milliseconds. */
    private fun parseAmzDate(value: String): Long? {
        if (value.length != AMZ_DATE_LENGTH || value[8] != 'T' || value[15] != 'Z') return null
        val year = value.substring(0, 4).toIntOrNull() ?: return null
        val month = value.substring(4, 6).toIntOrNull() ?: return null
        val day = value.substring(6, 8).toIntOrNull() ?: return null
        val hour = value.substring(9, 11).toIntOrNull() ?: return null
        val minute = value.substring(11, 13).toIntOrNull() ?: return null
        val second = value.substring(13, 15).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
        val seconds = daysFromCivil(year, month, day) * SECONDS_PER_DAY + hour * 3_600L + minute * 60L + second
        return seconds * 1_000L
    }

    /** Days since 1970-01-01 in the proleptic Gregorian calendar, using the standard era-based civil-date formula. */
    private fun daysFromCivil(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        // March is month index 0, so the leap day falls at the end of the year.
        val monthIndex = ((month + 9) % 12).toLong()
        val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    private const val DEFAULT_LIFETIME_MS = 45 * 60 * 1000L
    private const val EXPIRY_GUARD_MS = 30_000L
    private const val MIN_USABLE_LIFETIME_MS = 60_000L
    private const val MAX_SIGNED_WINDOW_SECONDS = 7L * 24 * 60 * 60
    private const val AMZ_DATE_LENGTH = 16
    private const val X_AMZ_EXPIRES = "x-amz-expires"
    private const val X_AMZ_DATE = "x-amz-date"
    private const val SECONDS_PER_DAY = 86_400L
    private const val FLAC_MIME_TYPE = "audio/flac"
    private const val FLAC_CODEC = "flac"
}
