package moe.rukamori.archivetune.shared.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import moe.rukamori.archivetune.shared.domain.playback.*
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportLibrary
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.ui.lyrics.*
import moe.rukamori.archivetune.shared.ui.queue.PlaybackQueuePanel
import moe.rukamori.archivetune.shared.ui.SafeAreaPadding
import moe.rukamori.archivetune.shared.ui.LocalHostSafeArea
import moe.rukamori.archivetune.shared.ui.safeAreaPadding
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveTuneShell
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab
import org.jetbrains.skia.EncodedImageFormat
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.library.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.desktop.*
import moe.rukamori.archivetune.shared.ui.home.*
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.navigation.DesktopSearchFocus
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.screens.*
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.*
import moe.rukamori.archivetune.shared.ui.settings.*
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
import java.io.File

import kotlin.test.Test

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
private fun playback(): PlaybackSnapshot? = playerFixture().playback!!.copy(
    occurrenceId = songs[0].occurrenceId, mediaId = songs[0].item.ref.id,
    durationMs = songs[0].item.durationMs, playWhenReady = false,
)
private fun nativeState() = PlayerNativeState(songs[0].occurrenceId, 1L, true, true, true, false,
    PlayerRepeat.OFF, true, .75f, false, emptyList())

private val noActions = ContentActions({}, {}, {}, {})

private class Store : SettingsStore {
    override val snapshots = MutableStateFlow(PreferenceSnapshot.EMPTY)
    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
        val before = snapshots.value
        val after = before.toEditor().apply(change).snapshot()
        snapshots.value = after
        return SettingsChange(before, after)
    }
}

