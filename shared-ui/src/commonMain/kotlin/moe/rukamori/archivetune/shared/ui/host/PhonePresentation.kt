package moe.rukamori.archivetune.shared.ui.host

import androidx.compose.runtime.staticCompositionLocalOf

/** iOS uses Android's phone presentation; desktop keeps its Metrolist PC surface at every window size. */
val LocalAndroidPhonePresentation = staticCompositionLocalOf { false }
