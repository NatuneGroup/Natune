package moe.rukamori.archivetune.lyrics

import moe.rukamori.archivetune.ai.AiServiceConfig
import moe.rukamori.archivetune.constants.AiApiValidationStatus

/**
 * Whether the current AI settings may be offered for lyrics translation.
 *
 * The config comes from `Preferences.toAiServiceConfig`, so it carries the selected provider and
 * that provider's own credential and endpoint: an OpenRouter or DeepL key is as valid as the
 * generic key. DeepL is accepted because translation is its only capability; romanisation keeps
 * using [AiServiceConfig.supportsRomanization]. A failed validation stays an explicit user-visible
 * refusal rather than being retried silently.
 */
internal fun isAiTranslationAvailable(
    config: AiServiceConfig,
    validationStatus: AiApiValidationStatus,
): Boolean = config.canCallApi && validationStatus != AiApiValidationStatus.FAILED
