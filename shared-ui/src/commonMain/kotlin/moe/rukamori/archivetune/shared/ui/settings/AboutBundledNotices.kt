package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.InternalResourceApi

/**
 * Third-party data bundled inside the app binary, so it never appears in the generated dependency
 * catalog Android builds for its license list. Their notices ship as compose resources (mirrored
 * from `shared-domain`'s romanization sources); the licenses dialog lists them on hosts without a
 * catalog and opens the full text through [readNotice] on demand.
 */
object AboutBundledNotices {
    /** The bundled-data licenses, presented like catalog entries with an attached notice text. */
    val entries: List<AboutLicenseEntry> =
        listOf(
            AboutLicenseEntry(
                name = "Natune IconPack — Natune and the credited community artists",
                version = "554c3dd24f4240d6b836e9f36572acd78afc8888",
                license = "CC BY-SA 4.0",
                noticeResource = "files/notices/IconPack-CC-BY-SA-4.0.txt",
            ),
            AboutLicenseEntry(
                name = "pinyin-data",
                version = null,
                license = "MIT License",
                noticeResource = "files/notices/pinyin-data-LICENSE.txt",
            ),
            AboutLicenseEntry(
                name = "phrase-pinyin-data",
                version = null,
                license = "MIT License",
                noticeResource = "files/notices/phrase-pinyin-data-LICENSE.txt",
            ),
        )

    /** The bundled notice text for [entry], or null when it carries none or the read fails. */
    @OptIn(InternalResourceApi::class)
    suspend fun readNotice(entry: AboutLicenseEntry): String? =
        entry.noticeResource?.let { path ->
            runCatching { Res.readBytes(path).decodeToString() }.getOrNull()
        }
}
