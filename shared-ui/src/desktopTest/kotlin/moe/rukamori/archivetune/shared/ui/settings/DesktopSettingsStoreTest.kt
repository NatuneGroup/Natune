package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

class DesktopSettingsStoreTest {
    @Test fun allSevenKindsDynamicKeysAndLegacyMigrationPairSurviveReopen() = disk { path ->
        val values = PreferenceSnapshot.of(
            BooleanPreferenceKey("bool") to true,
            StringPreferenceKey("text") to "a\u0000b<&\n日本語",
            IntPreferenceKey("historyDuration") to 42,
            FloatPreferenceKey("historyDuration") to 12.5f,
            LongPreferenceKey("long") to Long.MAX_VALUE,
            DoublePreferenceKey("double") to 1.234567890123,
            StringSetPreferenceKey("set") to setOf("", "one", "two\nthree"),
        )
        DesktopSettingsStore.open(path).useStore { it.replaceAll(values) }
        DesktopSettingsStore.open(path).useStore { reopened ->
            val actual = reopened.snapshot()
            assertEquals(values.keys, actual.keys)
            values.keys.forEach { assertEquals(values[it], actual[it], it.toString()) }
        }
    }

    @Test fun appleExperienceAndRestartLatchSurviveActualDiskRestart() = disk { path ->
        DesktopSettingsStore.open(path).useStore { store ->
            store.edit { this[PlayerDesignStyleKey] = "FLAMINGO" }
            val controller = SettingsController(store, store.snapshot(), false)
            controller.interfaceStyle(InterfaceStyle.APPLE_MUSIC)
            controller.set(UiScaleFactorKey, 1.2f)
            assertTrue(controller.restart.value.visible)
            controller.later()
            assertEquals("FLAMINGO", store.snapshot()[StyleBeforeAppleMusicKey])
        }
        DesktopSettingsStore.open(path).useStore { store ->
            assertEquals(InterfaceStyle.APPLE_MUSIC, store.snapshot().interfaceStyle())
            assertEquals(LibraryStyle.APPLE_MUSIC, store.snapshot().libraryStyle())
            assertEquals(1.2f, store.snapshot()[UiScaleFactorKey])
            val controller = SettingsController(store, store.snapshot(), false)
            assertFalse(controller.restart.value.visible)
            controller.interfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)
            assertEquals("FLAMINGO", store.snapshot()[PlayerDesignStyleKey])
        }
    }

    @Test fun replaceAllRemovesAbsentKeysAndFailedTransformDoesNotPersist() = disk { path ->
        DesktopSettingsStore.open(path).useStore { store ->
            store.edit { this[DynamicThemeKey] = false; this[UiScaleFactorKey] = 1.1f }
            val changed = store.replaceAll(PreferenceSnapshot.of(DynamicThemeKey to true))
            assertEquals(setOf(DynamicThemeKey, UiScaleFactorKey), changed.changedKeys)
            assertFailsWith<IllegalArgumentException> { store.edit { this[DynamicThemeKey] = false; errorArgument() } }
            assertEquals(true, store.snapshot()[DynamicThemeKey])
        }
        DesktopSettingsStore.open(path).useStore { store ->
            assertEquals(true, store.snapshot()[DynamicThemeKey])
            assertNull(store.snapshot()[UiScaleFactorKey])
        }
    }

    @Test fun failedAtomicReplacementNeitherPublishesNorPromptsAndCanRetry() = disk { path ->
        DesktopSettingsStore.open(path).useStore { store ->
            store.edit { this[UiScaleFactorKey] = 1f }
            val saved = Files.readAllBytes(path)
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("occupied"), "prevent replacement")
            val controller = SettingsController(store, store.snapshot(), false)
            assertFailsWith<java.io.IOException> { controller.set(UiScaleFactorKey, 1.3f) }
            assertEquals(1f, store.snapshot()[UiScaleFactorKey])
            assertFalse(controller.restart.value.visible)
            Files.delete(path.resolve("occupied"))
            Files.delete(path)
            Files.write(path, saved)
            controller.set(UiScaleFactorKey, 1.2f)
            assertTrue(controller.restart.value.visible)
        }
        DesktopSettingsStore.open(path).useStore { assertEquals(1.2f, it.snapshot()[UiScaleFactorKey]) }
    }

    @Test fun serializedConcurrentEditsAndSingleOwnerLock() = disk { path ->
        DesktopSettingsStore.open(path).useStore { store ->
            assertFailsWith<java.io.IOException> { DesktopSettingsStore.open(path) }
            val counter = IntPreferenceKey("counter")
            (1..64).map { async { store.edit { this[counter] = this[counter, 0] + 1 } } }.awaitAll()
            assertEquals(64, store.snapshot()[counter])
        }
        DesktopSettingsStore.open(path).useStore { assertEquals(64, it.snapshot()[IntPreferenceKey("counter")]) }
    }

    @Test fun corruptOrTruncatedFileIsNotSilentlyReset() = disk { path ->
        DesktopSettingsStore.open(path).useStore { it.edit { this[DynamicThemeKey] = false } }
        val original = Files.readAllBytes(path)
        Files.write(path, original.copyOf(original.size - 1))
        assertFailsWith<java.io.IOException> { DesktopSettingsStore.open(path) }
        assertContentEquals(original.copyOf(original.size - 1), Files.readAllBytes(path))
        // A failed initial read must release the owner lock.
        Files.write(path, original)
        DesktopSettingsStore.open(path).useStore { assertEquals(false, it.snapshot()[DynamicThemeKey]) }
    }

    @Test fun invalidOrOversizedUtf8NeverChangesDiskAndCloseWorksAfterCancellation() = disk { path ->
        val store = DesktopSettingsStore.open(path)
        store.edit { this[DynamicThemeKey] = false }
        val original = Files.readAllBytes(path)
        assertFailsWith<IllegalArgumentException> { store.edit { this[StringPreferenceKey("oversized")] = "x".repeat(1_048_577) } }
        assertFailsWith<java.nio.charset.CharacterCodingException> { store.edit { this[StringPreferenceKey("invalid")] = "\uD800" } }
        assertContentEquals(original, Files.readAllBytes(path))
        assertEquals(setOf(DynamicThemeKey), store.snapshot().keys)
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { store.close() }
        }
        job.cancelAndJoin()
        DesktopSettingsStore.open(path).useStore { assertEquals(false, it.snapshot()[DynamicThemeKey]) }
    }

    private fun errorArgument(): Nothing = throw IllegalArgumentException("abort edit")
    private fun disk(block: suspend kotlinx.coroutines.CoroutineScope.(Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("m6-settings-")
        try { block(directory.resolve("settings.bin")) }
        finally { directory.toFile().deleteRecursively() }
    }
    private suspend fun DesktopSettingsStore.useStore(block: suspend (DesktopSettingsStore) -> Unit) {
        try { block(this) } finally { close() }
    }
}
