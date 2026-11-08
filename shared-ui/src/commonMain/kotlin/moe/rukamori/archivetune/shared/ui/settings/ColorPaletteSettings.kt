package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.theme.paletteColorScheme
import org.jetbrains.compose.resources.stringResource

/** A draft palette previews locally; Save commits only the independent seed preference. */
@Composable
fun ColorPaletteSettings(controller: SettingsController, dark: Boolean, onFailure: () -> Unit) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: run { CircularProgressIndicator(); return }
    var text by remember(preferences[CustomThemeColorKey]) { mutableStateOf(preferences[CustomThemeColorKey] ?: "#6750A4") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val seed = text.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.drop(1)?.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
    val style = preferences.themePaletteStyle()
    val scheme = remember(seed, dark, style) { seed?.let { paletteColorScheme(it, dark, style) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LocalSettingsBack.current?.let { SettingsBackHeader(it) }
        Text(stringResource(Res.string.shared_palette_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.shared_palette_description))
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(Res.string.shared_palette_hex)) },
            singleLine = true, isError = seed == null, enabled = !busy, modifier = Modifier.fillMaxWidth())
        listOf(listOf("#6750A4", "#B3261E", "#006A6A"), listOf("#386A20", "#005AC1", "#E05297")).forEach { choices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { choice ->
                    OutlinedButton(onClick = { text = choice }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Box(Modifier.size(24.dp).background(Color(0xFF000000L or choice.drop(1).toLong(16)), CircleShape))
                        if (text.equals(choice, true)) Text(" ✓")
                    }
                }
            }
        }
        scheme?.let { preview -> MaterialTheme(colorScheme = preview) {
            Surface(shape = MaterialTheme.shapes.large, color = preview.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.shared_palette_preview), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(Res.string.shared_palette_preview_description), color = preview.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = MaterialTheme.shapes.medium, color = preview.primaryContainer) {
                            Text(stringResource(Res.string.shared_palette_primary), modifier = Modifier.padding(12.dp), color = preview.onPrimaryContainer)
                        }
                        Surface(shape = MaterialTheme.shapes.medium, color = preview.secondaryContainer) {
                            Text(stringResource(Res.string.shared_palette_secondary), modifier = Modifier.padding(12.dp), color = preview.onSecondaryContainer)
                        }
                    }
                }
            }
        } }
        Button(enabled = seed != null && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            val selected = text.uppercase()
            busy = true
            scope.launch {
                try { controller.edit { setCustomThemeColor(selected) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
                finally { busy = false }
            }
        }) { Text(stringResource(Res.string.shared_palette_save)) }
    }
}
