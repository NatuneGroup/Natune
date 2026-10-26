package moe.rukamori.archivetune.shared.domain.playback

/**
 * Request headers that every fetch of one progressive resource must carry, such as an authorization cookie or a
 * provider referer. In practice the values are provider credentials, so this type never prints them: [toString]
 * shows only the header names, which are safe for diagnostics. Equality still compares the values, so two locators
 * that differ only in a credential stay distinct.
 */
class PlaybackHeaders private constructor(private val values: Map<String, String>) {
    /** Header names in the order they were supplied. Names are safe to log; values are not. */
    val names: List<String> get() = values.keys.toList()

    fun isEmpty(): Boolean = values.isEmpty()

    /**
     * The header values, for the one engine that performs the request. Callers must not log, persist or display the
     * result.
     */
    fun reveal(): Map<String, String> = values

    override fun equals(other: Any?): Boolean = other is PlaybackHeaders && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "PlaybackHeaders(names=$names)"

    companion object {
        /** No headers: the ordinary header-free progressive case. */
        val NONE: PlaybackHeaders = PlaybackHeaders(emptyMap())

        /**
         * Builds a header set. Names must be HTTP tokens and unique without regard to case. Values must be non-blank
         * and free of CR and LF, so one value can never split into a second header line. Failure messages name the
         * rule, never the value.
         */
        fun of(headers: Map<String, String>): PlaybackHeaders {
            if (headers.isEmpty()) return NONE
            val seenNames = HashSet<String>()
            headers.forEach { (name, value) ->
                require(name.isNotEmpty() && name.all { isTokenChar(it) }) { "header name must be an HTTP token" }
                require(seenNames.add(name.lowercase())) { "header names must be unique without regard to case" }
                require(value.isNotBlank()) { "header value must not be blank" }
                require(value.none { it == '\r' || it == '\n' }) { "header value must not contain line breaks" }
            }
            return PlaybackHeaders(LinkedHashMap(headers))
        }

        /** RFC 7230 token characters: ASCII letters and digits plus the listed punctuation. */
        private fun isTokenChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#\$%&'*+-.^_`|~"
    }
}
