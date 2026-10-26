/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.allowsSortDirection
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The sort control of a Library list: a leading dropdown for the sort type and, where the sort has a direction, a
 * trailing toggle. The control only reports the new [LibrarySortState]; the host stores it, for example with
 * `LibrarySortState.writeTo`. A custom order has no direction, so the toggle is left out for it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T : Enum<T>> LibrarySortHeader(
    sortState: LibrarySortState<T>,
    sortTypes: List<T>,
    sortTypeLabel: (T) -> StringResource,
    onSortStateChange: (LibrarySortState<T>) -> Unit,
    modifier: Modifier = Modifier,
    showDirection: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val showSortDirection = showDirection && allowsSortDirection(sortState.type)
    val sortDirectionRotation by animateFloatAsState(
        targetValue = if (sortState.descending) 0f else 180f,
        label = "LibrarySortDirection",
    )

    Box(modifier = modifier.padding(vertical = 8.dp)) {
        if (showSortDirection) {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.TonalLeadingButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.heightIn(min = SplitButtonDefaults.MediumContainerHeight),
                    ) {
                        Text(
                            text = stringResource(sortTypeLabel(sortState.type)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TonalTrailingButton(
                        checked = sortState.descending,
                        onCheckedChange = { onSortStateChange(sortState.toggleDescending()) },
                        modifier =
                            Modifier
                                .heightIn(min = SplitButtonDefaults.MediumContainerHeight)
                                .widthIn(min = SplitButtonDefaults.MediumContainerHeight),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.m6_icon_arrow_downward),
                            contentDescription =
                                stringResource(
                                    if (sortState.descending) {
                                        Res.string.m6_sort_descending
                                    } else {
                                        Res.string.m6_sort_ascending
                                    },
                                ),
                            modifier =
                                Modifier
                                    .size(SplitButtonDefaults.TrailingIconSize)
                                    .rotate(sortDirectionRotation),
                        )
                    }
                },
            )
        } else {
            FilledTonalButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.heightIn(min = SplitButtonDefaults.MediumContainerHeight),
            ) {
                Text(
                    text = stringResource(sortTypeLabel(sortState.type)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.widthIn(min = 172.dp),
        ) {
            sortTypes.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(sortTypeLabel(type)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            painter =
                                painterResource(
                                    if (sortState.type == type) {
                                        Res.drawable.m6_icon_radio_checked
                                    } else {
                                        Res.drawable.m6_icon_radio_unchecked
                                    },
                                ),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onSortStateChange(sortState.selectType(type))
                        menuExpanded = false
                    },
                )
            }
        }
    }
}
