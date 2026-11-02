package moe.rukamori.archivetune.shared.ui.screens.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.telegram.TelegramBotsOwner
import moe.rukamori.archivetune.shared.data.telegram.TelegramBrowseOwner
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotAddIssue
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotsState
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBrowseIssue
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBrowseState
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelInfo
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotNotice
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotsActions
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotsUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBrowseActions
import moe.rukamori.archivetune.shared.ui.screens.TelegramBrowseNotice
import moe.rukamori.archivetune.shared.ui.screens.TelegramBrowseUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramChannelUi

/**
 * Presents the real [TelegramBotsOwner] through the existing bots screen model. The owner keeps the saved list,
 * the pasted-link parsing, the resolve and duplicate rules, persistence and the forward choice. This binding only
 * maps the owner state to [TelegramBotsUi] and forwards each action. Back and bot-chat navigation stay with the
 * host, which may copy [actions] with its own navigation.
 *
 * Lifecycle: create one owner and one binding per route visit and signed-in account. Call [load] before the page
 * is shown. Until [loaded] is true the saved list and the forward choice are unknown, so they must not be shown as
 * an empty list. A failed read is not thrown: it sets [TelegramBotsUi.loadFailed], the screen shows it with a retry,
 * and [TelegramBotsActions.retry] reads again. Saved-list edits stay disabled until a read succeeds, so a list that
 * was never read cannot be saved over the stored one. Call [close] on route leave or account change. It closes the
 * owner, which cancels a pending add and the sign-in watch, and it drops every later callback. It never closes the
 * shared TDLib client, the store or the directory, and it does not cancel a channel sync that was already accepted.
 *
 * Use the binding only on the dispatcher of [scope], which must be the same UI dispatcher the owner uses.
 */
class TelegramBotsOwnerBinding(
    private val owner: TelegramBotsOwner,
    scope: CoroutineScope,
    private val onBack: () -> Unit,
    private val onOpenBot: (TelegramBotUi) -> Unit,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + job)
    private var closed = false
    private val loadLock = Mutex()
    private val loadedFlow = MutableStateFlow(false)
    private val loadFailedFlow = MutableStateFlow(false)

    /** True once [load] has read the saved bots and the forward choice from the store. */
    val loaded: StateFlow<Boolean> = loadedFlow.asStateFlow()

    /** The owner state as the bots screen model. The notice is the owner's last add outcome until dismissed. */
    val state: StateFlow<TelegramBotsUi> =
        combine(owner.state, loadFailedFlow, loadedFlow) { snapshot, failed, loaded -> snapshot.toUi(failed, loaded) }
            .stateIn(
                work,
                SharingStarted.Eagerly,
                owner.state.value.toUi(loadFailedFlow.value, loadedFlow.value),
            )

    /**
     * The existing screen actions. Pasted text goes to the owner unchanged, and the owner parses it. Opening a bot
     * needs a ready account and a read saved list. Adding, removing and the forward choice need a read saved list,
     * because the owner would otherwise save its empty in-memory list over the stored one. Every action is ignored
     * after [close].
     */
    val actions: TelegramBotsActions =
        TelegramBotsActions(
            back = { whileOpen(onBack) },
            input = { text -> whileOpen { owner.input(text) } },
            add = { text ->
                whileListLoaded {
                    owner.input(text)
                    owner.add()
                }
            },
            open = { bot -> whileListLoaded { if (owner.state.value.ready) onOpenBot(bot) } },
            remove = { bot -> whileListLoaded { owner.remove(bot.id) } },
            forwardToChannel = { enabled -> whileListLoaded { owner.setForwardToChannel(enabled) } },
            dismissNotice = { whileOpen { owner.dismissIssue() } },
            retry = { whileOpen { work.launch { load() } } },
        )

    /**
     * Reads the saved bots and the forward choice until one read succeeds. A failed read sets
     * [TelegramBotsUi.loadFailed] and returns normally, so the page can show it. Cancellation is rethrown, and a read
     * that finishes after [close] is dropped.
     */
    suspend fun load() {
        loadLock.withLock {
            if (closed || loadedFlow.value) return
            val failure: Exception? =
                try {
                    owner.load()
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (thrown: Exception) {
                    thrown
                }
            // A read that finishes after close belongs to a visit that no longer exists, so its result is dropped.
            if (closed) return
            loadFailedFlow.value = failure != null
            if (failure == null) loadedFlow.value = true
        }
    }

    /** Ends this visit. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        job.cancel()
        owner.close()
    }

    private fun whileOpen(action: () -> Unit) {
        if (!closed) action()
    }

    /** Saved-list edits wait for a successful read, so a list that was never read cannot be saved over the stored one. */
    private fun whileListLoaded(action: () -> Unit) {
        if (!closed && loadedFlow.value) action()
    }
}

