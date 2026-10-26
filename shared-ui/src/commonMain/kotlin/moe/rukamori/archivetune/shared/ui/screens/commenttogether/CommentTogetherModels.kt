/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.screens.commenttogether

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.data.listentogether.RepliedMessage
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo

/*
 * Public contract of the Listen Together "comment together" chat screen, ported from the Android
 * CommentTogetherScreen. The screen owns only presentation state (scroll position, the anchored
 * action popup, sheets and the jump highlight). Room, chat, song-search, clipboard and playback
 * effects all leave through [CommentTogetherActions]; the host maps its session state into
 * [CommentTogetherUi] and performs the effects, so nothing here fakes a success.
 */

/** Author of a chat message or a typing member. The host decides [own] and [host] from its session. */
data class CommentAuthorUi(
    val userId: String,
    val username: String,
    /** True when the local user wrote this (matched by user id, with the username as the restored-history fallback). */
    val own: Boolean,
    /** True when this author is the room host. */
    val host: Boolean,
)

/** One reaction chip. [mine] is true when the local user already applied [emoji]. */
data class CommentReactionUi(val emoji: String, val count: Int, val mine: Boolean)

/**
 * One chat row. [id] must be unique and stable for the whole session: it keys the list, the pinned
 * carousel and jump-to-message, so it must never be a list index.
 */
data class CommentMessageUi(
    val id: String,
    val author: CommentAuthorUi,
    val text: String,
    /** Host-formatted local clock text, for example "14:05". */
    val timeText: String,
    /** Epoch milliseconds of the message. */
    val timestamp: Long,
    val pinned: Boolean,
    /** Epoch milliseconds of the most recent pin, or 0 when unknown. The latest pin is shown first. */
    val pinnedAt: Long,
    val edited: Boolean,
    /** Tombstone: the row stays as "This message was deleted" and has no reactions or actions. */
    val deleted: Boolean,
    /** True for history restored at startup. The older-messages divider follows the last restored row. */
    val restored: Boolean,
    val replyTo: RepliedMessage?,
    val sharedTrack: TrackInfo?,
    val reactions: List<CommentReactionUi>,
)

/** An edit in progress: the message being edited and its original text (shown in the composer strip). */
data class CommentEditTargetUi(val messageId: String, val originalText: String)

/**
 * The composer draft. [replyTo] and [editing] may both be set; an edit takes precedence, as on Android,
 * and a send while editing carries the edit id and no reply target. The screen mirrors [draft] into its
 * text field, so the host must publish every [CommentTogetherActions.draftChanged] value back in order.
 */
data class CommentComposerUi(
    val draft: String,
    val replyTo: RepliedMessage?,
    val editing: CommentEditTargetUi?,
)

/** A composer submission. [editMessageId] is non-null only when submitting an edit of an existing message. */
data class CommentSendUi(val text: String, val replyTo: RepliedMessage?, val editMessageId: String?)

/** One-shot feedback the host raises after the real effect succeeded. The screen shows it and then calls [CommentTogetherActions.noticeShown]. */
sealed interface CommentNotice {
    /** The message text was placed on the clipboard. */
    data object MessageCopied : CommentNotice

    /** A shared song was started in the room. */
    data object SongPlayed : CommentNotice

    /** A share-current request arrived while nothing playable is loaded. */
    data object NothingPlaying : CommentNotice
}

/** Everything the chat screen renders. Built by the host from its room and chat state. */
data class CommentTogetherUi(
    /** Live room or session generation (not the reusable room code). A change rebuilds the screen state. */
    val sessionKey: String,
    /** Room code shown under the title, or null when not in a room. */
    val roomCode: String?,
    /** Messages in display order, oldest first. Ids must be unique. */
    val messages: List<CommentMessageUi>,
    /** Remote members currently typing. The local user must not be listed. */
    val typing: List<CommentAuthorUi>,
    val composer: CommentComposerUi,
    /** False when the server carries no chat (protobuf-only). The composer is replaced by an explanatory notice. */
    val supported: Boolean,
    /** Whether the local user hosts the room. Enables room-wide delete on other people's messages. */
    val localHost: Boolean,
    /** The room's current track, falling back to the local player's. Null hides the now-playing share row. */
    val currentTrack: TrackInfo?,
    /** Pending feedback. The screen acknowledges it with [CommentTogetherActions.noticeShown]. */
    val notice: CommentNotice?,
)

/**
 * Host-owned effects. Every member is required, because a missing effect would silently do nothing.
 * Calls that can fail report failure through their own result (see [searchSongs]); nothing reports success
 * on the host's behalf.
 */
data class CommentTogetherActions(
    val back: () -> Unit,
    /** Reports whether the chat is on screen, which suppresses chat notifications while it is visible. */
    val screenVisible: (Boolean) -> Unit,
    /** The message list changed; the host should mark the newest messages read. */
    val markRead: () -> Unit,
    /** The composer text changed. The host stores the draft (it owns it, the screen does not). */
    val draftChanged: (String) -> Unit,
    /** The draft became non-blank; the host may broadcast a typing indicator (it owns throttling). */
    val notifyTyping: () -> Unit,
    /** Submit the draft. The host clears the draft and reply or edit target when it accepts the submission. */
    val send: (CommentSendUi) -> Unit,
    /** Cancel the reply or edit. Cancelling an edit also clears the draft text, as on Android. */
    val cancelCompose: () -> Unit,
    /** Set the reply target to the message with [messageId]. */
    val reply: (messageId: String) -> Unit,
    /** Start editing the message with [messageId]; the host seeds the draft with its text and clears any reply target. */
    val edit: (messageId: String) -> Unit,
    /** Delete a message, for everyone in the room or only for the local user. */
    val delete: (messageId: String, forEveryone: Boolean) -> Unit,
    /** Pin or unpin a message. */
    val setPinned: (messageId: String, pinned: Boolean) -> Unit,
    /** Toggle [emoji] on the message with [messageId] (the host decides add versus remove). */
    val react: (messageId: String, emoji: String) -> Unit,
    /** Copy a message's text to the clipboard. The host raises [CommentNotice.MessageCopied] once the copy succeeds. */
    val copyText: (String) -> Unit,
    /** Start a shared song in the room. The host raises [CommentNotice.SongPlayed] once it is started. */
    val playShared: (TrackInfo) -> Unit,
    /** Share a song into the chat. */
    val shareTrack: (TrackInfo) -> Unit,
    /** Share the room's (or the local player's) current song at the moment of the request. Raises [CommentNotice.NothingPlaying] when nothing is playable. */
    val shareCurrent: () -> Unit,
    /** Search songs for the share picker. A failed search is returned as [Result.failure] and shown as an error. */
    val searchSongs: suspend (query: String) -> Result<List<TrackInfo>>,
    /** Open a link from a message. The host decides between an in-app route and the system handler. */
    val openLink: (String) -> Unit,
    /** The screen has shown the pending [CommentTogetherUi.notice]. The host clears it. */
    val noticeShown: () -> Unit,
)

/** Renders a message author's avatar art (custom picture, avatar index or initial). The host owns the art and its loading. */
typealias CommentAvatar = @Composable (CommentAuthorUi, Modifier) -> Unit

/** Renders a track thumbnail from a URL (null when unknown). The host owns image loading and caching. */
typealias CommentThumbnail = @Composable (String?, Modifier) -> Unit
