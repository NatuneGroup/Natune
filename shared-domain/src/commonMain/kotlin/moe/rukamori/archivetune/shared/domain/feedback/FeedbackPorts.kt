package moe.rukamori.archivetune.shared.domain.feedback

/**
 * Reads the on-device diagnostic log without redaction. [FeedbackReport.finish] redacts the whole assembled draft
 * once, so the reviewed text and the sent text are the same. A failure is thrown, never replaced by a fake log.
 */
interface FeedbackDiagnostics {
    suspend fun unredactedReport(): String
}

/**
 * Posts one assembled envelope to the feedback service. A failure is returned so the review can say the report was
 * not delivered; nothing retries silently. [available] is false when the host has no endpoint or ingest key, and
 * the feature must then stay hidden rather than send anywhere.
 */
interface FeedbackTransport {
    val available: Boolean

    suspend fun send(envelopeJson: String): Result<Unit>
}

/** What the review shows in place of the report text. */
sealed interface FeedbackPreview {
    data object Preparing : FeedbackPreview

    data object Failed : FeedbackPreview

    /** [report] is the exact text Send transmits, inside the envelope's `report` field. */
    data class Ready(val report: String) : FeedbackPreview
}

/** The open review. A null state from the owner means no review is open. */
data class FeedbackReviewState(
    val preview: FeedbackPreview,
    val note: String,
    val sending: Boolean,
    val sendError: String?,
)