private fun host(): ArtistAlbumHost = java.lang.reflect.Proxy.newProxyInstance(ArtistAlbumHost::class.java.classLoader,
    arrayOf(ArtistAlbumHost::class.java)) { proxy, method, arguments ->
    when (method.name) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === arguments?.firstOrNull()
        "toString" -> "render host"
        else -> when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    }
} as ArtistAlbumHost

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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PlatformRenderTest {
    private fun render(name: String, phone: Boolean, title: String, content: @Composable () -> Unit) = runTest {
        val (w, h, d) = if (phone) Triple(1179, 2556, 3f) else Triple(1440, 900, 1f)
        val insets = if (phone) SafeAreaPadding(top = 59.0, bottom = 34.0) else SafeAreaPadding.None
        val compositionJob = Job(coroutineContext[Job])
        val scene = ImageComposeScene(w, h, Density(d), coroutineContext = coroutineContext + compositionJob) {
            ArchiveTuneTheme(darkTheme = false, disableAnimations = true) {
                CompositionLocalProvider(
                    LocalAndroidPhonePresentation provides phone,
                    LocalDesktopLayout provides !phone,
                    LocalHostSafeArea provides insets,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (name in setOf("player", "lyrics", "fullscreen-lyrics", "transport-glyphs", "queue") || name.startsWith("player-style-")) RenderAuditFixture.Content(name, phone)
                        else Box(Modifier.fillMaxSize().safeAreaPadding(insets)) {
                            if (name in AuditScreens) {
                                if (phone) RenderAuditFixture.PhoneShell(name) else RenderAuditFixture.DesktopShellContent(name)
                            } else if (phone) PhoneFrame(title, name, content) else DesktopFrame(title, name, content)
                        }
                    }
                }
            }
        }
        try {
            // Fixed frame times settle layout/scroll effects without waiting for perpetual player animations.
            repeat(40) { frame ->
                runCurrent()
                scene.render(frame * 16_000_000L).close()
                advanceTimeBy(16)
            }
            val dir = File(System.getProperty("natune.renderDir"), if (phone) "ios-viewport-proxy" else "desktop")
            check(dir.isDirectory || dir.mkdirs())
            scene.render(640_000_000L).use { bitmap ->
                bitmap.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    File(dir, "$name.png").writeBytes(data.bytes)
                }
            }
        } finally { scene.close(); compositionJob.cancel(); runCurrent() }
    }

    @Composable
    private fun PhoneFrame(title: String, name: String, content: @Composable () -> Unit) {
        val nav = remember { ShellNavigation().apply {
            select(when (title) { "Library" -> ShellTab.Library; "Search" -> ShellTab.Search; else -> ShellTab.Home })
            when (name) {
                "settings" -> open("settings")
                "settings-lyrics" -> open("settings/lyrics")
                "settings-privacy" -> open("settings/privacy")
            }
        } }
        ArchiveTuneShell(nav, PreferenceSnapshot.EMPTY, {}, miniPlayer = { MiniBar() }) { content() }
    }

    @Composable
    private fun DesktopFrame(title: String, name: String, content: @Composable () -> Unit) {
        val nav = remember { ShellNavigation().apply {
            when (name) {
                "settings" -> open("settings")
                "settings-lyrics" -> open("settings/lyrics")
                "settings-privacy" -> open("settings/privacy")
            }
        } }
        DesktopShell(nav, remember { DesktopShellState() },
            titleBar = { DesktopTitleBar(title, remember { DesktopSearchFocus() }, true, {}, {}, onOpenSettings = {}) },
            playerBar = { DesktopBar() },
            queuePane = {}, lyricsPane = {}) { m -> Box(m) { content() } }
    }

    @Composable private fun Album() = AlbumScreen(albums[0].item.ref,
        AlbumPageUi(albums[0].item.copy(subtitle = "Daft Punk"), listOf(playlists[3].item), 2013, false, songs.take(6), true, emptyList()),
        AlbumLoadState.Content, emptyList(), ArtistAlbumNowPlaying(), art, host(), Modifier.fillMaxSize())
    @Composable private fun Artist() = ArtistScreen(playlists[3].item.ref,
        ArtistPageUi(playlists[3].item, "French electronic duo formed in Paris in 1993.", "12.4M monthly listeners", "9.8M subscribers",
            emptyList(), albums.take(3).mapIndexed { i, a -> ArtistReleaseUi(a.item.ref, a.item.title, null, 2013 - i, ArtistReleaseType.ALBUM) }, ""),
        false, null, ArtistBlockUiState.Known(false), ArtistAlbumNowPlaying(), art, host(), Modifier.fillMaxSize())
    @Composable private fun Playlist() = DetailScreen(DetailKind.Playlist, playlists[1].item,
        PagedContentState(initialized = true, items = songs), DetailActions(noActions, {}, {}, {}, {}, {}), art, Modifier.fillMaxSize())
    @Composable private fun History() = HistoryScreen(
        HistoryUi(sections = listOf(
            HistorySectionUi("today", "Today", songs.take(4).mapIndexed { i, s -> HistoryEventUi(i.toLong(), s, "1${i}:2$i") }),
            HistorySectionUi("yday", "Yesterday", songs.drop(4).mapIndexed { i, s -> HistoryEventUi(10L + i, s, "2${i}:0$i") }))),
        HistoryActions({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}), art, Modifier.fillMaxSize())
    @Composable private fun Statistics() {
        val ranks = songs.take(5).mapIndexed { i, s -> ListeningRank(s.item, 40L - i * 6, (40L - i * 6) * 200_000) }
        val arts = listOf("Daft Punk", "The Weeknd", "M83").mapIndexed { i, n ->
            ListeningRank(ContentItem(ContentRef("youtube", ContentKind.ARTIST, "ar$i"), n), 60L - i * 10, 3_600_000L * (3 - i)) }
        val stats = ListeningStatistics(ranks, arts, albums.take(3).mapIndexed { i, a -> ListeningRank(a.item, 30L - i, 1_800_000L) },
            (0 until 24).map { ListeningSlot(it, (it * 7L) % 13, 60_000L) }, (0 until 7).map { ListeningSlot(it, 5L + it, 60_000L) },
            188, 41_000_000)
        StatisticsScreen(StatisticsUi(StatisticsSource.LOCAL, setOf(StatisticsSource.LOCAL), StatisticsPeriod.WEEKS,
            listOf(StatisticsRangeUi("w", "This week", ListeningWindow(0, 1)), StatisticsRangeUi("l", "Last week", ListeningWindow(1, 2))),
            "w", stats, StatisticsCoverage.COMPLETE, true, listOf(2026)),
            StatisticsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}), art, { "${it / 60_000} min" }, { listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")[it % 7] },
            Modifier.fillMaxSize())
    }
    @Composable private fun Together() = ListenTogetherScreen(
        ListenTogetherUi(RoomConnectionUi.DISCONNECTED, "Listener", "", null, true, null),
        ListenTogetherActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}),
        art, { _, m -> Box(m.background(Color.Gray)) }, Modifier.fillMaxSize())
    @Composable private fun DesktopBar() = DesktopPlayerBar(
        songs[0], playback(), nativeState(), art, false, remember { DesktopShellState() }, {}, {}, {}, {}, {},
    )

    private fun both(name: String, title: String, content: @Composable (Boolean) -> Unit) {
        val failures = mutableListOf<Throwable>()
        for (phone in listOf(true, false)) runCatching { render(name, phone, title) { content(phone) } }.onFailure {
            failures += it
            System.err.println("RENDER-FAIL ${if (phone) "ios-viewport-proxy" else "desktop"}-$name: $it")
        }
        if (failures.isNotEmpty()) throw failures.first()
    }

    @Test fun home() = both("home", "Home") { RenderAuditFixture.Content("home", it) }
    @Test fun search() = both("search", "Search") { RenderAuditFixture.Content("search", it) }
    @Test fun library() = both("library", "Library") { RenderAuditFixture.Content("library", it) }
    @Test fun album() = both("album", "Album") { Album() }
    @Test fun artist() = both("artist", "Artist") { Artist() }
    @Test fun playlist() = both("playlist", "Playlist") { Playlist() }
    @Test fun history() = both("history", "History") { History() }
    @Test fun statistics() = both("statistics", "Statistics") { Statistics() }
    @Test fun together() = both("listen-together", "Listen together") { Together() }
    @Test fun settingsHome() = both("settings", "Settings") { RenderAuditFixture.Content("settings", it) }
    @Test fun settingsLyrics() = both("settings-lyrics", "Lyrics") { RenderAuditFixture.Content("settings-lyrics", it) }
    @Test fun settingsPrivacy() = both("settings-privacy", "Privacy") { RenderAuditFixture.Content("settings-privacy", it) }
    @Test fun player() = both("player", "Now playing") { RenderAuditFixture.Content("player", it) }
    @Test fun fullscreenLyrics() = both("fullscreen-lyrics", "Lyrics") { RenderAuditFixture.Content("fullscreen-lyrics", it) }
    @Test fun transportGlyphs() = both("transport-glyphs", "Transport") { RenderAuditFixture.Content("transport-glyphs", it) }
    @Test fun lyrics() = both("lyrics", "Lyrics") { RenderAuditFixture.Content("lyrics", it) }
    @Test fun queue() = both("queue", "Queue") { RenderAuditFixture.Content("queue", it) }
    @Test fun savedPlayerStyles() {
        for (style in PlayerDesignStyle.entries) both("player-style-${style.name}", "Now playing") { RenderAuditFixture.Content("player-style-${style.name}", it) }
    }
    @Test fun miniPlayer() = both("mini-player", "Home") { RenderAuditFixture.Content("mini-player", it) }
}
