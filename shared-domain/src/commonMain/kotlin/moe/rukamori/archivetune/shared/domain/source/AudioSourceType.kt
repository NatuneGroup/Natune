/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.source

/**
 * Every audio source the multi-source chain can resolve through. The declaration order matches the
 * Android enum, and the persisted names are the contract: stored orders and per-song pins are written
 * as these names.
 */
enum class AudioSourceType {
    TIDAL,
    QOBUZ,
    QOBUZ_BACKUP,
    DEEZER,
    APPLE,
    AMAZON,
    QQ,
    SPOTIFY,
    JIOSAAVN,
    YOUTUBE,
}

/** Parses a stored source name, trimming and ignoring case. Unknown names return null. */
internal fun parseAudioSourceType(name: String): AudioSourceType? =
    runCatching { AudioSourceType.valueOf(name.trim().uppercase()) }.getOrNull()
