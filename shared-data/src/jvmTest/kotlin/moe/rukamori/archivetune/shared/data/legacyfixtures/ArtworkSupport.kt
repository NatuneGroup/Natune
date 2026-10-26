package moe.rukamori.archivetune.ui.utils

// Test-only linkage support. Legacy fixture construction never invokes conversion/resize methods.
enum class YtimgResizePolicy { PreserveOriginal }
fun String.resize(width: Int, height: Int, ytimgResizePolicy: YtimgResizePolicy): String = this
