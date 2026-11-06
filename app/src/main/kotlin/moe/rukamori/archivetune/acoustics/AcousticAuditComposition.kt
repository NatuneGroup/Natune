/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.acoustics

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditOwner
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget

/**
 * The audit owner of one Android surface: the shared state machine over this host's probe. A different
 * song gets its own owner, so no result, status or progress from one song can be read as another's.
 */
@Composable
fun rememberAcousticAuditOwner(
    target: AcousticAuditTarget,
    active: Boolean = true,
): AcousticAuditOwner {
    val context = LocalContext.current.applicationContext
    val service = LocalPlayerConnection.current?.service
    val downloads = LocalDownloadUtil.current
    val audit = remember(target.mediaId, context, service, downloads) {
        AndroidAcousticAudit(
            context,
            listOf(downloads.downloadCache, downloads.playerCache),
            snapshot = { service?.acousticAuditSourceSnapshot(it) },
        )
    }
    DisposableEffect(audit, active) {
        audit.owner.setActive(active)
        onDispose { audit.owner.cancel() }
    }
    DisposableEffect(audit) {
        onDispose { audit.close() }
    }
    return audit.owner
}

/**
 * Binds an audit target to this host's source identity. The shared owner refuses an unbound target, and
 * Android's identity is the media id: the probe re-checks the live snapshot after the analysis, so a
 * rendition swapped mid-run is refused instead of bound.
 */
internal fun AcousticAuditTarget.auditKey(): AcousticAuditTarget =
    if (requestKey.isNotEmpty()) this else copy(requestKey = mediaId)

/** A mounted audit surface's owner, its scope and its probe. */
internal class AndroidAcousticAudit(
    context: Context,
    caches: List<Cache>,
    snapshot: (String) -> AcousticSourceSnapshot?,
) {
    private val probe = AcousticAuditProbe(context, caches, snapshot)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val owner = AcousticAuditOwner(scope, verifySource = { probe.verifySource() }) { target, run ->
        probe.window(target, run)
    }

    fun close() {
        owner.close()
        scope.cancel()
    }
}
