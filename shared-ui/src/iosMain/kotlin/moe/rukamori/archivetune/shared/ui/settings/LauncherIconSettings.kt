package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.runtime.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The pinned app-icon catalog, with resource-backed names and retained community identities. */
data class LauncherIcon(
    val id: String,
    val name: StringResource,
    val preview: DrawableResource,
    val author: StringResource? = null,
    val githubAuthorUrl: String? = null,
    val isDefault: Boolean = false,
)

internal enum class LauncherIconSortOrder { NEW_ADDED, ALPHABETICAL }
internal data class LauncherIconUiModel(
    val id: String,
    val name: String?,
    val nameResId: StringResource?,
    val author: String?,
    val githubAuthorUrl: String?,
    val previewDrawableResId: DrawableResource,
    val isSelected: Boolean,
    val isDefault: Boolean,
    val supported: Boolean,
)
internal data class LauncherIconScreenUiModel(
    val icons: List<LauncherIconUiModel>,
    val selectedIcon: LauncherIconUiModel,
    val selectionInProgressId: String?,
    val hasCommunityIcons: Boolean,
    val searchQuery: String,
    val sortOrder: LauncherIconSortOrder,
    val isSortMenuExpanded: Boolean,
)
internal sealed interface LauncherIconScreenState {
    data object Loading : LauncherIconScreenState
    data object Empty : LauncherIconScreenState
    data class Error(val messageResId: StringResource) : LauncherIconScreenState
    data class Success(val model: LauncherIconScreenUiModel) : LauncherIconScreenState
}

/** Android's current icon, search/sort and segmented rows; UIKit alone owns applying a selection. */
@Composable
fun LauncherIconSettings(
    selectedId: String,
    applyingId: String?,
    supported: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var order by remember { mutableStateOf(LauncherIconSortOrder.NEW_ADDED) }
    var sortMenu by remember { mutableStateOf(false) }
    val all = SharedLauncherIconCatalog.map { icon -> LauncherIconUiModel(icon.id, null, icon.name,
        icon.author?.let { stringResource(it) }, icon.githubAuthorUrl, icon.preview,
        icon.id == selectedId, icon.isDefault, supported(icon.id)) }
    val names = all.associate { it.id to stringResource(requireNotNull(it.nameResId)) }
    val rows = all.filter { query.isBlank() || names[it.id].orEmpty().contains(query, true) || it.author.orEmpty().contains(query, true) }
    val sorted = if (order == LauncherIconSortOrder.NEW_ADDED) rows.asReversed() else rows.sortedBy { names[it.id].orEmpty().lowercase() }
    val back = LocalSettingsBack.current ?: {}
    LauncherIconScreenContent(
        LauncherIconScreenState.Success(LauncherIconScreenUiModel(sorted, all.firstOrNull { it.isSelected } ?: all.first(), applyingId, true, query, order, sortMenu)),
        onNavigateUp = back, onNavigateHome = moe.rukamori.archivetune.shared.ui.navigation.LocalBackToMain.current ?: back, onRetry = {}, onSelectIcon = onSelect, onOpenAuthorProfile = onOpenAuthor,
        onSearchQueryChange = { query = it }, onShowSortMenu = { sortMenu = true }, onDismissSortMenu = { sortMenu = false }, onSortOrderChange = { order = it })
}
