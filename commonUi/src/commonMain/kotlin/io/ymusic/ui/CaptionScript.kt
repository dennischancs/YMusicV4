package io.ymusic.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.player.CaptionCue
import io.ymusic.player.Player

/**
 * Scrollable caption script between the player and the queue. Three rows show at once: history
 * above, the cue at the playhead in the middle, upcoming below. A tap seeks to that cue. Dragging
 * or the wheel only moves the script.
 */
@Composable
internal fun CaptionScript(player: Player, modifier: Modifier = Modifier) {
    val cues by player.captionCues.collectAsState()
    if (cues.isEmpty()) return
    val progress by player.progress.collectAsState()
    val bilingual = remember(cues) { cues.any { it.alt.isNotBlank() } }
    val rowHeight = if (bilingual) 76.dp else 48.dp
    val rowPx = with(LocalDensity.current) { rowHeight.roundToPx() }
    val active = remember(cues, progress.positionMs) { activeCueIndex(cues, progress.positionMs) }
    val scriptKey = cues.firstOrNull()?.let { "${it.startMs}:${cues.size}:${it.text}" } ?: "0"
    val follow = remember(scriptKey) { mutableStateOf(true) }
    val scroll = remember(scriptKey) { ScrollState(0) }
    // Same key as [follow]: a new script replaces that state, and the connection has to write the new one.
    val userScroll = remember(follow) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) follow.value = false
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(scroll, active, follow.value, rowPx) {
        if (!follow.value || rowPx <= 0) return@LaunchedEffect
        val target = if (active < 0) 0 else (active + 1) * rowPx
        if (scroll.value == target) return@LaunchedEffect
        scroll.animateScrollTo(target)
    }

    Column(modifier.fillMaxWidth().background(DesignColors.SurfaceDim)) {
        HorizontalDivider(color = DesignColors.Divider)
        Box(
            Modifier
                .fillMaxWidth()
                .height(rowHeight * 3)
                .clipToBounds()
                .nestedScroll(userScroll),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = DesignSpacing.Space16),
            ) {
                // Two rows of headroom: before the first cue it sits in the bottom slot, then in the middle.
                Spacer(Modifier.height(rowHeight * 2))
                cues.forEachIndexed { index, cue ->
                    CaptionScriptRow(
                        cue = cue,
                        active = index == active,
                        past = active >= 0 && index < active,
                        bilingual = bilingual,
                        rowHeight = rowHeight,
                        onClick = {
                            follow.value = true
                            player.seekTo(cue.startMs)
                        },
                    )
                }
                Spacer(Modifier.height(rowHeight))
            }
        }
        HorizontalDivider(color = DesignColors.Divider)
    }
}

@Composable
private fun CaptionScriptRow(
    cue: CaptionCue,
    active: Boolean,
    past: Boolean,
    bilingual: Boolean,
    rowHeight: Dp,
    onClick: () -> Unit,
) {
    val primary = when {
        active -> DesignColors.Accent
        past -> DesignColors.InkMuted
        else -> DesignColors.InkSecondary
    }
    val alt = when {
        active -> DesignColors.InkSecondary
        past -> DesignColors.InkMuted
        else -> DesignColors.InkSecondary.copy(alpha = 0.72f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clipToBounds()
            .clickable(onClick = onClick)
            .background(if (active) DesignColors.Accent.copy(alpha = 0.12f) else Color.Transparent),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            cue.text,
            color = primary,
            fontSize = 16.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp,
        )
        if (bilingual && cue.alt.isNotBlank()) {
            Text(
                cue.alt,
                color = alt,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun activeCueIndex(cues: List<CaptionCue>, positionMs: Long): Int {
    var index = -1
    for (i in cues.indices) {
        if (cues[i].startMs <= positionMs) index = i else break
    }
    return index
}
