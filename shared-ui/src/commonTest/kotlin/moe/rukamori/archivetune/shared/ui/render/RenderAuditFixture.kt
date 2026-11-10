package moe.rukamori.archivetune.shared.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableSharedFlow
import moe.rukamori.archivetune.shared.domain.playback.*
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportLibrary
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.ui.lyrics.*
import moe.rukamori.archivetune.shared.ui.queue.PlaybackQueuePanel
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveTuneShell
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.DefaultLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.desktop.*
import moe.rukamori.archivetune.shared.ui.home.*
import moe.rukamori.archivetune.shared.ui.navigation.DesktopSearchFocus
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.screens.*
import moe.rukamori.archivetune.shared.ui.settings.*

private val palette = listOf(0xff3f51b5, 0xffe91e63, 0xff009688, 0xffff9800, 0xff673ab7, 0xff795548, 0xff2196f3, 0xff8bc34a)
private fun swatch(key: String) = Color(palette[(key.hashCode() and 0x7fffffff) % palette.size])
private val art: ContentArtwork = { item, modifier ->
    val c = swatch(item.ref.id)
    Box(modifier.background(Brush.linearGradient(listOf(c, c.copy(alpha = .55f)))))
}
private fun song(id: String, title: String, artist: String, ms: Long = 200_000L) =
    ContentOccurrence("$id@1", ContentItem(ContentRef("youtube", ContentKind.SONG, id), title, artist, durationMs = ms))
private fun card(kind: ContentKind, id: String, title: String, sub: String) =
    ContentOccurrence("$id@c", ContentItem(ContentRef("youtube", kind, id), title, sub))
private val songs = listOf(
    song("s1", "Get Lucky", "Daft Punk", 248_000), song("s2", "Instant Crush", "Daft Punk", 337_000),
    song("s3", "Blinding Lights", "The Weeknd", 200_000), song("s4", "Midnight City", "M83", 243_000),
    song("s5", "Redbone", "Childish Gambino", 326_000), song("s6", "Nightcall", "Kavinsky", 258_000),
    song("s7", "Electric Feel", "MGMT", 229_000), song("s8", "Genesis", "Justice", 234_000),
)
private val albums = listOf(
    card(ContentKind.ALBUM, "a1", "Random Access Memories", "Daft Punk • 2013"),
    card(ContentKind.ALBUM, "a2", "After Hours", "The Weeknd • 2020"),
    card(ContentKind.ALBUM, "a3", "Hurry Up, We're Dreaming", "M83 • 2011"),
    card(ContentKind.ALBUM, "a4", "Awaken, My Love!", "Childish Gambino • 2016"),
    card(ContentKind.ALBUM, "a5", "Oracular Spectacular", "MGMT • 2007"),
    card(ContentKind.ALBUM, "a6", "Cross", "Justice • 2007"),
)
private val playlists = listOf(
    card(ContentKind.PLAYLIST, "p1", "Liked songs", "Playlist • 128 songs"),
    card(ContentKind.PLAYLIST, "p2", "Night drive", "Playlist • 42 songs"),
    card(ContentKind.PLAYLIST, "p3", "Focus", "Playlist • 64 songs"),
    card(ContentKind.ARTIST, "r1", "Daft Punk", "Artist"),
)
private fun playback(): PlaybackSnapshot? = PlaybackSnapshot("s1@1", "s1", 1, 0, 12_000, 0, 248_000, false, 1f, true, false, false, PlaybackOutputRoute.LOCAL).copy(
    occurrenceId = songs[0].occurrenceId, mediaId = songs[0].item.ref.id,
    durationMs = songs[0].item.durationMs, playWhenReady = false,
)
private fun nativeState() = PlayerNativeState(songs[0].occurrenceId, 1L, true, true, true, false,
    PlayerRepeat.OFF, true, .75f, false, emptyList())

private val noActions = ContentActions({}, {}, {}, {})

private class RenderStore : SettingsStore {
    override val snapshots = MutableStateFlow(PreferenceSnapshot.EMPTY)
    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
        val before = snapshots.value
        val after = before.toEditor().apply(change).snapshot()
        snapshots.value = after
        return SettingsChange(before, after)
    }
}

@Composable
private fun MiniBar(modifier: Modifier = Modifier) {
    val playback = playback()
    ArchiveMiniPlayer(
        current = songs[0], playback = playback,
        nativeState = nativeState(),
        artwork = art, motion = false, onExpand = {}, onOpenPanel = {}, onPlayPause = {}, onSeekTo = {}, onAction = {},
        onVolumeChange = {}, modifier = modifier,
    )
}

internal val AuditScreens = listOf("home", "player", "fullscreen-lyrics", "mini-player", "queue", "lyrics", "settings", "settings-lyrics", "settings-privacy", "search", "library") + PlayerDesignStyle.entries.map { "player-style-${it.name}" }

