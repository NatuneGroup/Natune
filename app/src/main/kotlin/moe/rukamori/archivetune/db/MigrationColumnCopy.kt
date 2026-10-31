package moe.rukamori.archivetune.db

internal fun migrationColumnExpression(sourceName: String?, notNull: Boolean, fallback: String): String =
    when {
        sourceName == null -> fallback
        // Old song_album_map.index allowed NULL (schemas 2–4); a direct copy violates today's schema.
        notNull -> "COALESCE(`$sourceName`, $fallback)"
        else -> "`$sourceName`"
    }
