package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.StaticBusyFraction
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the Cast menu needs from the host's Cast session. It mirrors the session state and carries no Cast SDK type, so
 * the common code never touches the GMS SDK.
 */
@Immutable
data class CastMenuState(
    /** False when casting cannot be used on this device. The Cast row is then left out. */
    val isAvailable: Boolean,
    val isConnected: Boolean,
    val connectedDeviceName: String?,
)

/**
 * One route the host discovered. [id] is the host's own identity and comes back to the host unchanged when the route is
 * picked.
 */
@Immutable
data class CastRouteItem(
    val id: String,
    val name: String,
    val description: String?,
    val selected: Boolean,
    val enabled: Boolean,
    val connecting: Boolean,
)

/**
 * The route list the picker shows. The host runs discovery and reports one of these states; the picker never polls on
 * its own.
 */
sealed interface CastRoutePickerState {
    data object Loading : CastRoutePickerState

    data object Empty : CastRoutePickerState

    data class Error(
        val message: StringResource,
    ) : CastRoutePickerState

    data class Success(
        val routes: List<CastRouteItem>,
    ) : CastRoutePickerState
}

/** The Cast row of the player menu. Its icon follows the connection, and a tap asks the host to open the picker. */
data class CastMenuAction(
    val icon: DrawableResource,
    val label: StringResource,
    val onClick: () -> Unit,
)

/**
 * Maps the Cast session to the player row. Returns null while the session is unknown or when casting is unavailable, so
 * the row is left out.
 */
fun castMenuAction(
    state: CastMenuState?,
    onOpenPicker: () -> Unit,
): CastMenuAction? {
    if (state == null || !state.isAvailable) return null
    return CastMenuAction(
        icon = if (state.isConnected) Res.drawable.m6_icon_cast_connected else Res.drawable.m6_icon_cast,
        label = Res.string.m6_cast,
        onClick = onOpenPicker,
    )
}

/**
 * The Cast route picker as a bottom sheet. The host starts discovery in [onStartDiscovery] when the sheet opens and stops
 * it in [onStopDiscovery] when the sheet leaves composition. A pick calls [onRouteSelected] and a disconnect calls
 * [onDisconnect]; the host closes the sheet once it has accepted the pick. The Android nearby-devices permission request
 * belongs to the host because it needs an Activity.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CastRoutePickerSheet(
    castState: CastMenuState,
    routePickerState: CastRoutePickerState,
    onDismissRequest: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onRouteSelected: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    DisposableEffect(onStartDiscovery, onStopDiscovery) {
        onStartDiscovery()
        onDispose(onStopDiscovery)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CastRoutePickerHeader(onDismissRequest = onDismissRequest)

                CastRoutePickerStatus(
                    castState = castState,
                    routePickerState = routePickerState,
                    onDisconnect = onDisconnect,
                )

                CastRoutePickerContent(
                    routePickerState = routePickerState,
                    onRouteSelected = onRouteSelected,
                )
            }
        }
    }
}

@Composable
private fun CastRoutePickerHeader(onDismissRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                painter = painterResource(Res.drawable.m6_icon_cast),
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(12.dp)
                        .size(28.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(Res.string.m6_cast_devices),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.m6_cast_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalIconButton(onClick = onDismissRequest) {
            Icon(
                painter = painterResource(Res.drawable.m6_icon_close),
                contentDescription = stringResource(Res.string.m6_cast_close),
            )
        }
    }
}

@Composable
private fun CastRoutePickerStatus(
    castState: CastMenuState,
    routePickerState: CastRoutePickerState,
    onDisconnect: () -> Unit,
) {
    val connectedDeviceName = castState.connectedDeviceName

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (castState.isConnected) Res.drawable.m6_icon_cast_connected else Res.drawable.m6_icon_cast,
                    ),
                contentDescription = null,
                tint =
                    if (castState.isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text =
                        if (castState.isConnected && connectedDeviceName != null) {
                            stringResource(Res.string.m6_cast_connected_to, connectedDeviceName)
                        } else {
                            routePickerState.statusText()
                        },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (castState.isConnected) {
                TextButton(onClick = onDisconnect) {
                    Text(text = stringResource(Res.string.m6_cast_disconnect))
                }
            }
        }
    }
}

@Composable
private fun CastRoutePickerContent(
    routePickerState: CastRoutePickerState,
    onRouteSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.m6_cast_available_devices),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        when (routePickerState) {
            CastRoutePickerState.Loading -> CastRoutePickerLoading()
            CastRoutePickerState.Empty -> CastRoutePickerEmpty()
            is CastRoutePickerState.Error -> CastRoutePickerError(message = routePickerState.message)
            is CastRoutePickerState.Success ->
                CastRoutePickerRouteList(
                    routes = routePickerState.routes,
                    onRouteSelected = onRouteSelected,
                )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CastRoutePickerRouteList(
    routes: List<CastRouteItem>,
    onRouteSelected: (String) -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(
            items = routes,
            key = { _, route -> route.id },
            contentType = { _, _ -> "cast_route_device" },
        ) { index, route ->
            val routeClick = remember(route.id, onRouteSelected) { { onRouteSelected(route.id) } }
            CastRouteRow(
                route = route,
                index = index,
                count = routes.size,
                onClick = routeClick,
            )
        }
    }
}

@Composable
private fun CastRoutePickerError(message: StringResource) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.m6_icon_cast),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CastRoutePickerLoading() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 156.dp)
                    .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (motionAllowed()) {
                LoadingIndicator(modifier = Modifier.size(44.dp))
            } else {
                CircularProgressIndicator(progress = { StaticBusyFraction }, modifier = Modifier.size(44.dp))
            }
            Text(
                text = stringResource(Res.string.m6_cast_searching_devices),
                modifier = Modifier.padding(top = 18.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.m6_cast_no_devices_desc),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CastRoutePickerEmpty() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 156.dp)
                    .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.m6_icon_cast),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(38.dp),
            )
            Text(
                text = stringResource(Res.string.m6_cast_no_devices),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.m6_cast_no_devices_desc),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CastRouteRow(
    route: CastRouteItem,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        selected = route.selected,
        onClick = onClick,
        enabled = route.enabled && !route.connecting,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors =
            ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color =
                    if (route.selected) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (route.selected) {
                        MaterialTheme.colorScheme.onSecondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m6_icon_cast),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .padding(10.dp)
                            .size(22.dp),
                )
            }
        },
        trailingContent = {
            if (route.connecting) {
                if (motionAllowed()) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    CircularProgressIndicator(progress = { StaticBusyFraction }, modifier = Modifier.size(24.dp))
                }
            } else if (route.selected) {
                Icon(
                    painter = painterResource(Res.drawable.m6_icon_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        supportingContent = {
            Text(
                text = routeSupportingText(route),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Text(
            text = route.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun routeSupportingText(route: CastRouteItem): String {
    val description = route.description
    return when {
        route.selected -> stringResource(Res.string.m6_cast_connected)
        route.connecting -> stringResource(Res.string.m6_cast_connecting)
        description != null -> description
        else -> stringResource(Res.string.m6_cast_available_device)
    }
}

@Composable
private fun CastRoutePickerState.statusText(): String =
    when (this) {
        CastRoutePickerState.Loading -> stringResource(Res.string.m6_cast_searching_devices)
        CastRoutePickerState.Empty -> stringResource(Res.string.m6_cast_no_devices)
        is CastRoutePickerState.Error -> stringResource(message)
        is CastRoutePickerState.Success -> stringResource(Res.string.m6_cast_available_device_count, routes.size)
    }
