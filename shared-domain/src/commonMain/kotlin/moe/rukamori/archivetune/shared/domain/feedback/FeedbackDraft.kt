/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.feedback

/**
 * A reviewable feedback draft: the exact text the user sees before a send, plus the kind used for the server
 * envelope. Building never touches the service; that decision stays with the user.
 */
data class FeedbackDraft(
    val kind: String,
    /** The on-screen report body, already redacted and bounded by [FeedbackReport.finish]. */
    val report: String,
)

/**
 * Assembles drafts from the pieces every feedback surface shows. Each draft is one text that goes through
 * [FeedbackReport.finish] once, so redaction and the size bound cover the whole report rather than sections.
 * The host supplies identity, error details and the diagnostic log; a null diagnostic log is shown as unavailable
 * and the rest of the draft still builds.
 */
object FeedbackDrafts {
    /** Stands in for the diagnostic log when the host cannot read it. */
    const val DIAGNOSTICS_UNAVAILABLE = "(unavailable)"

    fun header(
        app: FeedbackReport.AppIdentity,
        device: FeedbackReport.DeviceIdentity,
        forkDirty: Boolean,
    ): String = FeedbackReport.header(app, device, forkDirty)

    /** The JSON envelope a reviewed draft becomes when the user presses send. */
    fun envelope(
        draft: FeedbackDraft,
        note: String,
        sentAt: String,
        app: FeedbackReport.AppIdentity,
        device: FeedbackReport.DeviceIdentity,
    ): String =
        FeedbackReport.json(
            kind = draft.kind,
            sentAt = sentAt,
            app = app,
            device = device,
            note = note,
            report = draft.report,
        )

    fun playback(
        app: FeedbackReport.AppIdentity,
        device: FeedbackReport.DeviceIdentity,
        forkDirty: Boolean,
        errorDetails: String,
        diagnostics: String?,
    ): FeedbackDraft =
        FeedbackDraft(
            kind = FeedbackReport.KIND_PLAYBACK,
            report =
                FeedbackReport.finish(
                    buildString {
                        appendLine(header(app, device, forkDirty))
                        appendLine()
                        appendLine("Report kind: playback error")
                        appendLine()
                        appendLine("Playback error details (as shown in the dialog):")
                        appendLine(errorDetails)
                        appendLine()
                        appendLine(FeedbackReport.LOG_HEADING)
                        append(diagnostics ?: DIAGNOSTICS_UNAVAILABLE)
                    },
                ),
        )

    /** A crash draft from the stored capture file; the capture already carries its own header. */
    fun crash(
        storedReport: String,
        diagnostics: String?,
    ): FeedbackDraft =
        FeedbackDraft(
            kind = FeedbackReport.KIND_CRASH,
            report =
                FeedbackReport.finish(
                    buildString {
                        appendLine(storedReport)
                        appendLine()
                        appendLine(FeedbackReport.LOG_HEADING)
                        append(diagnostics ?: DIAGNOSTICS_UNAVAILABLE)
                    },
                ),
        )
}
