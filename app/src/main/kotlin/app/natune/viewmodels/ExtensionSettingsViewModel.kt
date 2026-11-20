package app.natune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.natune.modules.ExtensionRegistration
import app.natune.modules.ExtensionRegistry
import app.natune.utils.reportException
import javax.inject.Inject

@HiltViewModel
class ExtensionSettingsViewModel @Inject constructor(private val registry: ExtensionRegistry) : ViewModel() {
    private val mutableState = MutableStateFlow<List<ExtensionRegistration>>(emptyList())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch { registry.state.collect { mutableState.value = it } }
        launch { registry.initialize() }
    }

    fun setEnabled(id: String, enabled: Boolean) = launch { registry.setEnabled(id, enabled) }
    fun move(id: String, delta: Int) = launch { registry.moveNative(id, delta) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportException(error) }
        }
    }
}
