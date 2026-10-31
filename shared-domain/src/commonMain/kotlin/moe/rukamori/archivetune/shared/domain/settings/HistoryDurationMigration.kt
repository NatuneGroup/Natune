package moe.rukamori.archivetune.shared.domain.settings

fun PreferenceEditor.migrateHistoryDuration() {
    val legacy = this[HISTORY_DURATION_LEGACY_FLOAT_KEY] ?: return
    this[HISTORY_DURATION_LEGACY_FLOAT_KEY] = null
    if (this[HistoryDuration] == null) this[HistoryDuration] = legacy.toInt().coerceIn(1, 60)
}
