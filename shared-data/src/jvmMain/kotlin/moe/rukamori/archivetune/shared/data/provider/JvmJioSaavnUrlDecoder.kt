package moe.rukamori.archivetune.shared.data.provider

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * DES-ECB decryptor for `encrypted_media_url`, identical to `SaavnService.decryptUrl`. The original
 * bundles the public static DES key `38346591` in source; that default is retained so the source
 * works out of the box, and hosts may still supply different key material.
 */
class JvmJioSaavnUrlDecoder(key: ByteArray = DEFAULT_KEY) : JioSaavnUrlDecoder {
    private val key = key.copyOf().also { require(it.size == 8) }
    companion object { val DEFAULT_KEY = "38346591".toByteArray(Charsets.UTF_8) }
    override fun decode(encryptedUrl: String): String {
        require(encryptedUrl.length <= 64 * 1024)
        return try {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DES"))
            cipher.doFinal(Base64.getDecoder().decode(encryptedUrl)).toString(Charsets.UTF_8)
        } catch (failure: Exception) { throw ProviderProtocolFailure() }
    }
}
