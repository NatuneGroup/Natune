package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlinx.coroutines.*
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.settings.CustomThemeColorKey
import moe.rukamori.archivetune.shared.domain.settings.setCustomThemeColor
import moe.rukamori.archivetune.ui.theme.ThemeSeedPalette
import moe.rukamori.archivetune.ui.theme.ThemeSeedPaletteCodec
import moe.rukamori.archivetune.ui.theme.desktopSeedColorScheme

internal fun desktopThemeColor(value: String): Color {
    val digits = value.removePrefix("#")
    require(digits.length in setOf(6, 8) && digits.all { it.digitToIntOrNull(16) != null })
    return Color(digits.toLong(16).let { if (digits.length == 6) it or 0xff000000L else it })
}

/** Android's four-seed format and scheme merge, presented in the desktop settings surface. */
@Composable
internal fun DesktopSeedThemeEditor(graph: DesktopSessionGraph) {
    val preferences by graph.settings.snapshots.collectAsState()
    val saved = preferences?.get(CustomThemeColorKey).orEmpty()
    val initial = remember(saved) { ThemeSeedPaletteCodec.decodeFromPreference(saved)
        ?: runCatching { desktopThemeColor(saved) }.getOrNull()?.let { ThemeSeedPalette(it, it, it, it) }
        ?: ThemeSeedPalette(Color(0xff6750a4), Color(0xff625b71), Color(0xff7d5260), Color(0xff605d62)) }
    var primary by remember { mutableStateOf(initial.primary) }
    var secondary by remember { mutableStateOf(initial.secondary) }
    var tertiary by remember { mutableStateOf(initial.tertiary) }
    var neutral by remember { mutableStateOf(initial.neutral) }
    var name by remember { mutableStateOf(ThemeSeedPaletteCodec.extractNameFromPreference(saved).orEmpty()) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(action: suspend () -> Unit) { scope.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    } }
    val palette = ThemeSeedPalette(primary, secondary, tertiary, neutral)
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(DesktopStrings["desktop_theme_title"], style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { if (it.length <= 128) name = it }, label = { Text(DesktopStrings["desktop_theme_name"]) })
        SeedColorField("primary", primary) { primary = it }
        SeedColorField("secondary", secondary) { secondary = it }
        SeedColorField("tertiary", tertiary) { tertiary = it }
        SeedColorField("neutral", neutral) { neutral = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (dark in listOf(false, true)) {
                val scheme = desktopSeedColorScheme(palette, dark)
                Row { listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.surface).forEach { Box(Modifier.size(36.dp).background(it)) } }
            }
        }
        Button(onClick = { run { graph.settings.edit { setCustomThemeColor(ThemeSeedPaletteCodec.encodeForPreference(palette, name.takeIf(String::isNotBlank))) } } }) {
            Text(DesktopStrings["desktop_theme_apply"])
        }
        Row {
            TextButton(onClick = { run {
                val file = chooseDesktopSaveFile(DesktopStrings["desktop_theme_export"], "natune-theme.json") ?: return@run
                withContext(Dispatchers.IO) { Files.writeString(file.toPath(), ThemeSeedPaletteCodec.encodeAsJson(palette, name.takeIf(String::isNotBlank))) }
            } }) { Text(DesktopStrings["desktop_theme_export"]) }
            TextButton(onClick = { run {
                val file = DesktopFilePickers().use { it.files(false).singleOrNull() } ?: return@run
                val text = withContext(Dispatchers.IO) { require(Files.size(file) <= 64 * 1024); Files.readString(file) }
                val loaded = ThemeSeedPaletteCodec.decodeFromJson(text) ?: error("Invalid theme")
                primary = loaded.primary; secondary = loaded.secondary; tertiary = loaded.tertiary; neutral = loaded.neutral
                name = ThemeSeedPaletteCodec.extractNameFromJsonOrNull(text).orEmpty()
            } }) { Text(DesktopStrings["desktop_theme_import"]) }
        }
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, text = { Text(DesktopStrings["desktop_theme_failed"]) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(DesktopStrings["ok"]) } })
}

@Composable
private fun SeedColorField(role: String, color: Color, change: (Color) -> Unit) {
    var text by remember(color) { mutableStateOf(String.format("#%08X", color.toArgb())) }
    val parsed = runCatching { desktopThemeColor(text) }.getOrNull()
    OutlinedTextField(text, { text = it; runCatching { desktopThemeColor(it) }.getOrNull()?.let(change) },
        label = { Text(DesktopStrings["desktop_theme_$role"]) }, isError = parsed == null,
        leadingIcon = { Box(Modifier.size(24.dp).background(color)) })
}
