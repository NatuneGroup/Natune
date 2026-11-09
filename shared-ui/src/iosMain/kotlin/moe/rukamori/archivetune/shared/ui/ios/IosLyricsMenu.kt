package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiLyricsOperation
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.menu.*
import moe.rukamori.archivetune.shared.ui.resources.*

/** The lyrics menu binds the accepted occurrence, persistent edits and AI owner to the shared Android presentation. */
@Composable
internal fun IosLyricsMenu(menu: IosItemMenuState, graph: IosSessionGraph, selected: ContentOccurrence, navigate: (String) -> Unit) {
    val document by graph.lyrics.current.collectAsState()
    val saved by graph.settings.snapshots.collectAsState()
    val preferences = saved ?: PreferenceSnapshot.EMPTY
    val undo by graph.lyricsTools.edits.undo.collectAsState()
    val romanization by graph.lyricsSurface.sessions.romanization.state.collectAsState()
    val before = document?.takeIf { it.occurrenceId == selected.occurrenceId }?.let { graph.lyricsTools.snapshot(it.occurrenceId) }
    var translating by remember(selected.occurrenceId) { mutableStateOf(false) }
    fun current(): Boolean = graph.player.snapshot.value?.occurrenceId == selected.occurrenceId &&
        graph.lyrics.current.value?.occurrenceId == selected.occurrenceId
    val rows = buildList {
        add(OverflowAction("edit_lyrics", Res.string.m4c_edit, enabled = before != null) {
            check(current()); graph.lyricsTools.openEdit(selected.occurrenceId)
        })
        add(OverflowAction("refresh_lyrics", Res.string.m6_refetch) { check(current()); graph.lyrics.retry() })
        add(OverflowAction("translate_lyrics", Res.string.m6_translate_only, enabled = before != null, dismissOnSuccess = false) { translating = true })
        if (preferences.aiLyricsSessionSettings().romanization.active) add(OverflowAction("romanize_lyrics", Res.string.m6_romanize,
            enabled = before != null && !romanization.running) { check(current()); check(graph.lyricsSurface.sessions.manual(AiLyricsOperation.ROMANIZE)) })
        if (undo?.applied?.mediaId == selected.occurrenceId) add(OverflowAction("undo_lyrics", Res.string.m6_undo_translation, destructive = true) {
            check(current())
            if (graph.lyricsTools.edits.undo(::current)) graph.lyricsSurface.sessions.translationUndone(selected.occurrenceId)
        })
        add(OverflowAction("lyrics_timing", Res.string.phone_lyrics_lyrics_sync_offset) { graph.lyricsTools.timingTarget = selected.item.ref })
        add(OverflowAction("search_lyrics", Res.string.shortcut_action_search, enabled = false, description = Res.string.phone_not_available_on_ios, run = {}))
    }
    if (translating) {
        LyricsTranslationDialog(preferences[TranslatorTargetLangKey, "ENGLISH"], preferences.aiServiceSettings().canCallApi,
            translate = { language ->
                check(current())
                graph.settings.edit { this[TranslatorTargetLangKey] = language }
                current() && graph.lyricsSurface.sessions.manual(AiLyricsOperation.TRANSLATE)
            }, onDismiss = { translating = false; menu.dismiss() }, onFailure = menu::failed)
    } else LyricsOverflowMenu(rows, preferences.playerMenuStyle(), menu::dismiss, menu::failed)
}
