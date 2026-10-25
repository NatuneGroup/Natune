/*
 * ArchiveTune KMP independent foundation
 * SPDX-License-Identifier: GPL-3.0-or-later
 * This program is free software: you can redistribute it and/or modify it under
 * the GNU General Public License, version 3 or (at your option) any later version.
 * This program is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package moe.rukamori.archivetune.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import moe.rukamori.archivetune.shared.resources.Res
import moe.rukamori.archivetune.shared.resources.app_name
import moe.rukamori.archivetune.shared.ui.ArchiveTuneRoot
import org.jetbrains.compose.resources.stringResource

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = stringResource(Res.string.app_name)) {
        // Absence is explicit: do not substitute an advancing synthetic engine.
        ArchiveTuneRoot(snapshot = null)
    }
}
