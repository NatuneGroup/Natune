/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_OFF

internal enum class UserQueuedKind {
    PLAY_NEXT,
    ADD_TO_QUEUE,
}

internal const val EXTRA_USER_QUEUED_KIND = "archivetune.userQueuedKind"

internal fun MediaItem.userQueuedKind(): UserQueuedKind? =
    mediaMetadata.extras
        ?.getInt(EXTRA_USER_QUEUED_KIND, -1)
        ?.let { UserQueuedKind.entries.getOrNull(it) }

/** Marks an item as added by the user (Play next / Add to queue), so later adds line up behind it. */
internal fun MediaItem.markUserQueued(kind: UserQueuedKind): MediaItem {
    val extras = android.os.Bundle(mediaMetadata.extras ?: android.os.Bundle.EMPTY)
    extras.putInt(EXTRA_USER_QUEUED_KIND, kind.ordinal)
    return buildUpon()
        .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
        .build()
}

/**
 * The songs straight after the current one (in play order) that the user added by hand, up to
 * the first radio or autoplay song. Ended there, so earlier plays of the same queue never count.
 */
internal fun upcomingUserQueuedKinds(
    player: Player,
    currentIndex: Int,
): List<UserQueuedKind> {
    val timeline = player.currentTimeline
    if (timeline.isEmpty || currentIndex == C.INDEX_UNSET) return emptyList()
    val kinds = mutableListOf<UserQueuedKind>()
    var index = currentIndex
    while (true) {
        index = timeline.getNextWindowIndex(index, REPEAT_MODE_OFF, player.shuffleModeEnabled)
        if (index == C.INDEX_UNSET || index >= player.mediaItemCount) break
        kinds += player.getMediaItemAt(index).userQueuedKind() ?: break
    }
    return kinds
}

/**
 * How many of the upcoming songs, in play order, a new [adding] goes after.
 *
 * [upcoming] holds the kind of each hand-queued song right after the current one, ending at the
 * first song nobody queued by hand: the radio or autoplay tail is never skipped. The block reads
 * `[Play next…][Add to queue…]`, so Play next lines up behind earlier Play next songs but still
 * ahead of Add to queue songs, and Add to queue goes after the whole block (#172).
 */
internal fun userQueueSkipCount(
    upcoming: List<UserQueuedKind>,
    adding: UserQueuedKind,
): Int =
    when (adding) {
        UserQueuedKind.PLAY_NEXT -> upcoming.takeWhile { it == UserQueuedKind.PLAY_NEXT }.size
        UserQueuedKind.ADD_TO_QUEUE -> upcoming.size
    }
