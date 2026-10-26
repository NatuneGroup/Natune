package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.getString

/** The text a host shows for [notice], resolved from the shared resources in the current locale. */
suspend fun settingsNoticeText(notice: SettingsNotice): String = when (notice) {
    is SettingsNotice.Message -> notice.count?.let { getString(notice.text, it) } ?: getString(notice.text)
    SettingsNotice.DeezerArlRejected -> getString(Res.string.m6_deezer_arl_invalid)
    is SettingsNotice.DeezerSignedIn -> getString(Res.string.m6_settings_notice_deezer_signed_in, notice.accountName)
    SettingsNotice.DeezerSignedOut -> getString(Res.string.m6_settings_notice_deezer_signed_out)
    SettingsNotice.AmazonInstanceRequired -> getString(Res.string.m6_amazon_instances_empty)
}
