package moe.rukamori.archivetune.shared.data.lyrics

import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.domain.repository.ContentItem

/** Retains the iOS cache/edit filename format without exposing the private digest implementation. */
fun lyricsStorageFileKey(item: ContentItem): String = Md5.hexDigest(
    "${item.ref.provider}/${item.ref.kind}/${item.ref.id}".encodeToByteArray(),
)
