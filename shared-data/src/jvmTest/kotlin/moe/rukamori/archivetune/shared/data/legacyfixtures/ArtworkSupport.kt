/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.ui.utils

// Test-only linkage support. Legacy fixture construction never invokes conversion/resize methods.
enum class YtimgResizePolicy { PreserveOriginal }
fun String.resize(width: Int, height: Int, ytimgResizePolicy: YtimgResizePolicy): String = this
