/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.CreatePlaylistActions
import moe.rukamori.archivetune.shared.ui.screens.CreatePlaylistDialog
import moe.rukamori.archivetune.shared.ui.screens.CreatePlaylistUi
import kotlin.random.Random

/** The letters after the "LP" prefix of a local playlist id. The baseline draws eight ASCII letters for it. */
private const val PLAYLIST_ID_LETTERS = 8
private const val PLAYLIST_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

/**
 * A new local playlist id in the baseline shape: the "LP" prefix and eight random ASCII letters. The id is a handle, not a
 * secret, and the insert refuses an existing id, so an ordinary random source is enough.
 */
internal fun randomDesktopPlaylistId(random: Random = Random.Default): String =
    "LP" + String(CharArray(PLAYLIST_ID_LETTERS) { PLAYLIST_ID_ALPHABET[random.nextInt(PLAYLIST_ID_ALPHABET.length)] })

/**
 * The create-playlist dialog of this host. The draft lives here, so the page's create action and the overlay share it.
 * The save goes through the one library owner. A saved playlist opens through [openRoute], so the root navigates to it.
 * A failed save keeps the draft for another attempt.
 */
internal class DesktopPlaylistCreation(
    private val scope: CoroutineScope,
    private val content: DesktopLibraryContent,
    private val openRoute: (String) -> Unit,
) {
    var visible by mutableStateOf(false)
        private set
    var name by mutableStateOf("")
        private set
    var submitting by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun show() {
        name = ""
        failed = false
        visible = true
    }

    fun rename(value: String) {
        name = value
        failed = false
    }

    /** The dialog offers no dismiss while a save runs, so a dismiss during the save is ignored. */
    fun dismiss() {
        if (!submitting) close()
    }

    /**
     * Drops the draft at once. A save that is still running completes, but it opens no playlist, because the page it was
     * started on is gone.
     */
    fun close() {
        visible = false
        name = ""
        failed = false
    }

    fun submit(title: String) {
        if (submitting || title.isBlank()) return
        submitting = true
        failed = false
        scope.launch {
            val id = randomDesktopPlaylistId()
            val saved = try {
                content.createPlaylist(id, title)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            submitting = false
            if (!saved) {
                failed = true
                return@launch
            }
            val stillOpen = visible
            close()
            if (stillOpen) openRoute(ArchiveRoutes.content(ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, id)))
        }
    }
}

/**
 * The create dialog over the Playlists route. It is dropped when the route is left, so a stale draft never reopens later.
 */
@Composable
internal fun DesktopCreatePlaylistOverlay(creation: DesktopPlaylistCreation, route: ArchiveRoute) {
    DisposableEffect(route.value) { onDispose { creation.close() } }
    if (route.destination != ArchiveDestination.PLAYLISTS || !creation.visible) return
    CreatePlaylistDialog(
        // This host syncs no new playlist to an account, so the sync choice stays hidden and never reaches an owner.
        state = CreatePlaylistUi(name = creation.name, allowSyncing = false, submitting = creation.submitting, failed = creation.failed),
        actions = CreatePlaylistActions(
            name = creation::rename,
            sync = { },
            create = { title, _ -> creation.submit(title) },
            dismiss = creation::dismiss,
        ),
    )
}
