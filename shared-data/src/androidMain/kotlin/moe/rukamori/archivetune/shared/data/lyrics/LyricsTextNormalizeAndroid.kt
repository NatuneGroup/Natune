package moe.rukamori.archivetune.shared.data.lyrics

import java.text.Normalizer

internal actual fun canonicalNfd(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
