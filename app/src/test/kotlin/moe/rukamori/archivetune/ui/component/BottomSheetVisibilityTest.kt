package moe.rukamori.archivetune.ui.component

import org.junit.Assert.*
import org.junit.Test

class BottomSheetVisibilityTest {
    @Test fun settingsHaveNoCollapsedPlayerHitTargets() {
        val targets = bottomSheetHitTargets(0f, dismissed = false, collapsedContentVisible = false)
        assertFalse(targets.root)
        assertFalse(targets.expanded)
        assertFalse(targets.collapsed)
    }

    @Test fun dismissedPlayerNeverKeepsItsDragDetectorOrChildrenOnScreen() {
        val targets = bottomSheetHitTargets(-0.1f, dismissed = true, collapsedContentVisible = true)
        assertFalse(targets.root)
        assertFalse(targets.expanded)
        assertFalse(targets.collapsed)
    }

    @Test fun collapsedPlayerRetainsOnlyVisibleMiniPlayerTargets() {
        val targets = bottomSheetHitTargets(0f, dismissed = false, collapsedContentVisible = true)
        assertTrue(targets.root)
        assertTrue(targets.collapsed)
        assertFalse(targets.expanded)
    }

    @Test fun alphaZeroExpandedContentAndMiniPlayerCannotInterceptTouchesDuringTransition() {
        assertFalse(bottomSheetHitTargets(0.24f, false, true).expanded)
        assertFalse(bottomSheetHitTargets(0.25f, false, true).expanded)
        assertFalse(bottomSheetHitTargets(0.25f, false, true).collapsed)
        assertTrue(bottomSheetHitTargets(0.26f, false, true).expanded)
        assertFalse(bottomSheetHitTargets(0.26f, false, true).collapsed)
    }

    @Test fun expandedPlayerRemainsAvailableOnSettingsRoutes() {
        val targets = bottomSheetHitTargets(1f, false, false)
        assertTrue(targets.root)
        assertTrue(targets.expanded)
        assertFalse(targets.collapsed)
    }

    @Test fun leavingScreenCannotDismissAnotherOwnersRootSheet() {
        val state = BottomSheetPageState()
        val updateOwner = Any()
        val otherOwner = Any()
        state.show(updateOwner) { }
        state.show(otherOwner) { }
        state.dismiss(updateOwner)
        assertTrue(state.isVisible)
        state.dismiss(otherOwner)
        assertFalse(state.isVisible)
    }
}
