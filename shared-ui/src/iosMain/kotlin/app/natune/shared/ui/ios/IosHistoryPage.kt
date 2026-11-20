package app.natune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.natune.shared.data.platform.ios.iosDiagnosticTime
import app.natune.shared.data.platform.ios.iosNowMillis
import app.natune.shared.domain.repository.ContentKind
import app.natune.shared.domain.repository.ContentProvider
import app.natune.shared.domain.repository.ContentRequest
import app.natune.shared.domain.repository.ContentSurface
import app.natune.shared.domain.repository.QueueCollectionKind
import app.natune.shared.domain.repository.QueueLoadRequest
import app.natune.shared.domain.repository.QueueStart
import app.natune.shared.domain.settings.DataSyncIdKey
import app.natune.shared.domain.settings.InnerTubeCookieKey
import app.natune.shared.domain.settings.hasYouTubeSignInCookie
import app.natune.shared.ui.host.YouTubeHistoryPager
import app.natune.shared.ui.host.hostHistoryPage
import app.natune.shared.ui.navigation.ArchiveGraphPage

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
