package moe.rukamori.archivetune.modules

/** Natune kinds layer onto the v1 envelope; legacy type labels still describe audio sources. */
enum class ModuleKind(val wireType: String, val capabilities: List<String>) {
    AUDIO_SOURCE("audio_provider", listOf("searchTracks", "getTrackStreamUrl", "getAlbum", "getArtist", "getPlaylist")),
    METADATA("metadata_provider", listOf("searchTracks", "getHome", "getLibrary", "getAlbum", "getArtist", "getPlaylist")),
    LYRICS("lyrics_provider", listOf("getLyrics")),
    PLAYBACK("playback_provider", listOf("searchTracks", "getTrackStreamUrl"));

    fun accepts(capabilities: List<String>): Boolean = when (this) {
        AUDIO_SOURCE, PLAYBACK -> capabilities.containsAll(listOf("searchTracks", "getTrackStreamUrl"))
        METADATA -> capabilities.any { it in this.capabilities }
        LYRICS -> "getLyrics" in capabilities
    }

    companion object {
        fun fromTypes(types: List<String>): ModuleKind {
            val kinds = entries.filter { it != AUDIO_SOURCE && it.wireType in types }
            if (kinds.size > 1 || (kinds.isNotEmpty() && AUDIO_SOURCE.wireType in types)) {
                throw ModuleException(ModuleFailure.UNSUPPORTED, stage = ModuleLoadStage.MANIFEST, diagnosticCode = "NATUNE_EXTENSION_KIND")
            }
            return kinds.singleOrNull() ?: AUDIO_SOURCE
        }
    }
}

val ModuleManifest.kind: ModuleKind get() = ModuleKind.fromTypes(types)
val ModuleManifest.providesAudio: Boolean get() = kind == ModuleKind.AUDIO_SOURCE || kind == ModuleKind.PLAYBACK
