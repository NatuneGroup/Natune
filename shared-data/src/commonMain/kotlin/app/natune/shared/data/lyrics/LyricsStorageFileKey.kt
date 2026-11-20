package app.natune.shared.data.lyrics

import app.natune.shared.data.bytesource.Md5
import app.natune.shared.domain.repository.ContentItem

/** Retains the iOS cache/edit filename format without exposing the private digest implementation. */
fun lyricsStorageFileKey(item: ContentItem): String = Md5.hexDigest(
    "${item.ref.provider}/${item.ref.kind}/${item.ref.id}".encodeToByteArray(),
)
