package moe.rukamori.archivetune.shared.data.platform.ios

import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException

internal data class IosResponseByteRange(val endExclusive: Long, val totalBytes: Long) {
    val hasMoreBytes: Boolean get() = totalBytes < 0 || endExclusive < totalBytes
}

internal fun parseIosResponseByteRange(value: String?, position: Long): IosResponseByteRange {
    val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)", RegexOption.IGNORE_CASE)
        .matchEntire(value?.trim().orEmpty())
        ?: throw ByteSourceException("the partial response has a malformed byte range")
    val first = match.groupValues[1].toLongOrNull()
    val last = match.groupValues[2].toLongOrNull()
    val total = match.groupValues[3].let { if (it == "*") -1L else it.toLongOrNull() }
    if (first != position || last == null || last < position || last == Long.MAX_VALUE ||
        total == null || (total >= 0 && last >= total)
    ) throw ByteSourceException("the partial response has an invalid byte range")
    return IosResponseByteRange(last + 1, total)
}
