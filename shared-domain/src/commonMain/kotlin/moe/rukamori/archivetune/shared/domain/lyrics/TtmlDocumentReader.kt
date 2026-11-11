package moe.rukamori.archivetune.shared.domain.lyrics

/** The document reader owns rows and credits together; generated native types stay outside domain. */
data class TtmlDocument(val lines: List<LyricsLine>, val credits: LyricsCredits = LyricsCredits())

fun interface TtmlDocumentReader {
    suspend fun readTtml(raw: String): TtmlDocument
}

object KotlinTtmlDocumentReader : TtmlDocumentReader {
    override suspend fun readTtml(raw: String): TtmlDocument = LyricsParser.readTtmlDocument(raw)
}
