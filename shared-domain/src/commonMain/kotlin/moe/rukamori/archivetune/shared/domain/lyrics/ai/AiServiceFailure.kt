package moe.rukamori.archivetune.shared.domain.lyrics.ai

/**
 * The safe classes of AI service failure. A host maps its transport or provider error to one of these kinds and throws
 * [AiServiceFailure], so no raw response text, key or endpoint reaches a settings state, a notice or a log.
 */
enum class AiServiceFailureKind {
    /** The provider refused the credential for this request. */
    AUTHENTICATION,

    /** The provider is throttling this key or account. */
    RATE_LIMITED,

    /** The endpoint could not be reached or did not finish. */
    UNREACHABLE,

    /** The configured model is not offered to this key. */
    MODEL_UNAVAILABLE,

    /** The provider answered, but not in a shape the app can read. */
    UNEXPECTED_RESPONSE,

    /** Any other failure. The page shows its generic message for it. */
    OTHER,
}

/**
 * A typed failure a host throws from the published AI text service. Its message is fixed to the kind, so a stringified
 * exception, state or log cannot echo a provider body. Any other exception is classified as [AiServiceFailureKind.OTHER].
 */
class AiServiceFailure(val kind: AiServiceFailureKind) : Exception("AI service failure: ${kind.name}")
