/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.EnableUpdateNotificationKey

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            return Result.success()
        }

        return try {
            val dataStore = applicationContext.dataStore

            val isEnabled = dataStore.data.map { it[EnableUpdateNotificationKey] ?: false }.first()
            if (!isEnabled) return Result.success()

            val revision = Updater.selectionRevision
            val updateChannel = Updater.selectedChannel(dataStore.data.first())
            Updater.getLatestRelease(updateChannel).onSuccess { release ->
                val offer = Updater.eligibleOffer(release, updateChannel)
                // A notification survives an app update. When the reader is already on the newest
                // build (the common case right after installing a nightly) there is no eligible
                // offer, so clear any stale "Update available" notification instead of leaving an
                // action that opens a build which is already installed.
                if (offer != null) {
                    UpdateNotificationManager.notifyIfNewVersion(applicationContext, offer, revision)
                } else {
                    UpdateNotificationManager.cancelUpdateNotification(applicationContext)
                }
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
