package moe.rukamori.archivetune.shared.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

class SettingsControllerTest {
    @Test fun failedDurableEditNeitherPublishesNorPrompts() = runBlocking {
        val store = Store()
        val controller = SettingsController(store, PreferenceSnapshot.EMPTY, false)
        store.fail = true
        assertFailsWith<IllegalStateException> { controller.set(UiScaleFactorKey, 1.2f) }
        assertNull(store.snapshots.value[UiScaleFactorKey])
        assertFalse(controller.restart.value.visible)
    }

    @Test fun laterDoesNotUndoDurablePreferencesAndConfirmIsConsumedExactlyOnce() = runBlocking {
        val store = Store()
        val controller = SettingsController(store, PreferenceSnapshot.EMPTY, false)
        controller.set(UiScaleFactorKey, 1.1f)
        assertTrue(controller.restart.value.visible)
        controller.later()
        assertFalse(controller.restart.value.visible)
        assertEquals(1.1f, store.snapshots.value[UiScaleFactorKey])
        controller.set(UiScaleFactorKey, 1f)
        assertFalse(controller.restart.value.visible)
        controller.set(UiScaleFactorKey, 1.2f)
        var restarts = 0
        assertFalse(controller.restartNow(true) { restarts++ })
        assertTrue(controller.restartNow(false) { restarts++ })
        assertFalse(controller.restartNow(false) { restarts++ })
        assertEquals(1, restarts)
        val reopened = SettingsController(store, store.snapshots.value, false)
        assertFalse(reopened.restart.value.visible)
        assertEquals(1.2f, reopened.store.snapshots.value?.get(UiScaleFactorKey))
    }

    @Test fun interfaceChoiceIsOneAtomicEditButDoesNotInventARestartReason() = runBlocking {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        controller.interfaceStyle(InterfaceStyle.APPLE_MUSIC)
        assertEquals(true, store.snapshots.value[AppleMusicExperienceKey])
        assertEquals("APPLE_MUSIC", store.snapshots.value[PlayerDesignStyleKey])
        assertEquals("APPLE_MUSIC", store.snapshots.value[LibraryStyleKey])
        assertEquals(1, store.editCount)
        assertFalse(controller.restart.value.visible)
    }

    private class Store : SettingsStore {
        override val snapshots = MutableStateFlow(PreferenceSnapshot.EMPTY)
        var fail = false
        var editCount = 0
        override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
            if (fail) error("Storage unavailable")
            val before = snapshots.value
            val after = before.toEditor().apply(change).snapshot()
            editCount++
            snapshots.value = after
            return SettingsChange(before, after)
        }
    }
}
