/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcRed.kt) —
 * original re-implementation for ArchiveTune.
 */

package app.natune.lyrics

import app.natune.constants.EnableLrcRedKey
import app.natune.utils.dataStore
import app.natune.utils.get
import app.natune.shared.data.lyrics.LrcRedLyricsProvider as SharedLrcRedLyricsProvider

/** The shared-data lrc.red provider on the app's lyrics client; the lookup itself lives in shared-data. */
object LrcRedLyricsProvider : LyricsProvider by SharedLyricsProviderAdapter(
    name = "lrc.red",
    enabled = { context -> context.dataStore[EnableLrcRedKey] ?: true },
    create = { client -> SharedLrcRedLyricsProvider(client) },
)
