package moe.rukamori.archivetune.shared.data.lyrics

/** Canonical NFD decomposition, matching the Android provider's `Normalizer.normalize(value, NFD)`. */
internal expect fun canonicalNfd(value: String): String
