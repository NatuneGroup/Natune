/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Genius.kt) —
 * original re-implementation for ArchiveTune.
 */

package app.natune.lyrics

import app.natune.constants.EnableGeniusLyricsKey
import app.natune.utils.dataStore
import app.natune.utils.get
import app.natune.shared.data.lyrics.GeniusLyricsProvider as SharedGeniusLyricsProvider

/** The shared-data Genius provider on the app's lyrics client; the lookup itself lives in shared-data. */
object GeniusLyricsProvider : LyricsProvider by SharedLyricsProviderAdapter(
    name = "Genius",
    enabled = { context -> context.dataStore[EnableGeniusLyricsKey] ?: false },
    create = { client -> SharedGeniusLyricsProvider(client) },
)
