package moe.rukamori.archivetune.shared.data.repository

import io.ktor.client.plugins.ResponseException

internal fun Throwable.httpStatusOrNull(): Int? {
    var throwable: Throwable? = this
    while (throwable != null) {
        if (throwable is ResponseException) return throwable.response.status.value
        throwable = throwable.cause
    }
    return null
}
