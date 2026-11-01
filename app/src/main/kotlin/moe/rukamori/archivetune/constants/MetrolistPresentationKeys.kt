package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.stringPreferencesKey

// Same wire name and values as the shared-domain MetrolistPresentationKey so a stored MODERN or
// VINTAGE reads identically on every host.
val MetrolistPresentationKey = stringPreferencesKey("metrolistKmpPresentation")
