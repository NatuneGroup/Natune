/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.lyrics

import android.content.Context
import android.util.Log
import moe.rukamori.archivetune.betterlyrics.BetterLyrics
import app.natune.constants.EnableBetterLyricsKey
import app.natune.utils.GlobalLog
import app.natune.utils.dataStore
import app.natune.utils.get

object BetterLyricsProvider : LyricsProvider {
    init {
        BetterLyrics.logger = { message ->
            GlobalLog.append(Log.INFO, "BetterLyrics", message)
        }
    }

    override val name = "BetterLyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBetterLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = BetterLyrics.getLyrics(title = title, artist = artist, album = album, durationSeconds = duration)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        onFailure: (Throwable) -> Unit,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, duration).onSuccess(callback).onFailure(onFailure)
    }
}
