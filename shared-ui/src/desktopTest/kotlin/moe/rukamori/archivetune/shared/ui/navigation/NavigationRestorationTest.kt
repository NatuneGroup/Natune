/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NavigationRestorationTest {
    @Test fun snapshotKeepsAllTabStacksAndDistinctEntryIdsForRepeatedRoutes() {
        val nav = ShellNavigation(ShellTab.Library)
        nav.open("local_playlist/LPtg-123")
        nav.open("album/a")
        val firstAlbum = nav.entry.id
        nav.open("online_playlist/PL1")
        nav.open("album/a")
        assertNotEquals(firstAlbum, nav.entry.id)
        nav.select(ShellTab.Search, toRoot = false)
        nav.open(ArchiveRoutes.search("日本語 + / ? &", ContentProvider.SPOTIFY_CATALOG))
        val restored = ShellNavigation.restore(nav.snapshot())
        assertEquals(nav.snapshot(), restored.snapshot())
        restored.select(ShellTab.Library, toRoot = false)
        assertEquals("album/a", restored.route)
        restored.back()
        assertEquals("online_playlist/PL1", restored.route)
        restored.select(ShellTab.Library, toRoot = true)
        assertEquals("library", restored.route)
        assertFalse(restored.back())
    }

    @Test fun routeArgumentsKeepLegacyIdsBase64SearchAndOpaqueParams() {
        val search = ArchiveRoutes.resolve(ArchiveRoutes.search("日本語 + / ? &", ContentProvider.SPOTIFY_CATALOG))!!
        assertEquals("日本語 + / ? &", search.searchQuery)
        assertEquals("SPOTIFY", search.arguments["provider"])
        assertEquals("hello", ArchiveRoutes.resolve("search/aGVsbG8?provider=YOUTUBE")!!.searchQuery)
        val request = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.EXPLORE,
            section = ContentSection("browse/id+?", "a+/=?&%25"))
        val route = ArchiveRoutes.resolve(ArchiveRoutes.browse(request))!!
        assertEquals(request, route.contentRequest())
        assertEquals("article/id ?&", ArchiveRoutes.resolve(ArchiveRoutes.article("article/id ?&"))!!.arguments["newsId"])
        assertEquals("LPtg-123", ArchiveRoutes.resolve("local_playlist/LPtg-123")!!.arguments["playlistId"])
        assertEquals("progress", ArchiveRoutes.resolve("auto_playlist/downloaded?tab=progress")!!.arguments["tab"])
        assertEquals("", ArchiveRoutes.resolve("youtube_browse/one?params=")!!.arguments["params"])
        assertNull(ArchiveRoutes.resolve("youtube_browse/one")!!.arguments["params"])
        assertEquals("liquid_glass_nav_bar", ArchiveRoutes.resolve("settings/appearance/navigation_bar?scrollTo=liquid_glass_nav_bar")!!.arguments["scrollTo"])
    }

    @Test fun initialPreferenceIsReadWithoutRewritingUnknownStoredValues() {
        assertEquals(ShellTab.Library, initialShellTab(PreferenceSnapshot.of(DefaultOpenTabKey to "LIBRARY")))
        assertEquals(ShellTab.Home, initialShellTab(PreferenceSnapshot.of(DefaultOpenTabKey to "future")))
    }

    @Test fun tabSwitchAndBackRestoreSaveablePageDraftButPoppedEntriesDoNotLeak() = runComposeUiTest {
        val nav = ShellNavigation()
        setContent { MaterialTheme {
            NavigationEntryContent(nav, Modifier.size(640.dp, 800.dp)) {
                var text by rememberSaveable { mutableStateOf("") }
                Column {
                    Text(nav.route)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth())
                }
            }
        } }
        onNode(hasSetTextAction()).performTextInput("home draft")
        runOnIdle { nav.open("album/one") }
        onNode(hasSetTextAction()).performTextInput("album draft")
        runOnIdle { nav.select(ShellTab.Library, toRoot = false) }
        onNode(hasSetTextAction()).performTextInput("library draft")
        runOnIdle { nav.select(ShellTab.Home, toRoot = false) }
        onNode(hasSetTextAction()).assertTextContains("album draft")
        runOnIdle { nav.back() }
        onNode(hasSetTextAction()).assertTextContains("home draft")
        runOnIdle { nav.open("album/one") }
        onNode(hasSetTextAction()).assertTextEquals("")
        onRoot().writeM4Screenshot("m4-navigation-restoration.png")
    }
}
