
package app.natune.utils.potoken

/** Holds the two PoToken variants produced by a single BotGuard minting cycle. */
data class PoTokenResult(
    val playerToken: String,
    val sessionToken: String,
)
