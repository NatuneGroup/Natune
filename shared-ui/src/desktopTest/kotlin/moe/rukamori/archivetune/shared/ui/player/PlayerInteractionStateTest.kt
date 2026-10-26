package moe.rukamori.archivetune.shared.ui.player

import kotlin.test.*

class PlayerInteractionStateTest {
    @Test fun previewNeverSeeksAndCommitOnlyHappensOnce() {
        val interaction=PlayerInteractionState()
        val state=playerFixture()
        val seeks=mutableListOf<Long>()
        interaction.preview(.5f,state)
        interaction.preview(.7f,state)
        assertEquals(126000L,interaction.previewMs)
        assertTrue(seeks.isEmpty())
        interaction.commit(state,seeks::add)
        interaction.commit(state,seeks::add)
        assertEquals(listOf(126000L),seeks)
    }
    @Test fun trackSeekAndCapabilityChangesCannotCommitStaleDraft() {
        val state=playerFixture()
        for(changed in listOf(
            state.copy(playback=state.playback!!.copy(occurrenceId="other")),
            state.copy(playback=state.playback.copy(discontinuityGeneration=1)),
            state.copy(canSeek=false),
        )) {
            val interaction=PlayerInteractionState()
            interaction.preview(.7f,state)
            interaction.commit(changed) { fail("stale draft committed") }
            assertNull(interaction.previewMs)
        }
    }
    @Test fun unknownDurationAndNonfinitePreviewAreNotSeekable() {
        val interaction=PlayerInteractionState()
        val state=playerFixture()
        interaction.preview(Float.NaN,state)
        interaction.preview(.3f,state.copy(playback=state.playback!!.copy(durationMs=null)))
        assertNull(interaction.previewMs)
    }
    @Test fun backPriorityPreservesAudioAndPanelState() {
        val interaction=PlayerInteractionState()
        interaction.panel=PlayerPanel.QUEUE
        interaction.fullscreenLyrics=true
        assertTrue(interaction.back())
        assertEquals(PlayerPanel.QUEUE,interaction.panel)
        assertTrue(interaction.back())
        assertEquals(PlayerPanel.ARTWORK,interaction.panel)
        assertFalse(interaction.back())
    }
    @Test fun artworkSettlesIgnoreFollowAndUseOccurrenceNotMediaIdentity() {
        val ids=listOf("duplicate@0","duplicate@1","third@2")
        assertNull(settledArtworkOccurrence(ids,"duplicate@0",1,true,true))
        assertNull(settledArtworkOccurrence(ids,"duplicate@0",1,false,false))
        assertNull(settledArtworkOccurrence(ids,"duplicate@0",0,false,true))
        assertEquals("duplicate@1",settledArtworkOccurrence(ids,"duplicate@0",1,false,true))
        assertEquals("third@2",settledArtworkOccurrence(ids,"duplicate@0",2,false,true))
        assertNull(settledArtworkOccurrence(ids,"duplicate@0",9,false,true))
    }
}
