package app.natune.shared.data.provider

import app.natune.shared.data.bytesource.Md5

/** The Qobuz `track/getFileUrl` request signature: the same MD5 hex the desktop host computes. */
class IosQobuzRequestDigest : QobuzRequestDigest {
    override fun md5Hex(value: String): String = Md5.hexDigest(value.encodeToByteArray())
}
