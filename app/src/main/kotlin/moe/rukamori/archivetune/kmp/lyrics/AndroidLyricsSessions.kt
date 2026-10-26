/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.lyrics

import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.shared.domain.repository.AiTextService
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.session.LyricsSessions
import timber.log.Timber
import java.util.UUID

/** DI calls this once with the same application settings store and the M7 transport adapter. */
fun androidLyricsSessions(scope: CoroutineScope, settings: SettingsStore, service: AiTextService): LyricsSessions =
    LyricsSessions(scope, settings, service, UUID.randomUUID().toString()) { operation ->
        Timber.tag("AiLyrics").w("%s request failed", operation.name)
    }
