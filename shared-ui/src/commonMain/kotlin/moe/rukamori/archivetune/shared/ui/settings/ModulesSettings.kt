package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The installed package projection carries no native package/runtime object into presentation. */
data class SettingsModule(val id: String, val name: String, val enabled: Boolean, val issue: String? = null)

/** Android Modules screen structure; platform-only actions retain their rows with an unavailable description. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesSettings(
    modules: List<SettingsModule>,
    enabled: Boolean,
    busy: Boolean,
    issue: String?,
    onEnabled: (Boolean) -> Unit,
    onImport: () -> Unit,
    onConfigure: (String) -> Unit,
    onModuleEnabled: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var addMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<SettingsModule?>(null) }
    val unavailable = stringResource(Res.string.phone_not_available_on_ios)
    Scaffold(
        topBar = { LargeTopAppBar(title = { Column {
            Text(stringResource(Res.string.source_modules))
            Text(stringResource(if (tab == 1) Res.string.phone_modules_catalogs else Res.string.phone_modules_subtitle), style = MaterialTheme.typography.bodyMedium)
        } }, navigationIcon = {
            LocalSettingsBack.current?.let { back -> IconButton(onClick = back) { Icon(painterResource(Res.drawable.m4_icon_arrow_back), stringResource(Res.string.m4_back)) } }
        }, actions = {
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) { Icon(painterResource(Res.drawable.m4hv_icon_more_vert), stringResource(Res.string.m4_more)) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Column { Text(stringResource(Res.string.phone_modules_check_all)); Text(unavailable, style = MaterialTheme.typography.bodySmall) } }, enabled = false, onClick = {})
                DropdownMenuItem(text = { Column { Text(stringResource(Res.string.phone_modules_check_updates)); Text(unavailable, style = MaterialTheme.typography.bodySmall) } }, enabled = false, onClick = {})
            }
        }) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenu = !addMenu }) { Icon(painterResource(Res.drawable.m6yp_icon_add), stringResource(Res.string.m4_more)) }
                DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.phone_modules_import_file)) }, enabled = !busy,
                        onClick = { addMenu = false; onImport() }, leadingIcon = { Icon(painterResource(Res.drawable.phone_reference_download), null) })
                    DropdownMenuItem(text = { Column { Text(stringResource(Res.string.phone_modules_add_url)); Text(unavailable, style = MaterialTheme.typography.bodySmall) } }, enabled = false, onClick = {})
                    DropdownMenuItem(text = { Column { Text(stringResource(Res.string.phone_modules_add_catalog)); Text(unavailable, style = MaterialTheme.typography.bodySmall) } }, enabled = false, onClick = {})
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "enable") {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.phone_modules_enabled), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Switch(enabled, onEnabled, enabled = !busy)
                        }
                        Text(stringResource(if (enabled) Res.string.phone_modules_enabled_description else Res.string.phone_modules_off), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModuleSummary(modules.count { it.enabled }, stringResource(Res.string.m6_source_enabled), Modifier.weight(1f))
                            ModuleSummary(modules.size, stringResource(Res.string.phone_modules_installed), Modifier.weight(1f))
                            ModuleSummary(0, stringResource(Res.string.phone_modules_catalogs), Modifier.weight(1f))
                        }
                    }
                }
            }
            issue?.let { item(key = "issue") { Text(it, color = MaterialTheme.colorScheme.error) } }
            item(key = "tabs") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                    FilterChip(tab == 0, { tab = 0 }, label = { Text(stringResource(Res.string.phone_modules_installed)) }, modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(painterResource(Res.drawable.phone_reference_extension), null) })
                    FilterChip(tab == 1, { tab = 1 }, label = { Text(stringResource(Res.string.phone_modules_catalogs)) }, modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(painterResource(Res.drawable.phone_reference_language), null) })
                }
            }
            if (tab == 0) {
                item(key = "native") {
                    ListItem(headlineContent = { Text(stringResource(Res.string.phone_extensions_native)) },
                        supportingContent = { Text(unavailable) }, leadingContent = { Icon(painterResource(Res.drawable.phone_reference_extension), null) })
                }
                item(key = "order_hint") { Text(stringResource(Res.string.phone_modules_order_hint), style = MaterialTheme.typography.bodySmall) }
                if (modules.isEmpty()) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(Res.drawable.phone_reference_extension), null, Modifier.size(64.dp))
                        Text(stringResource(Res.string.phone_modules_empty), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(Res.string.phone_modules_empty_description), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onImport, enabled = !busy) { Text(stringResource(Res.string.phone_modules_import_file)) }
                    }
                }
                itemsIndexed(modules, key = { _, module -> module.id }) { index, module ->
                    Card(onClick = { onConfigure(module.id) }, enabled = !busy, shape = RoundedCornerShape(22.dp)) {
                        ListItem(headlineContent = { Text(module.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Column { Text(module.id); module.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
                            leadingContent = { Icon(painterResource(Res.drawable.m4_drag_handle), null) },
                            trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(module.enabled, { onModuleEnabled(module.id, it) }, enabled = !busy)
                                Box {
                                    IconButton(onClick = { menu = module.id }) { Icon(painterResource(Res.drawable.m4hv_icon_more_vert), stringResource(Res.string.m4_more)) }
                                    DropdownMenu(menu == module.id, onDismissRequest = { menu = null }) {
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.shared_module_information)) }, onClick = { menu = null; onConfigure(module.id) })
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.m4_move_up)) }, enabled = !busy && index > 0, onClick = { menu = null; onMove(module.id, -1) })
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.m4_move_down)) }, enabled = !busy && index < modules.lastIndex, onClick = { menu = null; onMove(module.id, 1) })
                                        DropdownMenuItem(text = { Column { Text(stringResource(Res.string.phone_modules_check)); Text(unavailable, style = MaterialTheme.typography.bodySmall) } }, enabled = false, onClick = {})
                                        DropdownMenuItem(text = { Column { Text(stringResource(Res.string.phone_modules_check_update)); Text(unavailable, style = MaterialTheme.typography.bodySmall) } }, enabled = false, onClick = {})
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.ios_modules_remove)) }, enabled = !busy, onClick = { menu = null; removal = module })
                                    }
                                }
                            } })
                    }
                }
            } else {
                item(key = "suggested") { Text(stringResource(Res.string.phone_modules_suggested_catalogs), style = MaterialTheme.typography.titleMedium) }
                listOf(Res.string.phone_modules_natune_catalog, Res.string.phone_modules_upstream_catalog, Res.string.phone_extensions_catalog).forEachIndexed { index, title ->
                    item(key = "catalog:$index") { ListItem(headlineContent = { Text(stringResource(title)) }, supportingContent = { Text(unavailable) }, leadingContent = { Icon(painterResource(Res.drawable.phone_reference_language), null) }) }
                }
                item(key = "catalog_empty") { Text(stringResource(Res.string.phone_modules_catalogs_empty_description), style = MaterialTheme.typography.bodyMedium) }
            }
            item(key = "bottom") { Spacer(Modifier.height(72.dp)) }
        }
    }
    removal?.let { module ->
        AlertDialog(onDismissRequest = { removal = null }, title = { Text(stringResource(Res.string.phone_modules_remove_module_title)) },
            text = { Text(stringResource(Res.string.phone_modules_remove_module_description, module.name)) },
            confirmButton = { TextButton(enabled = !busy, onClick = { removal = null; onRemove(module.id) }) { Text(stringResource(Res.string.ios_modules_remove)) } },
            dismissButton = { TextButton(onClick = { removal = null }) { Text(stringResource(Res.string.m6_cancel)) } })
    }
}

@Composable
private fun ModuleSummary(count: Int, title: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .5f)) {
        Column(Modifier.padding(12.dp)) { Text(count.toString(), style = MaterialTheme.typography.headlineSmall); Text(title, style = MaterialTheme.typography.labelSmall) }
    }
}
