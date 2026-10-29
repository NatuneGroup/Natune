package moe.rukamori.archivetune.ui.player.flamingo

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R

@Composable
internal fun FlamingoQueueHeading(count: Int, modifier: Modifier, subtitleModifier: Modifier) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.flamingo_queue_header_title),
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.flamingo_queue_header_count, count),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = subtitleModifier,
        )
    }
}
