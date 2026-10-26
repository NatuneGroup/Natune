/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.playback

import java.io.File
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

/**
 * What a Telegram track reads through: the TDLib calls of the signed-in session, and the accepted-account store that admits
 * each read. [accountScope] is the store slot that the host committed the Telegram account under. Playback never chooses it,
 * and no account is inferred from a chat or file id, so a track with no accepted account is refused rather than read.
 */
internal class TelegramFileBinding(
    val operations: TdlibFileOperations,
    val accounts: AcceptedAccountReader,
    val accountScope: String,
) {
    init {
        require(accountScope.isNotBlank()) { "a Telegram file binding needs the account scope it was committed under" }
    }
}

/**
 * Supplies the binding of the Telegram session that is signed in, or null when none is. The host owns the TDLight client
 * and the account store and closes the client, so the playback side asks for the binding and never creates or closes one.
 */
internal typealias TelegramFileProvider = () -> TelegramFileBinding?

/**
 * The string the engine opens for [locator], or null when the engine cannot open it as a string. A local file must be
 * absolute, and a remote resource must be HTTP or HTTPS with no request headers. A header-bearing URL has no string
 * form, because the engine's own reader would send its request without the headers. The spectral probe uses this form.
 */
internal fun engineSourceOf(locator: PlaybackLocator): String? = when (locator) {
    is PlaybackLocator.LocalFile -> File(locator.path).takeIf { it.isAbsolute }?.path
    is PlaybackLocator.Remote -> locator.url.takeIf { isHttpUrl(it) && locator.headers.isEmpty() }
    // A Deezer CDN stream is ciphertext until the decrypting lease reads it. It has no string form the engine could
    // open, and the spectral probe must not read it as audio either.
    is PlaybackLocator.DeezerEncrypted -> null
    // A Telegram track is read through its TDLib file, never through the engine's own reader, so it has no string form.
    is PlaybackLocator.TelegramFile -> null
    // A prepared view is read through its host lease. It has no URL, so the engine cannot open it as a string either.
    is PlaybackLocator.PreparedPlaintext -> null
}

/**
 * What the engine opens for [locator], or null when it cannot open it. Every deck that opens a source goes through
 * here, so the same locator always names the same source. A header-free source keeps the engine's own reader. A
 * header-bearing HTTP(S) resource is read through a host lease, so its headers reach only its own origin.
 */
internal fun engineSourceOpen(
    locator: PlaybackLocator,
    telegram: TelegramFileProvider?,
): EngineSource? = when (locator) {
    is PlaybackLocator.LocalFile -> engineSourceOf(locator)?.let { EngineSource.Direct(it) }
    is PlaybackLocator.Remote ->
        if (locator.headers.isEmpty()) {
            engineSourceOf(locator)?.let { EngineSource.Direct(it) }
        } else {
            NativeStreamLease.forRemote(locator)?.let { EngineSource.Leased(it) }
        }
    is PlaybackLocator.DeezerEncrypted -> engineSourceForDeezerTrack(locator)
    is PlaybackLocator.TelegramFile -> engineSourceForTelegramTrack(locator, telegram)
    is PlaybackLocator.PreparedPlaintext -> engineSourceForPreparedPlaintext(locator)
}

/**
 * The host lease for one Deezer CDN track, read as decrypted audio. The chunk key derives from the track id, and the
 * typed locator carries that id with its URL, so no route can read the ciphertext without the key. Keeping the route
 * here keeps the rule that every deck names its source in one place. Null when the lease refuses the locator.
 */
internal fun engineSourceForDeezerTrack(locator: PlaybackLocator.DeezerEncrypted): EngineSource? =
    NativeStreamLease.forDeezerTrack(locator)?.let { EngineSource.Leased(it) }

/**
 * The host lease for one Telegram track, read through the TDLib partial file of the session that is signed in. Without a
 * bound session there is no TDLight client to read through, so the track is refused rather than read another way. The
 * account is admitted when the lease opens, inside [NativeStreamLease.forTelegramTrack]. The source never receives an HTTP
 * origin, because a TDLib file has none.
 */
internal fun engineSourceForTelegramTrack(
    locator: PlaybackLocator.TelegramFile,
    telegram: TelegramFileProvider?,
): EngineSource? {
    val binding = telegram?.invoke() ?: return null
    return EngineSource.Leased(NativeStreamLease.forTelegramTrack(locator.track, binding))
}

/**
 * The host lease for one prepared plaintext view, read through the transport that prepared it. Only a handle made by
 * [prepareHostPlaintext] can open, because only that handle carries the transport its view reads through, so a handle
 * from anywhere else is refused. A handle is single-use: a second load, or an expired view, is refused too, and the
 * occurrence then needs a fresh preparation. The caller falls through its source chain and never plays a progressive
 * URL instead.
 */
internal fun engineSourceForPreparedPlaintext(locator: PlaybackLocator.PreparedPlaintext): EngineSource? {
    val prepared = locator.handle as? HostPreparedPlaintext ?: return null
    return prepared.claim()?.let { EngineSource.Leased(it) }
}

/** What one deck loads: a source the engine reads itself, or a host lease the engine reads through callbacks. */
internal sealed interface EngineSource {
    /** A local file or a header-free HTTP(S) URL, which the engine opens with its own reader. */
    class Direct(val source: String) : EngineSource

    /** A header-bearing HTTP(S) resource. Every request is made through [lease], which the engine then owns. */
    class Leased(val lease: NativeStreamLease) : EngineSource
}

/**
 * Releases a source that no deck accepted. Once the engine accepts a leased source it closes it, so this is only for
 * the refusals that come before that hand-over, which would otherwise leave the lease without an owner.
 */
internal fun EngineSource.discard() {
    if (this is EngineSource.Leased) lease.close()
}

private fun isHttpUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
