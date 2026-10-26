package moe.rukamori.archivetune.shared.domain.repository

import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiModelOption
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiRequestBudget
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiServiceSettings

/**
 * M6 owns request/session identity, document segmentation/rebuild, cache and stale-result rejection.
 * The transport owns provider wire formats, line-count validation and the shared request budget.
 */
interface AiTextService {
    suspend fun translateLines(
        config: AiServiceSettings,
        targetLanguage: String,
        lines: List<String>,
        formatName: String,
    ): List<String>

    suspend fun romanizeLines(config: AiServiceSettings, lines: List<String>, formatName: String): List<String>

    /** Model identifiers only. Settings pickers that show labels call [fetchModels] instead. */
    suspend fun listModels(config: AiServiceSettings): List<String>

    /** Models with display names, as the settings model picker presents them. */
    suspend fun fetchModels(config: AiServiceSettings): List<AiModelOption>

    suspend fun test(config: AiServiceSettings)

    /**
     * One chat completion for prompts that are not lyric batches, such as AI Mix. The call is charged to
     * [budget] before it is sent and refunded in that same family if it fails or is cancelled.
     */
    suspend fun complete(
        config: AiServiceSettings,
        budget: AiRequestBudget,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.2,
        maxTokens: Int = 4096,
    ): String
}
