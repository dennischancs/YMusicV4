package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.player.CaptionCue
import io.ymusic.player.Player
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Scrollable caption script between the player and the queue. Three rows show at once: history
 * above, the cue at the playhead in the middle, upcoming below. A tap seeks to that cue. Dragging
 * or the wheel only moves the script.
 */
@Composable
internal fun CaptionScript(player: Player, modifier: Modifier = Modifier) {
    val cues by player.captionCues.collectAsState()
    if (cues.isEmpty()) return
    val bilingual = remember(cues) { cues.any { it.alt.isNotBlank() } }
    val rowHeight = if (bilingual) 76.dp else 48.dp
    val scriptKey = cues.firstOrNull()?.let { "${it.startMs}:${cues.size}:${it.text}" } ?: "0"
    val follow = remember(scriptKey) { mutableStateOf(true) }
    val programmatic = remember(scriptKey) { CaptionFollow() }
    val listState = remember(scriptKey) { LazyListState() }
    // Progress ticks several times a second. The row only changes when the cue index does.
    val active by produceState(initialValue = -1, player, cues) {
        player.progress
            .map { activeCueIndex(cues, it.positionMs) }
            .distinctUntilChanged()
            .collect { value = it }
    }
    val userScroll = remember(scriptKey) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && !programmatic.scrolling) {
                    follow.value = false
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(active, follow.value, cues.size) {
        if (!follow.value || cues.isEmpty()) return@LaunchedEffect
        val index = if (active < 0) 0 else active + 1
        if (listState.firstVisibleItemIndex == index && listState.firstVisibleItemScrollOffset == 0) {
            return@LaunchedEffect
        }
        programmatic.scrolling = true
        try {
            listState.scrollToItem(index)
        } finally {
            programmatic.scrolling = false
        }
    }

    Column(modifier.fillMaxWidth().background(DesignColors.SurfaceDim)) {
        HorizontalDivider(color = DesignColors.Divider)
        // Fixed height: the portrait queue measures this with an unbounded height. Two pad rows,
        // then the cues, then one, so the active cue sits in the middle.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight * 3)
                .clipToBounds()
                .nestedScroll(userScroll)
                .padding(horizontal = DesignSpacing.Space16),
        ) {
            item(key = "pad-a") { Spacer(Modifier.height(rowHeight)) }
            item(key = "pad-b") { Spacer(Modifier.height(rowHeight)) }
            itemsIndexed(cues, key = { index, cue -> "${cue.id}:${cue.startMs}:$index" }) { index, cue ->
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
            item(key = "pad-c") { Spacer(Modifier.height(rowHeight)) }
        }
        HorizontalDivider(color = DesignColors.Divider)
    }
}

/** True while the script is catching the playhead, so that scroll is not mistaken for a drag. */
private class CaptionFollow {
    var scrolling = false
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
