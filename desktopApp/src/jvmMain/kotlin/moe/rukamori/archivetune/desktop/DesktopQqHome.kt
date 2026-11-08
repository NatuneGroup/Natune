package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.shared.data.provider.KtorQqMusicRepository
import moe.rukamori.archivetune.shared.data.provider.QqMusicAccess
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.settings.QqMusicMusickeyKey
import moe.rukamori.archivetune.shared.domain.settings.QqMusicUinKey
import moe.rukamori.archivetune.shared.ui.home.*
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.HomeSourceBinding
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope

@Composable
internal fun desktopQqHome(graph: DesktopSessionGraph, source: HomeSourceBinding): ArchiveGraphPage {
    val preferences by graph.settings.snapshots.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val guid = remember { java.util.UUID.randomUUID().toString().replace("-", "") }
    val state by produceState<QqHomeUi>(QqHomeUi.Loading, preferences?.get(QqMusicUinKey), preferences?.get(QqMusicMusickeyKey), refresh) {
        value = QqHomeUi.Loading
        value = try {
            val accountScope = providerAccountScope(ContentProvider.QQ.id, CredentialScope.PERSONAL)
            val lease = accountScope?.let { graph.accounts.personalQqMusicLease(it) } ?: error("QQ sign-in required")
            val access = QqMusicAccess(lease.accountScope, lease.uin, lease.musicKey.reveal(), lease.loginType, CredentialScope.PERSONAL, lease::isStillAdmitted)
            val charts = withTimeout(30_000L) { KtorQqMusicRepository(graph.http, access, { guid }).homeCharts() }
            check(lease.isStillAdmitted())
            val sections = charts.mapIndexed { chartIndex, chart -> QqHomeSectionUi(chart.title, chart.label,
                chart.tracks.mapIndexed { index, track -> ContentOccurrence("qq-home:$chartIndex:$index:${track.item.ref.id}", track.queueItem()) }) }
            if (sections.isEmpty()) QqHomeUi.Empty else QqHomeUi.Ready(sections)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { graph.report("QQ home lookup declined: ${failure.javaClass.simpleName}"); QqHomeUi.Failed() }
    }
    val player by graph.player.snapshot.collectAsState()
    val queue by graph.queue.state.collectAsState()
    val active = queue.entries.firstOrNull { it.occurrenceId == player?.occurrenceId }?.item?.ref
    return ArchiveGraphPage.HomeQq(source, state, QqHomeActions({ section, row -> scope.launch {
        if (active == row.item.ref) {
            if (player?.playWhenReady == true) graph.player.pause() else graph.player.play()
        } else {
            val playable = desktopPlayableRows(graph, section.tracks)
            if (playable.any { it.occurrenceId == row.occurrenceId }) graph.queue.replace(playable, row.occurrenceId, true, staysFinite = true)
        }
    } }, { refresh++ }), active, player?.playWhenReady == true)
}
