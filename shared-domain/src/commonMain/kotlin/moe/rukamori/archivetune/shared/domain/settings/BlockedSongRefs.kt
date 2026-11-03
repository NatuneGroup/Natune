package moe.rukamori.archivetune.shared.domain.settings

/** A preference string stays bounded; the baseline table had no cap and a pathological block list is not one either. */
const val MAX_BLOCKED_SONG_REFS = 512

/**
 * One provider song the user asked recommendations to stop offering, the portable counterpart of the
 * baseline's `song.blockedAt` timestamp on a stored song row. The list lives in a preference rather
 * than a library table, so a host without the library store keeps it and a SETTINGS backup carries it.
 */
data class BlockedSongRef(
    val provider: String,
    val id: String,
) {
    fun encode(): String = "$provider:$id"

    companion object {
        /** The provider prefix ends at the first colon, so a provider-scoped id keeps any colons it carries. */
        fun decode(raw: String): BlockedSongRef? {
            val trimmed = raw.trim()
            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 0) return null
            val provider = trimmed.substring(0, separatorIndex)
            val id = trimmed.substring(separatorIndex + 1).trim()
            if (id.isEmpty()) return null
            return BlockedSongRef(provider, id)
        }
    }
}

/** Reads the stored block list; malformed entries drop out like a bad speed-dial pin does. */
fun parseBlockedSongRefs(
    raw: String,
    maxItems: Int = MAX_BLOCKED_SONG_REFS,
): List<BlockedSongRef> =
    raw
        .split(",")
        .mapNotNull(BlockedSongRef::decode)
        .distinct()
        .take(maxItems)

fun serializeBlockedSongRefs(refs: List<BlockedSongRef>): String = refs.joinToString(",") { it.encode() }

/**
 * The idempotent desired-state edit the pin row makes: unblocking drops the ref, blocking adds it once, and a full
 * list refuses instead of silently dropping an older block. Null means the list was already full.
 */
fun toggleBlockedSongRef(
    refs: List<BlockedSongRef>,
    ref: BlockedSongRef,
    blocked: Boolean,
    maxItems: Int = MAX_BLOCKED_SONG_REFS,
): List<BlockedSongRef>? {
    if (!blocked) return refs.filterNot { it == ref }
    if (ref in refs) return refs
    if (refs.size >= maxItems) return null
    return refs + ref
}
