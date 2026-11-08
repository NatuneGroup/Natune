package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.modules.ModuleHostEnvironment
import moe.rukamori.archivetune.modules.moduleStreamCookieJar
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookie
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin

/** Retains the module's own encrypted cookie jar; catalog/personal provider jars cannot answer a module scope. */
internal fun desktopModuleCookies(environment: ModuleHostEnvironment, installation: String): ScopedCookieSource {
    val jar = moduleStreamCookieJar(environment, installation)
    return object : ScopedCookieSource {
        override suspend fun storedCookies(account: ByteSourceAccount, origin: StreamOrigin): List<ScopedCookie> {
            if (account.provider != "modules") return emptyList()
            val url = okhttp3.HttpUrl.Builder().scheme(origin.scheme).host(origin.host).port(origin.port).build()
            return jar.loadForRequest(url).map { ScopedCookie(account, origin, it.name, it.value) }
        }
    }
}
