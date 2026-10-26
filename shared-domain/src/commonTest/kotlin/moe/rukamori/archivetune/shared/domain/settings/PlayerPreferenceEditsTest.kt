package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.*

class PlayerPreferenceEditsTest {
    @Test fun onlyFlamingoUsesLegacyVolumeUntilExplicitEdit() {
        val before = PreferenceSnapshot.of(ShowPlayerVolumeBarKey to true, FlamingoShowVolumeBarKey to false)
        assertFalse(before.showPlayerVolumeBar(PlayerDesignStyle.FLAMINGO))
        assertTrue(before.showPlayerVolumeBar(PlayerDesignStyle.V4))
        val after = before.toEditor().apply { setPlayerVolumeBar(false) }.snapshot()
        assertNull(after[FlamingoShowVolumeBarKey])
        assertFalse(after.showPlayerVolumeBar(PlayerDesignStyle.V4))
        assertFalse(after.showPlayerVolumeBar(PlayerDesignStyle.FLAMINGO))
        assertTrue(PreferenceSnapshot.EMPTY.showPlayerVolumeBar(PlayerDesignStyle.FLAMINGO))
    }

    @Test fun canonicalMetrolistPresentationHasStableWireIdentity() {
        assertEquals("metrolistKmpPresentation", MetrolistPresentationKey.name)
        assertTrue(MetrolistPresentationKey in PersistedPreferenceKeys)
        assertEquals("MODERN", PreferenceSnapshot.EMPTY[MetrolistPresentationKey, "MODERN"])
    }

    @Test fun mutableSetCannotMutateAnAlreadyPublishedSnapshot() {
        val key = StringSetPreferenceKey("set")
        val input = mutableSetOf("a", "b")
        val snapshot = PreferenceSnapshot.of(key to input)
        input.clear()
        assertEquals(setOf("a", "b"), snapshot[key])
        val returned = snapshot[key] as? MutableSet<String>
        returned?.clear()
        assertEquals(setOf("a", "b"), snapshot[key])
        val editor = snapshot.toEditor()
        val edited = mutableSetOf("x", "y")
        editor[key] = edited
        edited.clear()
        assertEquals(setOf("x", "y"), editor.snapshot()[key])
        assertFailsWith<IllegalArgumentException> { PreferenceSnapshot.of(key to true) }
    }
}
