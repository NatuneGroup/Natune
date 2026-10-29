package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramBotDirectory
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramBotStore
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramChannelLibrary
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramChannelSearch
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramClient
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramNativeAdmission
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.telegram.TelegramBotsOwner
import moe.rukamori.archivetune.shared.data.telegram.TelegramBrowseOwner
import moe.rukamori.archivetune.shared.domain.settings.TelegramLosslessOnlyKey
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelInfo
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.TelegramChannelUi
import moe.rukamori.archivetune.shared.ui.screens.telegram.TelegramBotsOwnerBinding
import moe.rukamori.archivetune.shared.ui.screens.telegram.TelegramBrowseOwnerBinding
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSUUID

/**
 * The Telegram channel browse page over the signed-in iOS client. Without a client the route keeps the host boundary,
 * so a search never looks live while no account is ready. The client is the key, so each account gets its own owner.
 */
@Composable
internal fun iosTelegramBrowsePage(graph: IosSessionGraph, host: IosHost, back: () -> Unit): ArchiveGraphPage {
    val client = host.telegram.collectAsState().value ?: return ArchiveGraphPage.Host
    return key(client) { iosTelegramBrowseMount(graph, client, back) }
}

@Composable
private fun iosTelegramBrowseMount(graph: IosSessionGraph, client: IosTelegramClient, back: () -> Unit): ArchiveGraphPage {
    // The binding must run on the UI dispatcher, and the composition scope is that dispatcher. The graph scope is not.
    val scope = rememberCoroutineScope()
    val binding = remember(client, scope, back) {
        TelegramBrowseOwnerBinding(
            owner = TelegramBrowseOwner(
                channelSearch = IosTelegramChannelSearch(client),
                channelLibrary = IosTelegramChannelLibrary(),
                losslessOnly = { graph.settings.snapshots.value?.get(TelegramLosslessOnlyKey) ?: false },
                scope = scope,
            ),
            scope = scope,
            // Plain digits until the iOS host has a locale-aware count formatter. The desktop host formats with NumberFormat.
            formatMemberCount = { count -> count.toString() },
            onBack = back,
        )
    }
    DisposableEffect(binding) { onDispose { binding.close() } }
    return ArchiveGraphPage.TelegramBrowse(
        state = binding.state.collectAsState().value,
        actions = binding.actions,
        avatar = { channel, modifier -> iosTelegramChannelAvatar(channel, binding.channels, modifier) },
    )
}

/**
 * The saved Telegram bot list over the signed-in iOS client, gated the same way as browse. Opening a bot goes to its
 * chat route, which stays the host boundary on this host, so that route says so instead of showing an empty page.
 */
@Composable
internal fun iosTelegramBotsPage(
    graph: IosSessionGraph,
    host: IosHost,
    back: () -> Unit,
    openRoute: (String) -> Unit,
): ArchiveGraphPage {
    val client = host.telegram.collectAsState().value ?: return ArchiveGraphPage.Host
    return key(client) { iosTelegramBotsMount(graph, client, back, openRoute) }
}

@Composable
private fun iosTelegramBotsMount(
    graph: IosSessionGraph,
    client: IosTelegramClient,
    back: () -> Unit,
    openRoute: (String) -> Unit,
): ArchiveGraphPage {
    val scope = rememberCoroutineScope()
    val binding = remember(client, scope, back, openRoute) {
        val ready = client.admission
            .map { admission -> admission is IosTelegramNativeAdmission.Admitted }
            .stateIn(scope, SharingStarted.Eagerly, client.admission.value is IosTelegramNativeAdmission.Admitted)
        TelegramBotsOwnerBinding(
            owner = TelegramBotsOwner(
                store = IosTelegramBotStore(graph.settings),
                directory = IosTelegramBotDirectory(client),
                ready = ready,
                scope = scope,
                nowMs = ::iosNowMillis,
                newId = { NSUUID().UUIDString },
            ),
            scope = scope,
            onBack = back,
            onOpenBot = { bot -> openRoute("telegram/bot/${bot.id}") },
        )
    }
    DisposableEffect(binding) {
        val load = scope.launch { binding.load() }
        onDispose {
            load.cancel()
            binding.close()
        }
    }
    return ArchiveGraphPage.TelegramBots(binding.state.collectAsState().value, binding.actions)
}

/**
 * A channel's minithumbnail when TDLib sent one, else the letter tile. The search rows carry no picture bytes, so the
 * picture is looked up by chat id. A missing or unreadable picture is the tile, never a failed row.
 */
@Composable
private fun iosTelegramChannelAvatar(
    channel: TelegramChannelUi,
    channels: StateFlow<List<TelegramChannelInfo>>,
    modifier: Modifier,
) {
    val infos by channels.collectAsState()
    val bitmap = remember(infos, channel.chatId) {
        iosDecodeMinithumbnail(infos.firstOrNull { it.chatId == channel.chatId }?.photoMinithumbnail)
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = channel.title, modifier = modifier)
    } else {
        IosLetterAvatar(channel.title, modifier)
    }
}

/** Decodes a TDLib minithumbnail. A payload Skia cannot read becomes null here, so one bad picture cannot fail the page. */
private fun iosDecodeMinithumbnail(bytes: ByteArray?): ImageBitmap? =
    bytes?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
