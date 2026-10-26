package moe.rukamori.archivetune.shared.domain.lyrics.ai

/**
 * DeepL target codes for the picker's language labels, ported from `DeepLService.targetCode`. A target
 * DeepL cannot take is ineligible for DeepL, and the caller reports it instead of sending a request.
 */
object DeepLTargets {
    private val ALIASES: Map<String, String> =
        mapOf(
            "ARABIC" to "AR", "BULGARIAN" to "BG", "CZECH" to "CS", "DANISH" to "DA",
            "GERMAN" to "DE", "GREEK" to "EL", "ENGLISH" to "EN-US", "SPANISH" to "ES",
            "ESTONIAN" to "ET", "FINNISH" to "FI", "FRENCH" to "FR", "HUNGARIAN" to "HU",
            "INDONESIAN" to "ID", "ITALIAN" to "IT", "JAPANESE" to "JA", "KOREAN" to "KO",
            "LITHUANIAN" to "LT", "LATVIAN" to "LV", "NORWEGIAN" to "NB", "DUTCH" to "NL",
            "POLISH" to "PL", "PORTUGUESE" to "PT-PT", "ROMANIAN" to "RO", "RUSSIAN" to "RU",
            "SLOVAK" to "SK", "SLOVENIAN" to "SL", "SWEDISH" to "SV", "TURKISH" to "TR",
            "UKRAINIAN" to "UK", "CHINESE-SIMPLIFIED" to "ZH-HANS", "CHINESE-TRADITIONAL" to "ZH-HANT",
            "ZH-CN" to "ZH-HANS", "ZH-TW" to "ZH-HANT", "EN" to "EN-US", "PT" to "PT-PT",
        )

    private val CODES: Set<String> =
        setOf(
            "AR", "BG", "CS", "DA", "DE", "EL", "EN-US", "EN-GB", "ES", "ET", "FI",
            "FR", "HU", "ID", "IT", "JA", "KO", "LT", "LV", "NB", "NL", "PL", "PT-PT", "PT-BR",
            "RO", "RU", "SK", "SL", "SV", "TR", "UK", "ZH", "ZH-HANS", "ZH-HANT",
        )

    fun targetCode(language: String): String? {
        val normalized = language.trim().uppercase().replace('_', '-').replace(' ', '-')
        return ALIASES[normalized] ?: normalized.takeIf { it in CODES }
    }
}
