package moe.rukamori.archivetune.shared.domain.lyrics.ai

/**
 * A provider model offered by the settings picker. [displayName] falls back to [id] when the provider
 * sends no friendly name, so the picker always has a label to show.
 */
data class AiModelOption(
    val id: String,
    val displayName: String,
)