/**
 * Presents the real [TelegramBrowseOwner] through the existing channel browse model. The owner runs the search and
 * the channel open. This binding maps the owner state to [TelegramBrowseUi] and forwards each action. The host
 * formats member counts through [formatMemberCount], so locale stays with the platform. Back and playlist
 * navigation stay with the host, which passes the playlist callback to the screen.
 *
 * Lifecycle: create one owner and one binding per route visit and signed-in account, and call [close] on route
 * leave or account change. [close] closes the owner, which cancels a running search or open. A channel sync that
 * the owner has already accepted keeps running, and this binding never cancels it. Every callback that arrives
 * after [close] is dropped, including the playlist navigation. The binding never closes the shared TDLib client or
 * the channel library.
 *
 * Use the binding only on the dispatcher of [scope], which must be the same UI dispatcher the owner uses.
 */
class TelegramBrowseOwnerBinding(
    private val owner: TelegramBrowseOwner,
    scope: CoroutineScope,
    private val formatMemberCount: (Int) -> String,
    private val onBack: () -> Unit,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + job)
    private var closed = false

    /** The owner state as the browse screen model. */
    val state: StateFlow<TelegramBrowseUi> =
        owner.state
            .map { snapshot -> snapshot.toUi(formatMemberCount) }
            .stateIn(work, SharingStarted.Eagerly, owner.state.value.toUi(formatMemberCount))

    /**
     * The current search results, in the same order as the rows in [state]. A platform avatar needs the
     * minithumbnail and photo id, which the screen model does not carry, so it looks them up here by chat id.
     */
    val channels: StateFlow<List<TelegramChannelInfo>> =
        owner.state
            .map { snapshot -> snapshot.results.distinctBy { it.chatId } }
            .stateIn(work, SharingStarted.Eagerly, owner.state.value.results.distinctBy { it.chatId })

    /**
     * The existing screen actions. A search runs on the query the owner holds, and the screen passes it trimmed.
     * Every action is ignored after [close].
     */
    val actions: TelegramBrowseActions =
        TelegramBrowseActions(
            back = { whileOpen(onBack) },
            query = { text -> whileOpen { owner.changeQuery(text) } },
            search = { text ->
                whileOpen {
                    owner.changeQuery(text)
                    owner.search()
                }
            },
            open = { channel, onOpened -> whileOpen { openChannel(channel, onOpened) } },
            dismissNotice = { whileOpen { owner.dismissIssue() } },
        )

    /** Ends this visit. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        job.cancel()
        owner.close()
    }

    private fun openChannel(channel: TelegramChannelUi, onOpened: (String) -> Unit) {
        // A row that a newer search has replaced is no longer on screen, so there is nothing to open.
        val info = owner.state.value.results.firstOrNull { it.chatId == channel.chatId } ?: return
        // The owner reports the playlist id only after the playlist exists and its sync was accepted.
        owner.open(info) { playlistId -> whileOpen { onOpened(playlistId) } }
    }

    private fun whileOpen(action: () -> Unit) {
        if (!closed) action()
    }
}

private fun TelegramBotsState.toUi(loadFailed: Boolean, loaded: Boolean): TelegramBotsUi =
    TelegramBotsUi(
        ready = ready,
        input = input,
        adding = adding,
        // Rows are keyed by bot id on screen, so a duplicated stored id is shown once instead of crashing the list.
        bots =
            bots
                .distinctBy { it.id }
                .map { bot -> TelegramBotUi(id = bot.id, username = bot.username, title = bot.title) },
        forwardToChannel = forwardToChannel,
        notice = issue?.toNotice(),
        loadFailed = loadFailed,
        loaded = loaded,
    )

private fun TelegramBotAddIssue.toNotice(): TelegramBotNotice =
    when (this) {
        TelegramBotAddIssue.InvalidUsername -> TelegramBotNotice.InvalidUsername
        is TelegramBotAddIssue.AlreadyAdded -> TelegramBotNotice.AlreadyAdded(username)
        TelegramBotAddIssue.ResolveFailed -> TelegramBotNotice.ResolveFailed
    }

private fun TelegramBrowseState.toUi(formatMemberCount: (Int) -> String): TelegramBrowseUi {
    val searchFailed = issue is TelegramBrowseIssue.SearchFailed
    return TelegramBrowseUi(
        query = query,
        searching = searching,
        // A failed search keeps its failure notice. It must not also read as a completed, empty search.
        searched = searched && !searchFailed,
        opening = opening,
        results = results.distinctBy { it.chatId }.map { channel -> channel.toUi(formatMemberCount) },
        notice = issue?.toNotice(),
    )
}

private fun TelegramChannelInfo.toUi(formatMemberCount: (Int) -> String): TelegramChannelUi =
    TelegramChannelUi(
        chatId = chatId,
        title = title,
        username = username,
        broadcast = isBroadcast,
        // Native metadata reports zero when the member count is unknown, and the row then shows no member line.
        membersText = if (memberCount > 0) formatMemberCount(memberCount) else null,
    )

private fun TelegramBrowseIssue.toNotice(): TelegramBrowseNotice =
    when (this) {
        is TelegramBrowseIssue.SearchFailed -> TelegramBrowseNotice.SearchFailed(detail)
        TelegramBrowseIssue.OpenFailed -> TelegramBrowseNotice.OpenFailed
    }
