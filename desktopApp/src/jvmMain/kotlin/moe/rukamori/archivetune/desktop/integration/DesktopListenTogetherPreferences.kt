/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherPreferences
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherAutoApprovalKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSettingsDefaults
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSuggestionAutoApproveKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherAvatarIndex
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherServerUrl

/**
 * The Listen Together preference values that the shared client reads synchronously. They come from the same logical
 * settings owner as the reconnect session, so the host passes the one logical store to both adapters.
 *
 * The client filters the saved server through its own allow-list, which is why the stored URL is returned as saved.
 * The host opens the settings owner before it builds the client, the same ordering [DesktopListenTogetherStore] needs.
 * Until a snapshot exists, each value answers with its baseline default, so a read that arrives first cannot invent a
 * different policy.
 */
internal class DesktopListenTogetherPreferences(
    private val settings: SettingsStore,
) : ListenTogetherPreferences {

    override fun serverUrl(): String =
        settings.snapshots.value?.listenTogetherServerUrl(ListenTogetherServers.defaultServerUrl)
            ?: ListenTogetherServers.defaultServerUrl

    override fun autoApproveJoinRequests(): Boolean =
        settings.snapshots.value?.get(
            ListenTogetherAutoApprovalKey,
            ListenTogetherSettingsDefaults.AUTO_APPROVAL,
        ) ?: ListenTogetherSettingsDefaults.AUTO_APPROVAL

    override fun autoApproveSuggestions(): Boolean =
        settings.snapshots.value?.get(
            ListenTogetherSuggestionAutoApproveKey,
            ListenTogetherSettingsDefaults.SUGGESTION_AUTO_APPROVE,
        ) ?: ListenTogetherSettingsDefaults.SUGGESTION_AUTO_APPROVE

    override fun avatarIndex(): Int =
        settings.snapshots.value?.listenTogetherAvatarIndex() ?: ListenTogetherSettingsDefaults.AVATAR_INDEX
}
