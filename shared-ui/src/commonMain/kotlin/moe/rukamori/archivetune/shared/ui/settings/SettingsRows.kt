package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.shared.domain.settings.*
import org.jetbrains.compose.resources.StringResource

/** Explicit controls retain typed persisted identities; labels never become values. */
sealed class SettingsRow(
    val anchor: String,
    val title: StringResource,
    val description: StringResource? = null,
    val enabled: Boolean = true,
) {
    class Toggle(
        anchor: String, title: StringResource, val key: BooleanPreferenceKey, val default: Boolean,
        description: StringResource? = null, enabled: Boolean = true,
        val read: (PreferenceSnapshot) -> Boolean = { it[key, default] },
        val write: PreferenceEditor.(Boolean) -> Unit = { this[key] = it },
        /** Supporting text that follows the switch (for example on or paused). It replaces [description] when set. */
        val descriptionFor: ((Boolean) -> StringResource)? = null,
    ) : SettingsRow(anchor, title, description, enabled)

    data class Option(
        val value: String, val label: StringResource, val enabled: Boolean = true,
        val description: StringResource? = null,
    )
    data class IntOption(val value: Int, val label: StringResource)
    class IntChoice(
        anchor: String, title: StringResource, val key: IntPreferenceKey, val default: Int,
        val options: List<IntOption>, enabled: Boolean = true,
        val write: PreferenceEditor.(Int) -> Unit = { this[key] = it },
    ) : SettingsRow(anchor, title, enabled = enabled) {
        init { require(options.isNotEmpty() && options.map { it.value }.distinct().size == options.size) }
    }
    class Choice(
        anchor: String, title: StringResource, val key: StringPreferenceKey, val default: String,
        val options: List<Option>, enabled: Boolean = true,
        /** Replaces the selected option's label, for example to say why the row is disabled. */
        description: StringResource? = null,
        val read: (PreferenceSnapshot) -> String = { it[key, default] },
        val write: PreferenceEditor.(String) -> Unit = { this[key] = it },
    ) : SettingsRow(anchor, title, description, enabled) {
        init { require(options.isNotEmpty() && options.map { it.value }.distinct().size == options.size) }
    }

    class Text(
        anchor: String, title: StringResource, val key: StringPreferenceKey,
        val masked: Boolean = false, val valid: (String) -> Boolean = { true }, enabled: Boolean = true,
        val default: String = "",
        val write: PreferenceEditor.(String) -> Unit = { this[key] = it },
        val singleLine: Boolean = true,
        /** Headline that follows the stored value (for example Set versus Edit). The editor keeps [title]. */
        val titleFor: ((PreferenceSnapshot) -> StringResource)? = null,
    ) : SettingsRow(anchor, title, enabled = enabled)

    class Action(
        anchor: String, title: StringResource, val run: () -> Unit, enabled: Boolean = true,
        description: StringResource? = null,
        /** The settings route this row opens, when it is a navigation target. Null for local actions. */
        val navRoute: String? = null,
        /**
         * Kept out of a desktop navigation pane until the user searches for it, which is how developer-only pages
         * stay reachable without being offered to every user. Phone hosts render the row as usual.
         */
        val hidden: Boolean = false,
    ) : SettingsRow(anchor, title, description, enabled)

    /**
     * Runtime-derived text (version, account, last check). A null [run] keeps it display-only. [fullText] shows every
     * line of a warning or note, where the default keeps short values to two lines.
     */
    class Status(
        anchor: String, title: StringResource, val value: String, val run: (() -> Unit)? = null,
        enabled: Boolean = true, val fullText: Boolean = false,
    ) : SettingsRow(anchor, title, enabled = enabled)

    /** Non-interactive group label; its anchor is still a scroll target. */
    class Header(anchor: String, title: StringResource) : SettingsRow(anchor, title, enabled = false)

    /**
     * A host-drawn row for layouts the standard rows cannot express, such as a progress bar with an inline cancel or a
     * switch whose change is a host call. It keeps the page's anchor and scroll position. Its content hands every change
     * to the host and never writes preferences, because the page cannot observe such writes.
     */
    class Custom(anchor: String, title: StringResource, val content: @Composable () -> Unit) : SettingsRow(anchor, title)

    /** Runtime locale catalogs use host-provided labels; selection and the dialog remain shared. */
    class RuntimeChoice(
        anchor: String, title: StringResource, val key: StringPreferenceKey, val default: String,
        val options: List<Pair<String, String>>, enabled: Boolean = true,
        val write: PreferenceEditor.(String) -> Unit = { this[key] = it },
    ) : SettingsRow(anchor, title, enabled = enabled)

    /** Visible Android control with no host consumer; it has no callback and cannot edit saved settings. */
    class Unavailable(anchor: String, title: StringResource, val kind: PhoneSettingKind) :
        SettingsRow(anchor, title, enabled = false)

    class Number(
        anchor: String, title: StringResource, val key: IntPreferenceKey, val default: Int, val range: IntRange,
        enabled: Boolean = true,
        val read: (PreferenceSnapshot) -> Int = { it[key, default] },
        val write: PreferenceEditor.(Int) -> Unit = { this[key] = it },
        val unit: SettingsUnit = SettingsUnit.NONE,
    ) : SettingsRow(anchor, title, enabled = enabled)

    class Decimal(
        anchor: String, title: StringResource, val key: FloatPreferenceKey, val default: Float,
        val range: ClosedFloatingPointRange<Float>, val steps: Int = 0, enabled: Boolean = true,
        val normalize: (Float) -> Float = { it },
        val unit: SettingsUnit = SettingsUnit.NONE,
        description: StringResource? = null,
    ) : SettingsRow(anchor, title, description, enabled)
}

/** Display-only units for numeric rows; the persisted value stays a plain number and never carries text. */
enum class SettingsUnit { NONE, SECONDS, PERCENT }
