package moe.rukamori.archivetune.shared.domain.presence

/**
 * On-disk names for the listener-presence opt-in. They live in their own store rather than the main settings file, so
 * the random identifier stays out of settings backup and device transfer. Renaming a name orphans the stored opt-in.
 */
object ListenerPresenceKeys {
    const val ENABLED: String = "listenerPresenceEnabled"
    const val LISTENER_ID: String = "listenerPresenceId"
    const val CONSENT_VERSION: String = "listenerPresenceConsentVersion"
}
