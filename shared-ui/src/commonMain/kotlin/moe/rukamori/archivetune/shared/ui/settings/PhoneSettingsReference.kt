package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

enum class PhoneSettingKind { HEADER, TOGGLE, VALUE, ACTION }

data class PhoneSettingReference(
    val anchor: String,
    val title: StringResource,
    val icon: DrawableResource?,
    val kind: PhoneSettingKind,
    val aliases: Set<String> = emptySet(),
    val preferenceName: String? = null,
    val bindingTitles: Set<StringResource> = emptySet(),
    val onlyWhenBound: Boolean = false,
)

data class PhoneSettingsReference(val title: StringResource, val rows: List<PhoneSettingReference>)

/** A phone host opts in; desktop keeps its own Metrolist settings layout. */
val LocalPhoneSettingsReference = staticCompositionLocalOf<PhoneSettingsReference?> { null }
val LocalUnavailableSettingsAnchors = staticCompositionLocalOf<Set<String>> { emptySet() }

internal data class PhoneSettingsEntry(val row: SettingsRow, val reference: PhoneSettingReference? = null)

internal fun SettingsRow.preferenceName(): String? = when (this) {
    is SettingsRow.Toggle -> key.name
    is SettingsRow.Choice -> key.name
    is SettingsRow.IntChoice -> key.name
    is SettingsRow.Text -> key.name
    is SettingsRow.Number -> key.name
    is SettingsRow.Decimal -> key.name
    is SettingsRow.RuntimeChoice -> key.name
    else -> null
}

/**
 * The reference supplies presentation, while existing rows retain all reads, writes and host actions.
 * An unbound Android control is retained, disabled, with no preference writer or action callback.
 */
internal fun phoneSettingsEntries(rows: List<SettingsRow>, reference: PhoneSettingsReference?, unavailable: Set<String> = emptySet()): List<PhoneSettingsEntry> {
    if (reference == null || reference.rows.isEmpty()) return rows.map { PhoneSettingsEntry(it) }
    val remaining = rows.filterNot { it is SettingsRow.Header }.toMutableList()
    val result = mutableListOf<PhoneSettingsEntry>()
    reference.rows.forEach { spec ->
        if (spec.kind == PhoneSettingKind.HEADER) {
            result += PhoneSettingsEntry(SettingsRow.Header(spec.anchor, spec.title), spec)
        } else {
            val match = remaining.firstOrNull { it.anchor == spec.anchor || it.anchor in spec.aliases }
                ?: spec.preferenceName?.let { name -> remaining.firstOrNull { it.preferenceName() == name } }
                ?: remaining.firstOrNull { it.title == spec.title || it.title in spec.bindingTitles }
            if (match != null) {
                remaining.remove(match)
                result += PhoneSettingsEntry(match, spec)
            } else if (!spec.onlyWhenBound || spec.anchor in unavailable || spec.aliases.any { it in unavailable }) {
                result += PhoneSettingsEntry(SettingsRow.Unavailable(spec.anchor, spec.title, spec.kind), spec)
            }
        }
    }
    // Host-supported supplementary rows (for example the observed USB rate) keep their saved identity.
    // Their nearest existing row owns their position rather than a detached iOS information block.
    remaining.forEach { row ->
        val originalIndex = rows.indexOf(row)
        val previous = rows.take(originalIndex).lastOrNull { candidate -> result.any { it.row === candidate } }
        val insertion = previous?.let { candidate -> result.indexOfFirst { it.row === candidate } + 1 } ?: result.size
        result.add(insertion, PhoneSettingsEntry(row))
    }
    return result
}

internal fun PhoneSettingsEntry.unavailable(anchors: Set<String>): Boolean =
    row is SettingsRow.Unavailable || row.anchor in anchors || reference?.anchor?.let { it in anchors } == true ||
        reference?.aliases?.any { it in anchors } == true
