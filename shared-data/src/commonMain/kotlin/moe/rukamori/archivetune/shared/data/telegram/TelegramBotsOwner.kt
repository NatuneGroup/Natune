/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBot
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotAddIssue
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotCodec
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotDirectory
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotProfile
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotStore
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotsState
import moe.rukamori.archivetune.shared.domain.telegram.parseTelegramBotUsername

/**
 * Owns the saved Telegram bot list: paste-a-link add, resolve, remove and the forward choice. Ported from the
 * Android bots screen. Every list change runs under one mutex, so an add that finishes during a remove neither
 * resurrects nor drops a bot. Add clears the input only after a bot is saved.
 *
 * Mutations run on the dispatcher of [scope]; the host passes its UI dispatcher. Storage failures are not hidden:
 * they propagate to [scope] rather than leaving a bot that looks saved.
 */
class TelegramBotsOwner(
    private val store: TelegramBotStore,
    private val directory: TelegramBotDirectory,
    ready: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val newId: () -> String,
) {
    private val published =
        MutableStateFlow(
            TelegramBotsState(
                ready = ready.value,
                input = "",
                adding = false,
                bots = emptyList(),
                forwardToChannel = true,
                issue = null,
            ),
        )
    val state: StateFlow<TelegramBotsState> = published.asStateFlow()

    private val commits = Mutex()
    private var adding: Job? = null
    private val readyWatch: Job =
        scope.launch { ready.collect { value -> published.update { it.copy(ready = value) } } }

    /** Loads the saved bots and forward choice. Call once before the bots screen reads [state]. */
    suspend fun load() {
        val bots = TelegramBotCodec.decode(store.readBots(), newId)
        val forward = store.readForwardToChannel()
        published.update { it.copy(bots = bots, forwardToChannel = forward) }
    }

    fun input(text: String) {
        published.update { it.copy(input = text) }
    }

    /** Resolves the pasted username and saves the bot. Ignored while signed out or while an add is running. */
    fun add() {
        val current = published.value
        if (!current.ready || current.adding) return
        val username = parseTelegramBotUsername(current.input)
        if (username == null) {
            published.update { it.copy(issue = TelegramBotAddIssue.InvalidUsername) }
            return
        }
        if (current.bots.any { it.username == username }) {
            published.update { it.copy(issue = TelegramBotAddIssue.AlreadyAdded(username)) }
            return
        }
        published.update { it.copy(adding = true, issue = null) }
        adding =
            scope.launch {
                val profile = resolveOrNull(username)
                if (profile == null) {
                    published.update { it.copy(adding = false, issue = TelegramBotAddIssue.ResolveFailed) }
                    return@launch
                }
                val bot =
                    TelegramBot(
                        id = newId(),
                        username = username,
                        chatId = profile.chatId,
                        title = profile.title?.takeIf { it.isNotBlank() } ?: "@$username",
                        addedAtMs = nowMs(),
                        photoMinithumbnail = profile.photoMinithumbnail,
                        photoFileId = profile.photoFileId,
                    )
                try {
                    commitBots { list -> if (list.any { it.username == username }) list else list + bot }
                } finally {
                    // A failed save must not leave the add spinning; the input stays so the user can retry.
                    published.update { it.copy(adding = false) }
                }
                published.update { it.copy(input = "") }
            }
    }

    fun remove(botId: String) {
        scope.launch { commitBots { list -> list.filterNot { it.id == botId } } }
    }

    fun setForwardToChannel(enabled: Boolean) {
        published.update { it.copy(forwardToChannel = enabled) }
        scope.launch { store.writeForwardToChannel(enabled) }
    }

    fun dismissIssue() {
        published.update { it.copy(issue = null) }
    }

    /** Cancels an add that is still resolving and stops watching sign-in state. */
    fun close() {
        adding?.cancel()
        readyWatch.cancel()
    }

    private suspend fun resolveOrNull(username: String): TelegramBotProfile? =
        try {
            directory.resolve(username)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unresolved: Exception) {
            null
        }

    private suspend fun commitBots(transform: (List<TelegramBot>) -> List<TelegramBot>) {
        commits.withLock {
            val updated = transform(published.value.bots)
            store.writeBots(TelegramBotCodec.encode(updated))
            published.update { it.copy(bots = updated) }
        }
    }
}
