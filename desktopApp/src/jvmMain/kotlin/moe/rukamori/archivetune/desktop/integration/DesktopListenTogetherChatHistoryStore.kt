package moe.rukamori.archivetune.desktop.integration

import kotlin.coroutines.cancellation.CancellationException
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatHistoryStore
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherChatHistoryKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore

/**
 * The desktop chat-history store: the shared history JSON inside the vault settings, under the same document format
 * the Android DataStore key holds. A failed read reports and returns nothing, so a corrupt value never becomes a fake
 * empty history the owner would overwrite the real one with.
 */
internal class DesktopListenTogetherChatHistoryStore(
    private val settings: SettingsStore,
    private val log: (String) -> Unit,
) : ListenTogetherChatHistoryStore {
    override suspend fun read(): String? {
        val snapshot = settings.snapshots.value ?: run {
            log("Listen Together chat history could not be read")
            return null
        }
        return snapshot[ListenTogetherChatHistoryKey]
    }

    override suspend fun write(document: String?) {
        try {
            settings.edit { this[ListenTogetherChatHistoryKey] = document }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log("Listen Together chat history could not be saved")
        }
    }
}
