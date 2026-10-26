package moe.rukamori.archivetune.shared.data.provider

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.data.stream.des.Des

/** The authorized host supplies retained key material. No copied bundled key or URL logging. */
class IosJioSaavnUrlDecoder(key: ByteArray) : JioSaavnUrlDecoder {
    private val des = Des(key.copyOf())

    @OptIn(ExperimentalEncodingApi::class)
    override fun decode(encryptedUrl: String): String {
        require(encryptedUrl.length <= 64 * 1024)
        return try {
            des.decryptEcbPkcs5(Base64.decode(encryptedUrl)).decodeToString()
        } catch (failure: Exception) {
            throw ProviderProtocolFailure()
        }
    }
}

/** The Qobuz `track/getFileUrl` request signature: the same MD5 hex the desktop host computes. */
class IosQobuzRequestDigest : QobuzRequestDigest {
    override fun md5Hex(value: String): String = Md5.hexDigest(value.encodeToByteArray())
}
