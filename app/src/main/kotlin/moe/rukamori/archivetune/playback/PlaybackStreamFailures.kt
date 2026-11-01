package moe.rukamori.archivetune.playback

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.IOException

internal fun findStreamHttpFailure(error: PlaybackException): HttpDataSource.InvalidResponseCodeException? {
    var throwable: Throwable? = error.cause
    while (throwable != null) {
        if (throwable is HttpDataSource.InvalidResponseCodeException) return throwable
        throwable = throwable.cause
    }
    return null
}

internal fun isRetryableRemoteParserFailure(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    ) return true

    var throwable: Throwable? = error.cause
    while (throwable != null) {
        if (throwable.message?.contains("Skipping atom with length", ignoreCase = true) == true ||
            throwable.isMedia3ExtractorBoundsFailure()
        ) return true
        throwable = throwable.cause
    }
    return false
}

/** URL rejection and damaged cached bytes have different invalidation rules. Keep this classification unchanged. */
internal fun isCacheCorruptionError(error: PlaybackException, isContentCached: Boolean): Boolean {
    val isIoError =
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
    val isContainerParseError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
    if (!isIoError && !isContainerParseError) return false

    var throwable: Throwable? = error.cause
    while (throwable != null) {
        when {
            throwable is EOFException -> return true
            throwable is IOException &&
                throwable.message?.contains("unexpected end of stream", ignoreCase = true) == true -> return true
            throwable is IllegalStateException || throwable is IllegalArgumentException -> {
                if (throwable.stackTrace.any { it.className.startsWith("androidx.media3.extractor") }) return true
            }
            isContentCached && throwable.isMedia3ExtractorBoundsFailure() -> return true
            isContainerParseError && isContentCached && throwable is ParserException -> return true
            isContainerParseError && isContentCached &&
                throwable.message?.let {
                    it.contains("Invalid integer size", ignoreCase = true) ||
                        it.contains("Skipping atom with length", ignoreCase = true) ||
                        it.contains("contentIsMalformed=true", ignoreCase = true)
                } == true -> return true
        }
        throwable = throwable.cause
    }
    return false
}

private fun Throwable.isMedia3ExtractorBoundsFailure(): Boolean =
    this is ArrayIndexOutOfBoundsException &&
        stackTrace.any { it.className.startsWith("androidx.media3.extractor") }
