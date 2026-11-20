/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Megalobiz.kt) —
 * original re-implementation for ArchiveTune.
 */

package app.natune.lyrics

import app.natune.constants.EnableMegalobizLyricsKey
import app.natune.utils.dataStore
import app.natune.utils.get
import app.natune.shared.data.lyrics.MegalobizLyricsProvider as SharedMegalobizLyricsProvider

/** The shared-data Megalobiz provider on the app's lyrics client; the lookup itself lives in shared-data. */
object MegalobizLyricsProvider : LyricsProvider by SharedLyricsProviderAdapter(
    name = "Megalobiz",
    enabled = { context -> context.dataStore[EnableMegalobizLyricsKey] ?: true },
    create = { client -> SharedMegalobizLyricsProvider(client) },
)
