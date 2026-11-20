/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.scrobbling

import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTrackMetadata

/** Builds the shared Last.fm track fields from Android playback metadata; the cleanup rules live in shared-domain. */
fun LastFmTrackMetadata.Companion.from(
    metadata: MediaMetadata,
    durationSeconds: Int?,
): LastFmTrackMetadata? = from(metadata.artists.map { it.name }, metadata.title, metadata.album?.title, durationSeconds)
