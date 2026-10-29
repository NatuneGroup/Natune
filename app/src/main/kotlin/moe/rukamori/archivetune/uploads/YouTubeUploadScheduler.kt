package moe.rukamori.archivetune.uploads

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadFailure
import java.util.UUID

/** Keys and the tag shared by the scheduler, the worker and the Uploads screen. */
internal object YouTubeUploadWorkData {
    const val TAG = "youtube_uploads"

    /** Carries the file name on the work itself, because queued work has no progress data to show yet. */
    const val NAME_TAG_PREFIX = "youtube_upload_name:"
    const val CHANNEL_ID = "youtube_uploads"
    const val KEY_URI = "uri"
    const val KEY_NAME = "name"
    const val KEY_STAGE = "stage"
    const val KEY_PERCENT = "percent"
    const val KEY_TITLE = "title"
    const val KEY_VIDEO_ID = "video_id"
    const val KEY_FAILURE = "failure"

    /** Failure name for a picked document the provider would not open or size. */
    const val FAILURE_UNREADABLE = "UNREADABLE"
}

/** Where one upload is. Stored by name in work data, so renaming a constant is a stored-state change. */
enum class YouTubeUploadStage {
    QUEUED,
    UPLOADING,
    PROCESSING,

    /** The song is listed in the uploads shelf now. */
    DONE,

    /** YouTube Music accepted the bytes but the song is not listed yet. */
    UPLOADED,
    ALREADY_UPLOADED,
    FAILED,
}

/**
 * Starts uploads as expedited work, so a user-started transfer keeps running with a foreground
 * notification and is not deferred behind battery-saver scheduling.
 */
object YouTubeUploadScheduler {
    fun enqueue(context: Context, uri: Uri, fileName: String): UUID {
        val request =
            OneTimeWorkRequestBuilder<YouTubeUploadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(
                    workDataOf(
                        YouTubeUploadWorkData.KEY_URI to uri.toString(),
                        YouTubeUploadWorkData.KEY_NAME to fileName,
                    ),
                ).addTag(YouTubeUploadWorkData.TAG)
                .addTag(YouTubeUploadWorkData.NAME_TAG_PREFIX + fileName)
                .build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    fun observe(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(YouTubeUploadWorkData.TAG)
}

/** The string shown for one failure. Upload and delete failures are reported, never hidden. */
internal fun YouTubeUploadFailure.messageRes(): Int =
    when (this) {
        YouTubeUploadFailure.SIGN_IN_REQUIRED -> R.string.youtube_upload_error_sign_in
        YouTubeUploadFailure.UNSUPPORTED_TYPE -> R.string.youtube_upload_error_unsupported
        YouTubeUploadFailure.EMPTY_FILE -> R.string.youtube_upload_error_empty
        YouTubeUploadFailure.TOO_LARGE -> R.string.youtube_upload_error_too_large
        YouTubeUploadFailure.HTTP -> R.string.youtube_upload_error_http
        YouTubeUploadFailure.UNKNOWN -> R.string.youtube_upload_error_unknown
    }

/** Maps the failure name stored in work data, which is either a [YouTubeUploadFailure] or the unreadable marker. */
internal fun failureMessageRes(stored: String?): Int =
    when (stored) {
        YouTubeUploadWorkData.FAILURE_UNREADABLE -> R.string.youtube_upload_read_failed
        null -> R.string.youtube_upload_error_unknown
        else ->
            runCatching { YouTubeUploadFailure.valueOf(stored) }
                .getOrNull()
                ?.messageRes()
                ?: R.string.youtube_upload_error_unknown
    }
