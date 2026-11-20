/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.lyrics

import android.content.Context
import app.natune.constants.EnablePaxsenixAppleMusicLyricsKey
import moe.rukamori.archivetune.paxsenix.PaxsenixLyrics
import app.natune.utils.dataStore
import app.natune.utils.get

object PaxsenixAppleMusicLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: Apple Music"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnablePaxsenixAppleMusicLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> {
        // Nothing to fall back on here: without a token the module spends its only request on a
        // 401, so say so and let the next provider in the chain answer.
        if (!PaxsenixAppleMusicToken.install()) {
            return Result.failure(IllegalStateException("No Apple Music web player token available"))
        }
        return PaxsenixLyrics.getAppleMusicLyrics(title, artist, duration)
    }

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
