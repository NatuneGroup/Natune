/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * Discord experimental options: the translator that rewrites the sent fields, and the two activity buttons. Each
 * button's detail rows appear only while its switch is on, and a custom URL row appears only for the custom source.
 * The rows come from the same snapshot the page commits to, so a hidden row never keeps a stale value.
 */
@Composable
fun DiscordExperimentalSettings(
    controller: SettingsController,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }

    val translatorOn = preferences[EnableTranslatorKey, false]
    val button1On = preferences[DiscordActivityButton1EnabledKey, DiscordSettingsDefaults.BUTTON1_ENABLED]
    val button1Source = preferences[DiscordActivityButton1UrlSourceKey, DiscordSettingsDefaults.BUTTON1_URL_SOURCE]
    val button2On = preferences[DiscordActivityButton2EnabledKey, DiscordSettingsDefaults.BUTTON2_ENABLED]
    val button2Source = preferences[DiscordActivityButton2UrlSourceKey, DiscordSettingsDefaults.BUTTON2_URL_SOURCE]

    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("translator_options", Res.string.m6_discord_group_translator))
        add(
            SettingsRow.Toggle(
                anchor = "enable_translator",
                title = Res.string.m6_discord_enable_translator,
                key = EnableTranslatorKey,
                default = false,
                description = Res.string.m6_discord_enable_translator_description,
            ),
        )
        if (translatorOn) {
            add(
                SettingsRow.Text(
                    anchor = "translator_contexts",
                    title = Res.string.m6_discord_context_info,
                    key = TranslatorContextsKey,
                    default = DiscordSettingsDefaults.TRANSLATOR_CONTEXTS,
                ),
            )
            add(
                SettingsRow.Choice(
                    anchor = "target_language",
                    title = Res.string.m6_target_language,
                    key = TranslatorTargetLangKey,
                    default = DiscordSettingsDefaults.TRANSLATOR_TARGET,
                    options = translatorLanguageOptions(),
                ),
            )
        }

        add(SettingsRow.Header("discord_buttons", Res.string.m6_discord_group_buttons))
        add(
            SettingsRow.Toggle(
                anchor = "discord_show_button_1",
                title = Res.string.m6_discord_show_button,
                key = DiscordActivityButton1EnabledKey,
                default = DiscordSettingsDefaults.BUTTON1_ENABLED,
                description = Res.string.m6_discord_show_button1_description,
            ),
        )
        if (button1On) {
            add(
                SettingsRow.Choice(
                    anchor = "discord_activity_button_1_url",
                    title = Res.string.m6_discord_button1_url_source,
                    key = DiscordActivityButton1UrlSourceKey,
                    default = DiscordSettingsDefaults.BUTTON1_URL_SOURCE,
                    options = discordButtonUrlSourceOptions,
                ),
            )
            add(
                SettingsRow.Text(
                    anchor = "discord_activity_button1_label",
                    title = Res.string.m6_discord_button1_label,
                    key = DiscordActivityButton1LabelKey,
                    default = DiscordSettingsDefaults.BUTTON1_LABEL,
                ),
            )
            if (button1Source == "custom") {
                add(
                    SettingsRow.Text(
                        anchor = "discord_activity_button1_url",
                        title = Res.string.m6_discord_button1_custom_url,
                        key = DiscordActivityButton1CustomUrlKey,
                    ),
                )
            }
        }

        add(
            SettingsRow.Toggle(
                anchor = "discord_show_button_2",
                title = Res.string.m6_discord_show_button,
                key = DiscordActivityButton2EnabledKey,
                default = DiscordSettingsDefaults.BUTTON2_ENABLED,
                description = Res.string.m6_discord_show_button2_description,
            ),
        )
        if (button2On) {
            add(
                SettingsRow.Choice(
                    anchor = "discord_activity_button_2_url",
                    title = Res.string.m6_discord_button2_url_source,
                    key = DiscordActivityButton2UrlSourceKey,
                    default = DiscordSettingsDefaults.BUTTON2_URL_SOURCE,
                    options = discordButtonUrlSourceOptions,
                ),
            )
            add(
                SettingsRow.Text(
                    anchor = "discord_activity_button2_label",
                    title = Res.string.m6_discord_button2_label,
                    key = DiscordActivityButton2LabelKey,
                    default = DiscordSettingsDefaults.BUTTON2_LABEL,
                ),
            )
            if (button2Source == "custom") {
                add(
                    SettingsRow.Text(
                        anchor = "discord_activity_button2_url",
                        title = Res.string.m6_discord_button2_custom_url,
                        key = DiscordActivityButton2CustomUrlKey,
                        default = DiscordSettingsDefaults.BUTTON2_CUSTOM_URL,
                    ),
                )
            }
        }
    }

    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
    )
}

private fun buttonUrlSourceLabel(value: String): StringResource = when (value) {
    "artisturl" -> Res.string.m6_discord_url_source_artist
    "albumurl" -> Res.string.m6_discord_url_source_album
    "custom" -> Res.string.m6_discord_url_source_custom
    else -> Res.string.m6_discord_url_source_song
}

private val discordButtonUrlSourceOptions: List<SettingsRow.Option> =
    DiscordButtonUrlSourceValues.map { SettingsRow.Option(it, buttonUrlSourceLabel(it)) }
