package moe.rukamori.archivetune.desktop

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.FileFont
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.ui.settings.AddonDownloadUiState
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

/** The typeface file Settings › Additional downloads installs, under the same name the Android add-on uses. */
internal const val SPATIAL_FLOW_FONT_FILE_NAME = "spatialflow_google_sans_flex.ttf"

/**
 * The instance-directory folder the font lives in: the folder Android's add-on store uses under its files directory.
 * The file keeps the plain name because a desktop build pins no digest to put in it.
 */
internal const val SPATIAL_FLOW_FONT_DIRECTORY = "addons"

/** A typeface of this family is about 4 MB; the bound refuses a file that could not be one. */
internal const val SPATIAL_FLOW_FONT_MAX_BYTES = 16L * 1024L * 1024L

/**
 * The installed-file rules. Android downloads the typeface from a pinned commit and checks a build-time digest; a
 * desktop build has no pinned copy, so the user supplies the file and [looksLikeFont] checks the container before
 * anything is written. A file that is not a font would render as the default family, which is worse than a refusal, and
 * [loads] additionally proves Skia can parse it before it replaces a working install.
 */
internal object DesktopSpatialFlowFontFile {
    /** sfnt/OpenType container magic: TrueType, Apple TrueType, a collection and CFF-flavoured OpenType. */
    private val MAGIC = listOf(
        byteArrayOf(0x00, 0x01, 0x00, 0x00),
        "true".toByteArray(Charsets.US_ASCII),
        "ttcf".toByteArray(Charsets.US_ASCII),
        "OTTO".toByteArray(Charsets.US_ASCII),
    )

    fun looksLikeFont(bytes: ByteArray): Boolean =
        bytes.size >= 16 && MAGIC.any { magic -> magic.indices.all { index -> bytes[index] == magic[index] } }

    fun acceptable(bytes: ByteArray): Boolean = bytes.size <= SPATIAL_FLOW_FONT_MAX_BYTES && looksLikeFont(bytes)

    /** True when the bytes parse as a typeface, so an installed file is known to be drawable before it is kept. */
    fun loads(bytes: ByteArray): Boolean = runCatching {
        FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) != null
    }.getOrDefault(false)
}

/**
 * The desktop consumer of the optional SpatialFlow typeface. The file is kept inside the instance directory, so a
 * removed or replaced install leaves nothing behind, and [family] is what the SpatialFlow player draws its title in. A
 * missing file is the default family, never an empty one: the player keeps working without the typeface.
 *
 * The validation runs through Skia so the same TTF the Android app installs is accepted here, while Compose loads the
 * installed file itself through [FileFont].
 */
internal class DesktopSpatialFlowFont(
    instanceDirectory: Path,
    private val scope: CoroutineScope,
) {
    private val target = instanceDirectory.resolve(SPATIAL_FLOW_FONT_DIRECTORY).resolve(SPATIAL_FLOW_FONT_FILE_NAME)
    private val mutableState = MutableStateFlow<AddonDownloadUiState>(AddonDownloadUiState.NotInstalled)
    private val mutableFamily = MutableStateFlow<FontFamily?>(null)

    val state: StateFlow<AddonDownloadUiState> = mutableState.asStateFlow()

    /** The family the player renders with, or null while no typeface is installed. */
    val family: StateFlow<FontFamily?> = mutableFamily.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** Re-reads the installed file. Called at start and after every install or removal. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val installed = installedBytes()
        mutableFamily.value = if (installed == null) null else familyOf()
        mutableState.value = when {
            installed == null -> AddonDownloadUiState.NotInstalled
            mutableFamily.value == null -> AddonDownloadUiState.Failed(DesktopSettingsText["spatial_flow_font_invalid"])
            // The size is what the settings row shows, so it is the bytes the player reads.
            else -> AddonDownloadUiState.Installed(installed.size.toLong())
        }
    }

    /**
     * Installs a typeface the user picked. The copy is written beside the target and renamed over it, so a failed write
     * never leaves a half file the player would try to load. Returns true only when the installed file is drawable.
     */
    suspend fun install(source: Path): Boolean = withContext(Dispatchers.IO) {
        val bytes = try {
            if (!Files.isRegularFile(source) || Files.size(source) > SPATIAL_FLOW_FONT_MAX_BYTES) return@withContext false
            Files.readAllBytes(source)
        } catch (_: Exception) {
            return@withContext false
        }
        if (!DesktopSpatialFlowFontFile.acceptable(bytes) || !DesktopSpatialFlowFontFile.loads(bytes)) {
            mutableState.value = AddonDownloadUiState.Failed(DesktopSettingsText["spatial_flow_font_invalid"])
            return@withContext false
        }
        try {
            Files.createDirectories(target.parent)
            val staged = target.resolveSibling("$SPATIAL_FLOW_FONT_FILE_NAME.partial")
            Files.write(staged, bytes)
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // The previous install is still the one on disk, so its family stays in use.
            refresh()
            mutableState.value = AddonDownloadUiState.Failed(DesktopSettingsText["spatial_flow_font_failed"])
            return@withContext false
        }
        refresh()
        mutableState.value is AddonDownloadUiState.Installed
    }

    /** Opens the file chooser and installs the chosen file. Returns true when a typeface is installed afterwards. */
    suspend fun installPicked(): Boolean {
        val chosen = try {
            DesktopFilePickers().use { it.files(multiple = false, title = DesktopStrings["spatial_flow_font_pick"]) }
        } catch (_: Exception) {
            emptyList()
        }
        val source = chosen.firstOrNull() ?: return false
        return install(source)
    }

    /** Removes the installed file, so the player falls back to the default family. False when it could not be removed. */
    suspend fun remove(): Boolean = withContext(Dispatchers.IO) {
        val removed = try {
            Files.deleteIfExists(target)
        } catch (_: Exception) {
            false
        }
        if (!removed) {
            mutableState.value = AddonDownloadUiState.Failed(DesktopSettingsText["spatial_flow_font_remove_failed"])
            return@withContext false
        }
        refresh()
        true
    }

    private fun installedBytes(): ByteArray? = try {
        if (!Files.isRegularFile(target) || Files.size(target) > SPATIAL_FLOW_FONT_MAX_BYTES) {
            null
        } else {
            Files.readAllBytes(target).takeIf { DesktopSpatialFlowFontFile.loads(it) }
        }
    } catch (_: Exception) {
        null
    }

    /*
     * Compose Desktop resolves a FileFont through Skia, so the family is built from the installed file and the failure
     * to build one is the same "no usable typeface" answer as a missing file.
     */
    @OptIn(ExperimentalTextApi::class)
    private fun familyOf(): FontFamily? = try {
        FontFamily(FileFont(target.toFile()))
    } catch (_: Exception) {
        null
    }
}
