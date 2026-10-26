package moe.rukamori.archivetune.shared.data.provider

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** The authorized host supplies retained key material. No copied bundled key or URL logging. */
class JvmJioSaavnUrlDecoder(key: ByteArray) : JioSaavnUrlDecoder {
    private val key = key.copyOf().also { require(it.size == 8) }
    override fun decode(encryptedUrl: String): String {
        require(encryptedUrl.length <= 64 * 1024)
        return try {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DES"))
            cipher.doFinal(Base64.getDecoder().decode(encryptedUrl)).toString(Charsets.UTF_8)
        } catch (failure: Exception) { throw ProviderProtocolFailure() }
    }
}
