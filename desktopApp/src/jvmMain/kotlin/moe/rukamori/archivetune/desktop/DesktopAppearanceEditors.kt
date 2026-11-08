package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.theme.paletteColorScheme

/** Palette generation is the shared MaterialKolor policy; desktop owns only file choosers and the editor. */
@Composable
internal fun DesktopAppearanceEditor(graph: DesktopSessionGraph, path: String) {
    if (path.endsWith("/theme_creator")) { DesktopSeedThemeEditor(graph); return }
    val preferences by graph.settings.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    var color by remember { mutableStateOf(preferences?.get(CustomThemeColorKey)?.takeIf { it.startsWith("#") } ?: "#6750A4") }
    var style by remember { mutableStateOf(preferences?.themePaletteStyle() ?: ThemePaletteStyle.AUTOMATIC) }
    var failed by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) { scope.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    } }
    val valid = Regex("#[0-9a-fA-F]{6}").matches(color)
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(DesktopStrings[if (path.endsWith("/icon")) "desktop_icon_title" else "desktop_theme_title"], style = MaterialTheme.typography.headlineSmall)
        if (path.endsWith("/icon")) {
            Text(DesktopStrings["desktop_icon_description"])
            Button(onClick = { run {
                val file = DesktopFilePickers().use { it.files(false).singleOrNull() } ?: return@run
                withContext(Dispatchers.IO) {
                    require(Files.size(file) <= 1024 * 1024)
                    val image = javax.imageio.ImageIO.read(file.toFile()) ?: error("Invalid icon")
                    require(image.width in 1..1024 && image.height in 1..1024)
                    val target = graph.instanceDirectory.resolve("custom-icon.png")
                    check(javax.imageio.ImageIO.write(image, "png", target.toFile()))
                    image.flush()
                    graph.settings.edit { this[DesktopAppIconPathKey] = target.toString() }
                }
            } }) { Text(DesktopStrings["desktop_icon_pick"]) }
            TextButton(onClick = { run { graph.settings.edit { this[DesktopAppIconPathKey] = null } } }) { Text(DesktopStrings["desktop_icon_reset"]) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("#6750A4", "#006C4C", "#005AC1", "#9C4146", "#765B00", "#5B5B5B").forEach { seed ->
                    Box(Modifier.size(40.dp).background(Color(0xff000000L or seed.substring(1).toLong(16))).clickable { color = seed })
                }
            }
            OutlinedTextField(color, { color = it }, label = { Text(DesktopStrings["desktop_theme_color"]) }, isError = !valid)
            var choosing by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { choosing = true }) { Text(style.name.lowercase().replace('_', ' ')) }
                DropdownMenu(choosing, { choosing = false }) { ThemePaletteStyle.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.name.lowercase().replace('_', ' ')) }, onClick = { style = option; choosing = false })
                } }
            }
            if (valid) {
                val scheme = paletteColorScheme(Color(0xff000000L or color.substring(1).toLong(16)), false, style)
                Row { listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer, scheme.surface).forEach {
                    Box(Modifier.size(52.dp).background(it))
                } }
            }
            Button(enabled = valid, onClick = { run { graph.settings.edit { setCustomThemeColor(color); this[ThemePaletteStyleKey] = style.name } } }) {
                Text(DesktopStrings["desktop_theme_apply"])
            }
            Row {
                TextButton(enabled = valid, onClick = { run {
                    val file = chooseDesktopSaveFile(DesktopStrings["desktop_theme_export"], "natune-theme.json") ?: return@run
                    withContext(Dispatchers.IO) { Files.writeString(file.toPath(), buildJsonObject { put("version", 1); put("color", color); put("palette", style.name) }.toString()) }
                } }) { Text(DesktopStrings["desktop_theme_export"]) }
                TextButton(onClick = { run {
                    val file = DesktopFilePickers().use { it.files(false).singleOrNull() } ?: return@run
                    val theme = withContext(Dispatchers.IO) { require(Files.size(file) <= 4096); Json.parseToJsonElement(Files.readString(file)).jsonObject }
                    require(theme["version"]?.jsonPrimitive?.intOrNull == 1)
                    val seed = theme.getValue("color").jsonPrimitive.content
                    require(Regex("#[0-9a-fA-F]{6}").matches(seed))
                    color = seed; style = theme["palette"]?.jsonPrimitive?.content.toEnum(ThemePaletteStyle.AUTOMATIC)
                } }) { Text(DesktopStrings["desktop_theme_import"]) }
            }
        }
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, text = { Text(DesktopStrings["desktop_theme_failed"]) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(DesktopStrings["ok"]) } })
}
