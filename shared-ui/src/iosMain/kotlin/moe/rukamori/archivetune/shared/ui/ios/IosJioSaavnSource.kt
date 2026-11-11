package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.provider.IosJioSaavnUrlDecoder
import moe.rukamori.archivetune.shared.data.provider.KtorJioSaavnRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.settings.JioSaavnWebPlayerKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/** With no key, the default answers unavailable (client configuration required); only the explicit option uses the web player. */
internal fun iosJioSaavnSource(http: HttpClient,
    preferences: () -> PreferenceSnapshot?): AudioProviderRepository = KtorJioSaavnRepository(
    http,
    IosProviderCredentials.jioSaavnDesKey?.let(::IosJioSaavnUrlDecoder),
    useWebPlayer = { preferences()?.get(JioSaavnWebPlayerKey) == true },
)
