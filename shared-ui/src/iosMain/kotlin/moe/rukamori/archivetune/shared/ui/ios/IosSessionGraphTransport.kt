package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.rukamori.archivetune.shared.data.platform.ios.IosTransportAvailability

/** System transport uses the same occurrence-aware successors as the on-screen controls. */
internal fun IosSessionGraph.iosTransportAvailability(): Flow<IosTransportAvailability> = combine(
    queue.state, player.snapshot, settings.snapshots,
) { queueState, playback, preferences ->
    val native = iosNativeState(queueState, playback, preferences)
    IosTransportAvailability(canPrevious = native.canPrevious, canNext = native.canNext)
}.distinctUntilChanged()
