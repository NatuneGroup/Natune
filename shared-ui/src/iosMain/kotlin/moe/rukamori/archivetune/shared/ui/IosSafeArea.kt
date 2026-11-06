package moe.rukamori.archivetune.shared.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The window's safe area, as the UIKit host measures it. It is physical edges, not leading/trailing, because the
 * notch and the home indicator do not move when the interface is mirrored.
 */
class IosSafeArea internal constructor() {
    private val current = MutableStateFlow(SafeAreaPadding.None)
    val padding: StateFlow<SafeAreaPadding> = current.asStateFlow()

    fun update(left: Double, top: Double, right: Double, bottom: Double) {
        val next = SafeAreaPadding(left = left, top = top, right = right, bottom = bottom).sanitized()
        if (current.value != next) current.value = next
    }
}
