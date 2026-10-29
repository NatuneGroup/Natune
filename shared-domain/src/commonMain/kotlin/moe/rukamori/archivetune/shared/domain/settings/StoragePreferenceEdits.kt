package moe.rukamori.archivetune.shared.domain.settings

fun PreferenceSnapshot.smartTrimmerAvailable(): Boolean =
    this[MaxSongCacheSizeKey, 1024] != 0 || this[MaxImageCacheSizeKey, 512] != 0

fun PreferenceEditor.setCacheCapacity(key: IntPreferenceKey, capacity: Int) {
    require(key in setOf(MaxSongCacheSizeKey, MaxImageCacheSizeKey, MaxCanvasCacheSizeKey))
    require(capacity >= -1)
    this[key] = capacity
    if (!snapshot().smartTrimmerAvailable()) this[SmartTrimmerKey] = false
}

fun PreferenceEditor.setSmartTrimmer(enabled: Boolean) {
    this[SmartTrimmerKey] = enabled && snapshot().smartTrimmerAvailable()
}
