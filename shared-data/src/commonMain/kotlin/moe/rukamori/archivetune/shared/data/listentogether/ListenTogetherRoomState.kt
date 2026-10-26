/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.listentogether

// Pure reducers for the room snapshot the server sends. They hold no state of their own, so the client applies
// them under its serial scope and tests can exercise them without a socket.

internal fun RoomState.withUserJoined(payload: UserJoinedPayload): RoomState =
    copy(
        users = users + UserInfo(
            userId = payload.userId,
            username = payload.username,
            isHost = false,
            avatarIndex = payload.avatarIndex,
        ),
    )

internal fun RoomState.withoutUser(userId: String): RoomState =
    copy(users = users.filter { it.userId != userId })

internal fun RoomState.withHost(newHostId: String): RoomState =
    copy(
        hostId = newHostId,
        users = users.map { it.copy(isHost = it.userId == newHostId) },
    )

/** A reconnect or a disconnect only flips the presence flag; the member stays listed while away. */
internal fun RoomState.withConnection(userId: String, connected: Boolean): RoomState =
    copy(users = users.map { if (it.userId == userId) it.copy(isConnected = connected) else it })

/**
 * Applies a host playback action to the snapshot a guest keeps. Actions that only move the local player, such as
 * skip, leave the snapshot unchanged, so the client does not invent a track position the server never sent.
 */
internal fun RoomState.withPlayback(action: PlaybackActionPayload): RoomState =
    when (action.action) {
        PlaybackActions.PLAY -> copy(isPlaying = true, position = action.position ?: position)
        PlaybackActions.PAUSE -> copy(isPlaying = false, position = action.position ?: position)
        PlaybackActions.SEEK -> copy(position = action.position ?: position)
        // A track change always starts paused at zero; the host sends play once the room buffers.
        PlaybackActions.CHANGE_TRACK -> copy(currentTrack = action.trackInfo, isPlaying = false, position = 0L)
        PlaybackActions.QUEUE_ADD -> action.trackInfo?.let { track ->
            copy(queue = if (action.insertNext == true) listOf(track) + queue else queue + track)
        } ?: this
        PlaybackActions.QUEUE_REMOVE -> action.trackId?.takeIf { it.isNotEmpty() }?.let { id ->
            copy(queue = queue.filter { it.id != id })
        } ?: this
        PlaybackActions.QUEUE_CLEAR -> copy(queue = emptyList())
        // Volume is clamped here, not trusted, because a malformed host value must not amplify a guest's output.
        PlaybackActions.SET_VOLUME -> action.volume?.let { copy(volume = it.coerceIn(0f, 1f)) } ?: this
        else -> this
    }
