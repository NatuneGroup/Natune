/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes the key identity and the edit model the settings transitions run on. A key is its name and value kind
 * together, the way DataStore identifies one, and an edit never changes a snapshot that was already read.
 */
class PreferenceSnapshotSettingsTest {
    @Test
    fun aKeyIsIdentifiedByNameAndValueKind() {
        assertEquals(BooleanPreferenceKey("flag"), BooleanPreferenceKey("flag"))
        assertNotEquals<PreferenceKey<*>>(BooleanPreferenceKey("flag"), BooleanPreferenceKey("other"))
        assertNotEquals<PreferenceKey<*>>(IntPreferenceKey("historyDuration"), FloatPreferenceKey("historyDuration"))
    }

    @Test
    fun editorWritesAndReadsTypedValues() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()

        editor[AppleMusicExperienceKey] = true
        editor[UiScaleFactorKey] = 1.2f
        editor[StyleBeforeAppleMusicKey] = "TIKTOK"

        assertEquals(true, editor[AppleMusicExperienceKey])
        assertEquals(1.2f, editor[UiScaleFactorKey])
        assertEquals("TIKTOK", editor[StyleBeforeAppleMusicKey])
    }

    @Test
    fun settingNullRemovesTheStoredValue() {
        val editor = PreferenceSnapshot.of(StyleBeforeAppleMusicKey to "TIKTOK").toEditor()

        editor[StyleBeforeAppleMusicKey] = null

        assertNull(editor[StyleBeforeAppleMusicKey])
        assertFalse(editor.snapshot().keys.contains(StyleBeforeAppleMusicKey))
    }

    @Test
    fun typedReadsFallBackToTheGivenDefault() {
        val snapshot = PreferenceSnapshot.EMPTY

        assertEquals(false, snapshot[AppleMusicExperienceKey, false])
        assertEquals(true, snapshot[AppleMusicExperienceKey, true])
    }

    @Test
    fun aSnapshotIsNotChangedByLaterEdits() {
        val editor = PreferenceSnapshot.of(PlayerDesignStyleKey to "V4").toEditor()
        val before = editor.snapshot()

        editor[PlayerDesignStyleKey] = "APPLE_MUSIC"

        assertEquals("V4", before[PlayerDesignStyleKey])
        assertEquals("APPLE_MUSIC", editor.snapshot()[PlayerDesignStyleKey])
    }

    @Test
    fun aMissingKeyIsAbsentRatherThanInvented() {
        val snapshot = PreferenceSnapshot.EMPTY

        assertNull(snapshot[StyleBeforeAppleMusicKey])
        assertTrue(snapshot.keys.isEmpty())
    }
}
