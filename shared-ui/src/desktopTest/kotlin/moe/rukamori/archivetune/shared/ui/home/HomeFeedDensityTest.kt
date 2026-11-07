package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.desktop.DesktopShellMetrics
import moe.rukamori.archivetune.shared.ui.home.variants.HomeVariantChip
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState

/**
 * The desktop feed's vertical budget, measured against the reference app's first-sleeve target: on a 1430 x 861 window
 * the first cover starts no lower than 220 dp, and three shelves are visible above the player bar. Both numbers are the
 * parity checklist's, and both are the reason the desktop feed carries no greeting block, no page title and a four dp
 * section rhythm.
 */
@OptIn(ExperimentalTestApi::class)
class HomeFeedDensityTest {
    private fun row(id: String) = ContentOccurrence(
        "occurrence-$id",
        ContentItem(ContentRef("youtube", ContentKind.SONG, id), "Track $id", "Artist"),
    )

    private fun shelves() = listOf("picks", "second", "third").map { key ->
        HomeShelf(key, "Shelf $key", listOf(row("$key-1"), row("$key-2")))
    }

    @Test
    fun theFirstSleeveStartsWithinTheDesktopBudgetAndThreeShelvesAreVisible() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Box(Modifier.requiredSize(1430.dp, 861.dp)) {
                        Column(Modifier.fillMaxSize()) {
                            // The chrome the shell draws above and below the page, so the page is measured where a user
                            // sees it: the 56 dp top band and the 72 dp player bar.
                            Spacer(Modifier.fillMaxWidth().height(DesktopShellMetrics.titleBarHeight))
                            HomeScreen(
                                source = HomeSource.YOUTUBE,
                                availableSources = listOf(HomeSource.YOUTUBE),
                                onSource = {},
                                design = HomeFeedDesign.Classic,
                                shelves = shelves(),
                                pageState = PagedContentState(initialized = true, items = emptyList()),
                                actions = ContentActions({ _ -> }, null, {}, {}),
                                artwork = { _, modifier -> Box(modifier.background(Color.Blue)) },
                                headerActions = {},
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                chips = listOf(HomeVariantChip("podcasts", "Podcasts"), HomeVariantChip("sleep", "Sleep")),
                                selectedChipKey = null,
                                selectChip = {},
                                showChips = true,
                                blurEffects = false,
                            )
                            Spacer(Modifier.fillMaxWidth().height(DesktopShellMetrics.playerBarHeight))
                        }
                    }
                }
            }
        }
        val firstCover = onNodeWithTag("home-picks-occurrence-picks-1").getBoundsInRoot()
        assertTrue(firstCover.top <= 220.dp, "the first cover starts at ${firstCover.top}, over the 220 dp budget")
        // Three shelves above the fold: the third row's covers still begin above the player bar, which the shell draws in
        // the last 72 dp of the window.
        val pageBottom = 861.dp - DesktopShellMetrics.playerBarHeight
        listOf("home-second-occurrence-second-1", "home-third-occurrence-third-1").forEach { tag ->
            val cover = onNodeWithTag(tag).getBoundsInRoot()
            assertTrue(cover.top < pageBottom, "$tag starts at ${cover.top}, below the player bar at $pageBottom")
        }
    }
}
