/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package app.natune.kmp.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.natune.R
import app.natune.shared.ui.menu.MenuSharing
import app.natune.telegram.TelegramClient
import app.natune.telegram.TelegramMediaId
import app.natune.utils.shareLocalAudio
import org.drinkless.tdlib.TdApi

/** Activity-scoped: the existing local content-URI chooser requires an Activity, not an app singleton. */
class AndroidMenuSharing(private val context: Activity) : MenuSharing {
    override suspend fun shareLink(url: String) = withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
        context.startActivity(Intent.createChooser(intent, null))
    }

    override suspend fun copyLink(url: String) = withContext(Dispatchers.Main) {
        // The player menu's copy-link toast is the established copied feedback; reuse it for every menu row.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.copy_link), url))
        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) = withContext(Dispatchers.Main) {
        check(shareLocalAudio(context, mediaId, mimeType)) { "Local audio is unavailable for sharing" }
    }

    override suspend fun telegramMessageLink(mediaId: String): String? {
        val id = TelegramMediaId.decode(mediaId) ?: return null
        return TelegramClient.send(TdApi.GetMessageLink().apply {
            chatId = id.chatId
            messageId = id.messageId
            pollOptionId = ""
        }).link?.takeIf(String::isNotBlank)
    }
}
