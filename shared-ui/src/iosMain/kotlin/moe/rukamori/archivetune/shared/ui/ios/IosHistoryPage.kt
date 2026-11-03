package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.platform.ios.iosDiagnosticTime
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.hasYouTubeSignInCookie
import moe.rukamori.archivetune.shared.ui.host.YouTubeHistoryPager
import moe.rukamori.archivetune.shared.ui.host.hostHistoryPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage

@Composable
internal fun iosHistoryPage(graph: IosSessionGraph, menu: IosItemMenuState): ArchiveGraphPage {
    val snapshot by graph.settings.snapshots.collectAsState()
    val cookie = snapshot?.get(InnerTubeCookieKey)
    val dataSyncId = snapshot?.get(DataSyncIdKey)
    val pager = remember(graph, cookie, dataSyncId) {
        val isCurrentAccount = {
            hasYouTubeSignInCookie(cookie) &&
                graph.settings.snapshots.value?.get(InnerTubeCookieKey) == cookie &&
                graph.settings.snapshots.value?.get(DataSyncIdKey) == dataSyncId
        }
        YouTubeHistoryPager(
            scope = graph.scope,
            fetch = {
                graph.youtubeTransport.refreshSession(graph.settings.snapshots.value)
                graph.youtubeTransport.historySections(it)
            },
            play = { row ->
                graph.scope.launch {
                    try {
                        if (!isCurrentAccount()) return@launch
                        if (row.item.ref.kind == ContentKind.EPISODE) {
                            graph.queue.replace(listOf(row), row.occurrenceId, playWhenReady = true)
                        } else {
                            graph.queue.load(QueueLoadRequest(
                                ContentRequest(ContentProvider.YOUTUBE, ContentSurface.ARTIST, text = row.item.ref.id),
                                QueueCollectionKind.RADIO, QueueStart(row.item.ref, row.occurrenceId, row.setVideoId),
                                playWhenReady = true,
                            ))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        graph.report("the history item could not be played")
                    }
                }
            },
            playShuffled = { rows ->
                graph.scope.launch {
                    try {
                        if (!isCurrentAccount()) return@launch
                        graph.queue.replace(rows, rows.first().occurrenceId, playWhenReady = true, staysFinite = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        graph.report("the history shuffle could not be played")
                    }
                }
            },
            report = graph::report,
            isCurrentAccount = isCurrentAccount,
        )
    }
    return hostHistoryPage(
        owners = graph.pageOwners,
        offsetMsAt = ::iosOffsetMsAt,
        nowMs = ::iosNowMillis,
        formatTime = ::iosDiagnosticTime,
        more = { menu.open(it) },
        remote = if (hasYouTubeSignInCookie(cookie)) listOf(pager) else emptyList(),
    )
}
