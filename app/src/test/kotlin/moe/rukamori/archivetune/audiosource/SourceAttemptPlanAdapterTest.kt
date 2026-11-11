package moe.rukamori.archivetune.audiosource

import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DefaultSourceAttemptPlan
import moe.rukamori.archivetune.shared.domain.source.SourceAttemptPlanRequest
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType as SharedAudioSourceType
import moe.rukamori.archivetune.shared.domain.source.PlaybackSourceSnapshot as SharedPlaybackSourceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAttemptPlanAdapterTest {
    @Test fun androidShippedOrderAndPinsAgreeWithTheDefaultSharedPlan() {
        val snapshot = PlaybackSourceSnapshot(AudioSourceConfig.DEFAULT_ORDER, AudioSourceType.entries.toSet())
        val shared = SharedPlaybackSourceSnapshot(snapshot.order.map { SharedAudioSourceType.valueOf(it.name) },
            snapshot.enabled.map { SharedAudioSourceType.valueOf(it.name) }.toSet())
        assertEquals(listOf("TIDAL", "QOBUZ", "QOBUZ_BACKUP", "DEEZER", "APPLE", "JIOSAAVN", "AMAZON", "QQ", "MODULES", "SPOTIFY"),
            DefaultSourceAttemptPlan.plan(SourceAttemptPlanRequest(shared)).attempts.map { it.sourceId.name })
        for (pin in listOf(null, AudioSourceType.MODULES, AudioSourceType.SPOTIFY, AudioSourceType.YOUTUBE)) {
            val old = PlaybackSourceChain.configuredChain(snapshot, pin, emptySet()).map { it.name }
            val plan = DefaultSourceAttemptPlan.plan(SourceAttemptPlanRequest(shared, pin?.let { SharedAudioSourceType.valueOf(it.name) }))
            assertEquals(old, plan.attempts.map { it.sourceId.name })
        }
    }
}
