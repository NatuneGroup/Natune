package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import platform.Foundation.NSNotificationCenter

internal fun iosSystemSnapshotJson(queue: QueueState?, player: MusicPlayerController): String {
    val snapshot = player.snapshot.value
    val current = queue?.entries?.firstOrNull { it.occurrenceId == snapshot?.occurrenceId }
    return buildJsonObject {
        put("title", current?.item?.title.orEmpty()); put("artist", current?.item?.subtitle.orEmpty())
        put("playing", snapshot?.playWhenReady == true)
        put("queue", buildJsonArray { queue?.entries?.take(200)?.forEach { entry -> add(buildJsonObject {
            put("occurrence", entry.occurrenceId); put("provider", entry.item.ref.provider); put("id", entry.item.ref.id)
            put("title", entry.item.title); put("artist", entry.item.subtitle)
        }) } })
    }.toString()
}

internal fun iosStartSystemSnapshots(scope: CoroutineScope, queue: StateFlow<QueueState>, player: MusicPlayerController): Job =
    scope.launch {
        combine(queue, player.snapshot) { state, _ -> iosSystemSnapshotJson(state, player) }.distinctUntilChanged().collect {
            NSNotificationCenter.defaultCenter.postNotificationName("NatuneSystemSnapshotChanged", it)
        }
    }
