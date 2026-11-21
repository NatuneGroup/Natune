package app.natune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsKeyDictionaryTest {
    private val current = PersistedPreferenceKeys.map { it.name }.toSet()

    @Test
    fun everyRenameLandsOnAStoredKeyInOneStep() {
        SettingsKeyDictionary.renamed.forEach { (old, new) ->
            assertTrue(new in current, "$old is renamed to $new, which this build does not store")
            assertTrue(new !in SettingsKeyDictionary.renamed, "$old -> $new is a chain; point it at the final name")
            assertTrue(old !in current, "$old is still stored, so it is not a rename")
        }
    }

    @Test
    fun retiredSettingsAreNotStoredAnyMoreAndAreNotAlsoRenamed() {
        SettingsKeyDictionary.retired.forEach { name ->
            assertTrue(name !in current, "$name is retired but still stored")
            assertTrue(name !in SettingsKeyDictionary.renamed, "$name is both retired and renamed")
        }
    }

    @Test
    fun currentNamePassesThroughDropsRetiredAndFollowsRenames() {
        assertEquals("darkMode", SettingsKeyDictionary.currentName("darkMode"))
        assertNull(SettingsKeyDictionary.currentName("enablePaxsenixSpotifyLyrics"))
        SettingsKeyDictionary.renamed.forEach { (old, new) -> assertEquals(new, SettingsKeyDictionary.currentName(old)) }
    }
}
