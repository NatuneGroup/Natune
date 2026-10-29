package moe.rukamori.archivetune.uploads

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadPolicy
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadResult
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadedSong
import moe.rukamori.archivetune.utils.reportException

/**
 * Streams one picked file into the account, then waits for YouTube Music to list it. Progress and the final
 * stage are published as work data, and the foreground notification shows the same state while the work runs.
 */
class YouTubeUploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val notificationId: Int = (id.hashCode() and Int.MAX_VALUE) or 1
    private var lastPercent = -1

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(
            text = applicationContext.getString(R.string.youtube_upload_notification_queued),
            progress = INDETERMINATE,
        )

    override suspend fun doWork(): Result =
        try {
            performUpload()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            reportException(failure)
            finish(
                stage = YouTubeUploadStage.FAILED,
                name = inputData.getString(YouTubeUploadWorkData.KEY_NAME).orEmpty(),
            )
        }

    private suspend fun performUpload(): Result {
        val name = inputData.getString(YouTubeUploadWorkData.KEY_NAME).orEmpty()
        setProgressAsync(progressData(YouTubeUploadStage.QUEUED, name, percent = null))
        val uri =
            inputData.getString(YouTubeUploadWorkData.KEY_URI)?.let(Uri::parse)
                ?: return finish(YouTubeUploadStage.FAILED, name, failure = YouTubeUploadWorkData.FAILURE_UNREADABLE)
        val info =
            runCatching { describeYouTubeUploadDocument(applicationContext, uri) }.getOrNull()
                ?: return finish(YouTubeUploadStage.FAILED, name, failure = YouTubeUploadWorkData.FAILURE_UNREADABLE)
        val fileName = info.fileName.ifBlank { name }
        if (info.length < 0L) {
            return finish(YouTubeUploadStage.FAILED, fileName, failure = YouTubeUploadWorkData.FAILURE_UNREADABLE)
        }
        YouTubeUploadPolicy.validate(fileName, info.length)?.let { rejection ->
            return finish(YouTubeUploadStage.FAILED, fileName, failure = rejection.name)
        }
        val document =
            runCatching { openYouTubeUploadDocument(applicationContext, uri, info) }.getOrNull()
                ?: return finish(YouTubeUploadStage.FAILED, fileName, failure = YouTubeUploadWorkData.FAILURE_UNREADABLE)

        val repository =
            EntryPointAccessors
                .fromApplication(applicationContext, YouTubeUploadWorkerEntryPoint::class.java)
                .uploads()
        // The shelf before the transfer tells the new song apart from the ones already listed.
        val before = recoverFromFailure { repository.browse(null).songs }
        before?.let { repository.rememberUploadedVideoIds(it.map(YouTubeUploadedSong::videoId)) }

        lastPercent = -1
        setProgressAsync(progressData(YouTubeUploadStage.UPLOADING, fileName, percent = 0))
        val outcome =
            document.use { content ->
                repository.upload(fileName, content) { sent -> reportUpload(fileName, sent, info.length) }
            }
        return when (outcome) {
            YouTubeUploadResult.AlreadyUploaded -> finish(YouTubeUploadStage.ALREADY_UPLOADED, fileName)
            is YouTubeUploadResult.Failed -> finish(YouTubeUploadStage.FAILED, fileName, failure = outcome.failure.name)
            YouTubeUploadResult.Uploaded -> awaitProcessing(repository, fileName, before)
        }
    }

    private suspend fun awaitProcessing(
        repository: YouTubeUploadsRepository,
        fileName: String,
        before: List<YouTubeUploadedSong>?,
    ): Result {
        val existing = before ?: return finish(YouTubeUploadStage.UPLOADED, fileName)
        val known = existing.mapTo(HashSet()) { it.videoId }
        setProgressAsync(progressData(YouTubeUploadStage.PROCESSING, fileName, percent = null))
        updateNotification(
            text = applicationContext.getString(R.string.youtube_upload_notification_processing, fileName),
            progress = INDETERMINATE,
        )
        repeat(PROCESSING_POLLS) {
            delay(POLL_INTERVAL_MS)
            val page = recoverFromFailure { repository.browse(null) } ?: return@repeat
            repository.rememberUploadedVideoIds(page.songs.map(YouTubeUploadedSong::videoId))
            val listed = page.songs.firstOrNull { it.videoId !in known }
            if (listed != null) {
                return finish(
                    stage = YouTubeUploadStage.DONE,
                    name = listed.title,
                    title = listed.title,
                    videoId = listed.videoId,
                )
            }
        }
        return finish(YouTubeUploadStage.UPLOADED, fileName)
    }

    private fun reportUpload(fileName: String, sent: Long, total: Long) {
        val percent = if (total <= 0L) 0 else ((sent * 100L) / total).toInt().coerceIn(0, 100)
        if (percent == lastPercent) return
        lastPercent = percent
        setProgressAsync(progressData(YouTubeUploadStage.UPLOADING, fileName, percent))
        updateNotification(
            text = applicationContext.getString(R.string.youtube_upload_notification_uploading, fileName, percent),
            progress = percent,
        )
    }

    private fun finish(
        stage: YouTubeUploadStage,
        name: String,
        title: String? = null,
        videoId: String? = null,
        failure: String? = null,
    ): Result {
        postResult(resultText(stage, name, failure))
        val output = resultData(stage, title, videoId, failure)
        return if (stage == YouTubeUploadStage.FAILED) Result.failure(output) else Result.success(output)
    }

    private fun resultText(stage: YouTubeUploadStage, name: String, failure: String?): String {
        val context = applicationContext
        return when (stage) {
            YouTubeUploadStage.DONE -> context.getString(R.string.youtube_upload_notification_done, name)
            YouTubeUploadStage.UPLOADED -> context.getString(R.string.youtube_upload_notification_accepted, name)
            YouTubeUploadStage.ALREADY_UPLOADED -> context.getString(R.string.youtube_upload_notification_already, name)
            YouTubeUploadStage.FAILED ->
                context.getString(
                    R.string.youtube_upload_notification_failed,
                    context.getString(failureMessageRes(failure)),
                )
            YouTubeUploadStage.QUEUED,
            YouTubeUploadStage.UPLOADING,
            YouTubeUploadStage.PROCESSING,
            -> name
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String, progress: Int?) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(notificationId, buildNotification(text, ongoing = true, progress = progress))
    }

    @SuppressLint("MissingPermission")
    private fun postResult(text: String) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(notificationId + 1, buildNotification(text, ongoing = false, progress = null))
    }

    private fun foregroundInfo(text: String, progress: Int?): ForegroundInfo {
        val notification = buildNotification(text, ongoing = true, progress = progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean, progress: Int?): Notification {
        ensureChannel()
        val builder =
            NotificationCompat
                .Builder(applicationContext, YouTubeUploadWorkData.CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(applicationContext.getString(R.string.youtube_uploads_channel_name))
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress == INDETERMINATE) {
            builder.setProgress(0, 0, true)
        } else if (progress != null) {
            builder.setProgress(100, progress, false)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val channel =
            NotificationChannelCompat
                .Builder(YouTubeUploadWorkData.CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.youtube_uploads_channel_name))
                .build()
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
    }

    private fun progressData(stage: YouTubeUploadStage, name: String, percent: Int?): Data =
        Data
            .Builder()
            .putString(YouTubeUploadWorkData.KEY_STAGE, stage.name)
            .putString(YouTubeUploadWorkData.KEY_NAME, name)
            .putInt(YouTubeUploadWorkData.KEY_PERCENT, percent ?: -1)
            .build()

    private fun resultData(
        stage: YouTubeUploadStage,
        title: String?,
        videoId: String?,
        failure: String?,
    ): Data {
        val builder = Data.Builder().putString(YouTubeUploadWorkData.KEY_STAGE, stage.name)
        title?.let { builder.putString(YouTubeUploadWorkData.KEY_TITLE, it) }
        videoId?.let { builder.putString(YouTubeUploadWorkData.KEY_VIDEO_ID, it) }
        failure?.let { builder.putString(YouTubeUploadWorkData.KEY_FAILURE, it) }
        return builder.build()
    }

    /** A failed listing is retried by the next poll, so it is not an upload failure; cancellation still propagates. */
    private suspend fun <T> recoverFromFailure(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }

    private companion object {
        const val INDETERMINATE = -1
        const val PROCESSING_POLLS = 12
        const val POLL_INTERVAL_MS = 10_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface YouTubeUploadWorkerEntryPoint {
    fun uploads(): YouTubeUploadsRepository
}
