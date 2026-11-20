/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import app.natune.network.NetworkBannerUiState
import app.natune.network.ObserveNetworkBannerStateUseCase
import javax.inject.Inject

@HiltViewModel
class NetworkBannerViewModel
    @Inject
    constructor(
        observeNetworkBannerStateUseCase: ObserveNetworkBannerStateUseCase,
    ) : ViewModel() {
        val bannerState: StateFlow<NetworkBannerUiState> =
            observeNetworkBannerStateUseCase()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = NetworkBannerUiState.Hidden,
                )
    }
