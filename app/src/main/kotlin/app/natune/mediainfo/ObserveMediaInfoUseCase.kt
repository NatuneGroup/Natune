package app.natune.mediainfo

import java.io.IOException
import kotlinx.coroutines.CancellationException

internal fun Throwable.asMediaInfoError(): MediaInfoState.Error {
    if (this is CancellationException) throw this
    return MediaInfoState.Error(
        if (this is IOException) MediaInfoError.Network else MediaInfoError.Unavailable,
    )
}