internal object RenderAuditFixture {
    @Composable fun Content(name: String, phone: Boolean) {
        when (name) {
            "home", "mini-player" -> Home()
            "search" -> Search()
            "library" -> Library()
            "settings" -> SettingsHome(phone)
            "settings-lyrics" -> SettingsRows("settings/lyrics", phone, lyricsSettingsRows {})
            "settings-privacy" -> SettingsRows("settings/privacy", phone, privacyInlineSettingsRows())
            "player" -> Player(phone)
            "lyrics" -> Player(phone, PlayerPanel.LYRICS)
            "fullscreen-lyrics" -> Player(phone, PlayerPanel.LYRICS, fullscreen = true)
            "queue" -> Player(phone, PlayerPanel.QUEUE)
            else -> if (name.startsWith("player-style-")) Player(phone, style = PlayerDesignStyle.valueOf(name.removePrefix("player-style-"))) else error("Unknown audit screen: $name")
        }
    }

    @Composable fun PhoneShell(name: String) {
        val nav = remember(name) { ShellNavigation().apply {
            select(when (name) { "library" -> ShellTab.Library; "search" -> ShellTab.Search; else -> ShellTab.Home })
            if (name.startsWith("settings")) open(name.replace("settings-", "settings/"))
        } }
        ArchiveTuneShell(nav, PreferenceSnapshot.EMPTY, {}, miniPlayer = { MiniBar() }) { Content(name, true) }
    }

    @Composable fun DesktopShellContent(name: String) {
        val nav = remember(name) { ShellNavigation().apply {
            select(when (name) { "library" -> ShellTab.Library; "search" -> ShellTab.Search; else -> ShellTab.Home })
            if (name.startsWith("settings")) open(name.replace("settings-", "settings/"))
        } }
        DesktopShell(nav, remember { DesktopShellState() },
            titleBar = { DesktopTitleBar(name, remember { DesktopSearchFocus() }, true, {}, {}, onOpenSettings = {}) },
            playerBar = { DesktopBar() }, queuePane = {}, lyricsPane = {}) { m -> Box(m) { Content(name, false) } }
    }
    @Composable private fun Home() = HomeScreen(HomeSource.YOUTUBE, HomeSource.entries, {}, HomeFeedDesign.Classic,
        listOf(HomeShelf("quick", "Quick picks", songs), HomeShelf("albums", "Albums for you", albums),
            HomeShelf("mixes", "Your mixes", playlists)),
        PagedContentState(initialized = true, items = songs), noActions, art, {}, Modifier.fillMaxSize(), accountName = "Listener")
    @Composable private fun Search() = SearchScreen("daft punk", SearchScope.Online, {}, {}, {}, {}, {},
        PagedContentState(initialized = true, items = listOf(playlists[3]) + songs.take(4) + albums.take(2)),
        noActions, art, Modifier.fillMaxSize())
    @Composable private fun Library() = LibraryScreen(LibraryFilter.LIBRARY, DefaultLibraryFilterOrder, {},
        PagedContentState(initialized = true, items = playlists + albums.take(3)), noActions, art, {}, {}, Modifier.fillMaxSize())
    @Composable private fun SettingsHome(phone: Boolean) {
        val store = remember { RenderStore() }
        CompositionLocalProvider(LocalSettingsBack provides {}, LocalPhoneSettingsReference provides if (phone) phoneSettingsReference("settings") else null) {
            SettingsHomePage(remember { SettingsController(store, store.snapshots.value, false) }, {}, {}, modifier = Modifier.fillMaxSize())
        }
    }
    @Composable private fun SettingsRows(route: String, phone: Boolean, rows: List<SettingsRow>) {
        val store = remember { RenderStore() }
        CompositionLocalProvider(LocalSettingsBack provides {}, LocalPhoneSettingsReference provides if (phone) phoneSettingsReference(route) else null) {
            SettingsPage(rows, store.snapshots.collectAsState().value, remember { SettingsController(store, store.snapshots.value, false) }, onFailure = {})
        }
    }
    @Composable private fun DesktopBar() = DesktopPlayerBar(
        songs[0], playback(), nativeState(), art, false, remember { DesktopShellState() }, {}, {}, {}, {}, {},
    )
    @Composable private fun Player(phone: Boolean, panel: PlayerPanel = PlayerPanel.ARTWORK, style: PlayerDesignStyle = PlayerDesignStyle.Default, fullscreen: Boolean = false) {
        val state = PlayerState(playback(), songs[0].item.title, songs[0].item.subtitle, songs.map { it.occurrenceId }, canPrevious = true, canNext = true, volume = .75f, reducedMotion = true)
        val slots = PlayerSlots(
            artwork = { _, m -> art(songs[0].item, m) },
            canvas = { _, _, m -> Box(m) },
            customBackground = { _, m -> Box(m) },
            lyrics = { m -> Lyrics(m) },
            compactLyrics = { m -> Lyrics(m) },
            queue = { m -> Queue(m) },
        )
        if (!phone && panel == PlayerPanel.ARTWORK && style == PlayerDesignStyle.Default) {
            DesktopExpandedPlayer(state, slots, {}, {}, {}, {}, {}, Modifier.fillMaxSize())
        } else {
            BottomSheetPlayer(state, PlayerActions({}, {}, {}, {}, {}, {}), PreferenceSnapshot.of(PlayerDesignStyleKey to style.name), slots, false,
                Modifier.fillMaxSize(), remember { PlayerInteractionState().apply { this.panel = panel; fullscreenLyrics = fullscreen } })
        }
    }
    @Composable private fun Lyrics(modifier: Modifier) {
        val lines = listOf("Like the legend of the phoenix", "All ends with beginnings",
            "What keeps the planet spinning", "The force from the beginning", "We've come too far",
            "To give up who we are", "So let's raise the bar", "And our cups to the stars")
            .mapIndexed { i, text -> SyncedLine(i * 8_000L, text, durationMs = 8_000L) }
        LyricsFullscreenRenderer(lines, "render", remember { MutableStateFlow(playback()) },
            remember { LyricsRomanizationState(LyricsRomanizationPresentation.off("render")) },
            LyricsSurfaceSettings(reduceMotion = true), LyricsSurfaceActions({}, {}), modifier)
    }
    @Composable private fun Queue(modifier: Modifier) {
        val scope = rememberCoroutineScope()
        val player = remember {
            MusicPlayerController(object : PlaybackPort {
                override val snapshots = MutableStateFlow(playback())
                override val failures = MutableSharedFlow<PlaybackFailure>()
                override fun submit(command: PlaybackCommand) = error("Read-only render fixture")
                override fun close() = Unit
            }, scope)
        }
        DisposableEffect(player) { onDispose { player.close() } }
        val queue = remember { AuditQueue(player) }
        PlaybackQueuePanel(queue, AuditQueueEdits, remember { RenderStore() }, AuditLibrary,
            modifier, artwork = { entry, m -> art(entry.item, m) })
    }
}

