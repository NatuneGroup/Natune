package app.natune.shared.ui.ios

import io.ktor.client.HttpClient
import app.natune.shared.data.platform.ios.IosProviderCredentials
import app.natune.shared.data.provider.IosJioSaavnUrlDecoder
import app.natune.shared.data.provider.KtorJioSaavnRepository
import app.natune.shared.domain.repository.AudioProviderRepository
import app.natune.shared.domain.settings.JioSaavnWebPlayerKey
import app.natune.shared.domain.settings.PreferenceSnapshot

/** With no key, the default answers unavailable (client configuration required); only the explicit option uses the web player. */
internal fun iosJioSaavnSource(http: HttpClient,
    preferences: () -> PreferenceSnapshot?): AudioProviderRepository = KtorJioSaavnRepository(
    http,
    IosProviderCredentials.jioSaavnDesKey?.let(::IosJioSaavnUrlDecoder),
    useWebPlayer = { preferences()?.get(JioSaavnWebPlayerKey) == true },
)
