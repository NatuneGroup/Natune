/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import moe.rukamori.archivetune.shared.domain.playback.*

internal fun playerFixture() = PlayerState(
    PlaybackSnapshot("track@1","track",1,0,12000,0,180000,true,1f,true,false,false,PlaybackOutputRoute.LOCAL),
    "Test song","Test artist",listOf("track@0","track@1","track@2"),canPrevious=true,canNext=true,
    volume=.6f,palette=listOf(Color(0xff16436a),Color(0xff57334c)),reducedMotion=true,
)

internal class PlayerTestActions {
    val actions=mutableListOf<PlayerAction>()
    val seeks=mutableListOf<Long>()
    val selected=mutableListOf<String>()
    val visibility=mutableListOf<Boolean>()
    var plays=0
    val callbacks=PlayerActions({ plays++ },seeks::add,selected::add,{},actions::add,visibility::add)
}

internal fun playerSlots() = PlayerSlots(
    artwork={ id,modifier -> Box(modifier.background(if(id=="track@0") Color.Red else Color(0xff2798aa)).testTag("artwork-$id")) },
    canvas={ _,active,modifier -> Box(modifier.background(Color.Magenta).testTag("canvas-$active")) },
    customBackground={ uri,modifier -> Box(modifier.background(Color.Yellow).testTag("custom-$uri")) },
    lyrics={ modifier -> Box(modifier.testTag("lyrics-owner-slot")) { Text("Lyrics fixture") } },
    compactLyrics={ modifier -> Box(modifier.testTag("compact-lyrics-owner-slot")) { Text("Compact fixture") } },
    queue={ modifier -> Box(modifier.testTag("queue-owner-slot")) { Text("Queue fixture") } },
)