private class AuditQueue(override val player: MusicPlayerController) : QueueController {
    override val state = MutableStateFlow(QueueState(1, songs, songs[0].occurrenceId, songs.map { it.occurrenceId }))
    override suspend fun replace(entries: List<ContentOccurrence>, startOccurrenceId: String, playWhenReady: Boolean, staysFinite: Boolean) = error("Read-only fixture")
    override suspend fun insert(entries: List<ContentOccurrence>, kind: moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind): Int = error("Read-only fixture")
    override suspend fun remove(occurrenceIds: Set<String>) = error("Read-only fixture")
    override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) = error("Read-only fixture")
    override suspend fun select(occurrenceId: String, positionMs: Long) = error("Read-only fixture")
}
private object AuditQueueEdits : QueueEditingCommands {
    override val infiniteQueueLoading = MutableStateFlow(false)
    override val queueSource = MutableStateFlow<ContentRequest?>(null)
    override suspend fun setShuffle(enabled: Boolean): Boolean = error("Read-only fixture")
    override suspend fun moveInPlayOrder(occurrenceId: String, beforeOccurrenceId: String?) = error("Read-only fixture")
    override suspend fun moveNext(occurrenceId: String) = error("Read-only fixture")
    override suspend fun removeUndoable(occurrenceIds: Set<String>): Long? = error("Read-only fixture")
    override suspend fun undoRemoval(ticket: Long): Boolean = error("Read-only fixture")
    override suspend fun clearUpcoming(): Long? = error("Read-only fixture")
    override suspend fun refreshInfiniteQueue() = error("Read-only fixture")
}
private object AuditLibrary : PlaylistImportLibrary {
    override suspend fun findSong(mediaId: String, provider: String?): ContentItem? = error("Read-only fixture")
    override suspend fun createImportedPlaylist(id: String, title: String, occurrences: List<ContentOccurrence>, tags: Set<String>) = error("Read-only fixture")
    override suspend fun appendImported(playlistId: String, occurrences: List<ContentOccurrence>, tags: Set<String>) = error("Read-only fixture")
    override suspend fun likeAll(items: List<ContentItem>) = error("Read-only fixture")
    override fun observe(query: ContentQuery): kotlinx.coroutines.flow.Flow<ContentPage> = error("Read-only fixture")
    override suspend fun mutate(mutation: LibraryMutation) = error("Read-only fixture")
    override suspend fun page(query: ContentQuery, continuation: String?): ContentPage = error("Read-only fixture")
    override suspend fun item(ref: ContentRef): ContentItem = error("Read-only fixture")
}
