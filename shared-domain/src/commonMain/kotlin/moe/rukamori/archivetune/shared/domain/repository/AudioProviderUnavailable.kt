package moe.rukamori.archivetune.shared.domain.repository

/** A missing capability or authorization, not a search with zero matches. */
enum class AudioProviderUnavailableReason {
    ACCOUNT_REQUIRED,
    CLIENT_CONFIGURATION_REQUIRED,
    PLATFORM_DECRYPTION_REQUIRED,
    ACCOUNT_CHANGED,
}

class AudioProviderUnavailable(val reason: AudioProviderUnavailableReason) : IllegalStateException(reason.name)
