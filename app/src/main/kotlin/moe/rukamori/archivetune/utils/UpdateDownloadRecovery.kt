package moe.rukamori.archivetune.utils

/** A missing asset may be retried once, using a different eligible offer on the same selection. */
internal fun replacementDownloadOffer(
    statusCode: Int,
    failedOffer: UpdateOffer,
    refreshedOffer: UpdateOffer?,
    selectionIsCurrent: Boolean,
    alreadyRetried: Boolean,
): UpdateOffer? {
    if (statusCode != 404 && statusCode != 410) return null
    if (alreadyRetried || !selectionIsCurrent) return null
    val fresh = refreshedOffer ?: return null
    if (fresh.channel != failedOffer.channel || fresh.downloadUrl.isBlank()) return null
    return fresh.takeIf { it.identity != failedOffer.identity || it.downloadUrl != failedOffer.downloadUrl }
}
