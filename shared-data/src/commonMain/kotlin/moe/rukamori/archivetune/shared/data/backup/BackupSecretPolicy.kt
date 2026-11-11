package moe.rukamori.archivetune.shared.data.backup

/** The credential matcher introduced by Android backup exclusion; every export uses this one definition. */
object BackupSecretPolicy {
    private val credentialFragments = listOf("cookie", "token", "password", "secret", "apikey", "credential", "jwt", "session")
    private val credentialNames = setOf("spotifyspdc", "spotifyspkey", "deezerarl", "qqmusicmusickey", "lypriosigningkey")

    fun isSecret(name: String): Boolean {
        val normalized = name.filter(Char::isLetterOrDigit).lowercase()
        return credentialFragments.any(normalized::contains) || normalized in credentialNames
    }
}
