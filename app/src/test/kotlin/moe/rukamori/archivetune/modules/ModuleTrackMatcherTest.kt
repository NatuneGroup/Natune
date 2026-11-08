package moe.rukamori.archivetune.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleTrackMatcherTest {
    private val target = ModuleMatchMetadata("Déjà Vu", listOf("Beyoncé"), null, 180, null, true)
    private val track = ModuleTrack("track", "deja vu", listOf("BEYONCE"), null, 180, null, null)

    @Test
    fun matchingIgnoresCaseAndDiacritics() {
        assertNull(ModuleTrackMatcher.evaluate(target, track).rejection)
    }

    @Test
    fun recordingQualifiersRemainPartOfTheGate() {
        for (qualifier in listOf("Remastered", "Clean", "Live")) {
            assertEquals(ModuleMatchRejection.RECORDING,
                ModuleTrackMatcher.evaluate(target, track.copy(title = "deja vu ($qualifier)")).rejection)
        }
    }

    @Test
    fun aDifferentDurationContinuesToTheNextProvider() {
        assertEquals(ModuleMatchRejection.DURATION,
            ModuleTrackMatcher.evaluate(target, track.copy(durationSeconds = 240)).rejection)
    }
}
