package moe.rukamori.archivetune.shared.domain

/**
 * Names the shared domain module in the KMP graph. Playback snapshot and clock types live in the
 * `playback` package. Queue and source policy arrives with the M1 domain milestone.
 */
object SharedDomainModule {
    const val NAME: String = "shared-domain"
}
