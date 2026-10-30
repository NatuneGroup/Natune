package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.data.playback.iosBlendOverlapMs
import moe.rukamori.archivetune.shared.data.playback.iosBlendSettings
import moe.rukamori.archivetune.shared.data.repository.QueueHandoffEngine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/** Uses the queue's occurrence/repeat/source resolution and the same setting meanings as Android and desktop. */
class IosCrossfadeHandoff(
    private val port: IosPlaybackPort,
    private val settings: StateFlow<PreferenceSnapshot?>,
) : QueueHandoffEngine {
    // Room playback must follow the room's song boundaries instead of overlapping songs independently on this device.
    private val held = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val configuration: Flow<Any?> = combine(
        settings.map { it.iosBlendSettings() }.distinctUntilChanged(), held,
    ) { stored, hold -> stored to hold }

    fun hold(value: Boolean) {
        if (held.value == value) return
        held.value = value
        if (value) port.cancelNext()
    }

    override fun overlapMs(current: ContentOccurrence, next: ContentOccurrence, durationMs: Long): Long? =
        if (held.value) null else iosBlendOverlapMs(settings.value.iosBlendSettings(), current.item, next.item, durationMs)

    override suspend fun arm(request: PlaybackRequest, overlapMs: Long): Boolean {
        if (held.value) return false
        return port.armNext(request, overlapMs)
    }

    override fun cancel() = port.cancelNext()
}
