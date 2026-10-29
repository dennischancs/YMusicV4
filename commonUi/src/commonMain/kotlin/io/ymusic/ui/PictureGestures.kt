package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import io.ymusic.player.Player
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_forward10
import io.ymusic.ui.resources.cd_pause
import io.ymusic.ui.resources.cd_play
import io.ymusic.ui.resources.cd_rewind10
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.TimeSource

/** Visual picture gestures. +x is a finger moving right; the right half includes the midpoint. */
enum class PictureGesture {
    TogglePlay,
    Previous,
    Next,
    Rewind,
    FastForward,
}

const val PictureHoldMs = 2_000L
const val PictureHintMs = 3_000L
const val PictureGestureDebounceMs = 80L
const val PictureSeekStepMs = 10_000L
const val PictureSeekRepeatMs = 500L
val PictureSwipeThreshold = 72.dp

/**
 * One finger on the picture. A second [down] while tracking is ignored so two desktop targets can
 * deliver the same press; a real second finger goes through [extraPointer].
 */
class PictureGestureTracker(
    private val touchSlopPx: Float,
    private val swipeThresholdPx: Float,
) {
    private enum class Phase { Idle, Tracking, Suppressed }

    private var phase = Phase.Idle
    private var downX = 0f
    private var downY = 0f
    private var width = 0f
    private var holdArmed = false
    private var holdFired = false

    /** False when a press is already being tracked (duplicate delivery). */
    fun down(x: Float, y: Float, widthPx: Float): Boolean {
        if (phase == Phase.Tracking) return false
        phase = Phase.Tracking
        downX = x
        downY = y
        width = widthPx
        holdArmed = widthPx > 0f
        holdFired = false
        return true
    }

    fun extraPointer() {
        phase = Phase.Suppressed
        holdArmed = false
    }

    /** True once the finger leaves the slop and the hold should be cancelled. */
    fun move(x: Float, y: Float): Boolean {
        if (phase != Phase.Tracking || !holdArmed) return false
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy <= touchSlopPx * touchSlopPx) return false
        holdArmed = false
        return true
    }

    fun up(x: Float, y: Float): PictureGesture? {
        val tracking = phase == Phase.Tracking
        val fired = holdFired
        phase = Phase.Idle
        holdArmed = false
        holdFired = false
        if (!tracking || fired) return null
        val dx = x - downX
        val dy = y - downY
        val adx = abs(dx)
        val ady = abs(dy)
        if (adx >= swipeThresholdPx && adx > touchSlopPx && adx > ady) {
            return if (dx > 0f) PictureGesture.Previous else PictureGesture.Next
        }
        if (adx <= touchSlopPx && ady <= touchSlopPx) return PictureGesture.TogglePlay
        return null
    }

    /** Arms a repeating seek. The caller waits [PictureHoldMs], then [heldSeek] until the finger lifts. */
    fun holdElapsed(): PictureGesture? {
        if (phase != Phase.Tracking || !holdArmed || width <= 0f) return null
        holdArmed = false
        holdFired = true
        return heldSeek()
    }

    /** The side still held after [holdElapsed]. Null once the finger lifts or a second finger lands. */
    fun heldSeek(): PictureGesture? {
        if (phase != Phase.Tracking || !holdFired || width <= 0f) return null
        return if (downX < width / 2f) PictureGesture.Rewind else PictureGesture.FastForward
    }

    fun cancel() {
        phase = Phase.Idle
        holdArmed = false
        holdFired = false
    }
}

internal class PictureGestureDebounce(
    private val windowMs: Long = PictureGestureDebounceMs,
) {
    private var last: PictureGesture? = null
    private var atMs = Long.MIN_VALUE

    fun accept(gesture: PictureGesture, nowMs: Long): Boolean {
        if (last == gesture && nowMs - atMs < windowMs) return false
        last = gesture
        atMs = nowMs
        return true
    }
}

private val pictureGestureDebounce = PictureGestureDebounce()
private val pictureGestureClock = TimeSource.Monotonic.markNow()

/** One press of the old rewind or fast-forward button. Rewind stops at 0; fast-forward stops at a known duration. */
internal fun nextPictureSeekMs(positionMs: Long, gesture: PictureGesture, durationMs: Long = -1L): Long = when (gesture) {
    PictureGesture.Rewind -> (positionMs - PictureSeekStepMs).coerceAtLeast(0L)
    PictureGesture.FastForward -> {
        val next = positionMs + PictureSeekStepMs
        if (durationMs > 0L) next.coerceAtMost(durationMs) else next
    }
    else -> positionMs
}

/** @return false when [gesture] repeats inside [PictureGestureDebounceMs] (two targets, one click). */
fun performPictureGesture(player: Player, gesture: PictureGesture): Boolean {
    val now = pictureGestureClock.elapsedNow().inWholeMilliseconds
    if (!pictureGestureDebounce.accept(gesture, now)) return false
    when (gesture) {
        PictureGesture.TogglePlay -> player.togglePlay()
        PictureGesture.Previous -> player.skipPrev()
        PictureGesture.Next -> player.skipNext()
        PictureGesture.Rewind -> player.rewind()
        PictureGesture.FastForward -> player.fastForward()
    }
    VideoOverlayNative.show()
    PictureGestureHints.onGesture(gesture)
    return true
}

/** Now Playing's picture is composed. Desktop mouse ignores the mini-player hole while this is false. */
object PictureGestureInput {
    @Volatile
    private var depth = 0

    val active: Boolean get() = depth > 0

    internal fun enter() {
        depth++
    }

    internal fun exit() {
        depth = (depth - 1).coerceAtLeast(0)
        if (depth == 0) PictureGestureHints.clear()
    }
}

internal object PictureGestureHints {
    var playVisible by mutableStateOf(false)
    var seek by mutableStateOf<PictureGesture?>(null)
    var token by mutableIntStateOf(0)
        private set

    fun onGesture(gesture: PictureGesture) {
        when (gesture) {
            PictureGesture.TogglePlay -> {
                playVisible = true
                seek = null
            }
            PictureGesture.Rewind, PictureGesture.FastForward -> {
                seek = gesture
                playVisible = false
            }
            PictureGesture.Previous, PictureGesture.Next -> Unit
        }
        token++
    }

    fun clear() {
        playVisible = false
        seek = null
    }
}

@Composable
internal fun PictureGestureCatcher(player: Player, modifier: Modifier = Modifier) {
    val slop = LocalViewConfiguration.current.touchSlop
    val swipePx = with(LocalDensity.current) { PictureSwipeThreshold.toPx() }
    val tracker = remember(slop, swipePx) { PictureGestureTracker(slop, swipePx) }
    Box(
        modifier.pointerInput(player, slop, swipePx) {
            try {
                coroutineScope {
                    val scope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (VideoOverlayNative.menusOpen > 0) {
                            VideoOverlayNative.visible = false
                            return@awaitEachGesture
                        }
                        if (!tracker.down(down.position.x, down.position.y, size.width.toFloat())) {
                            return@awaitEachGesture
                        }
                        var suppressed = false
                        val hold = scope.launch {
                            delay(PictureHoldMs)
                            val gesture = tracker.holdElapsed() ?: return@launch
                            val now = pictureGestureClock.elapsedNow().inWholeMilliseconds
                            if (!pictureGestureDebounce.accept(gesture, now)) return@launch
                            VideoOverlayNative.show()
                            var at = player.progress.value.positionMs
                            while (true) {
                                if (tracker.heldSeek() != gesture) break
                                val next = nextPictureSeekMs(at, gesture, player.progress.value.durationMs)
                                if (next != at) {
                                    at = next
                                    player.seekTo(at)
                                }
                                PictureGestureHints.onGesture(gesture)
                                delay(PictureSeekRepeatMs)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                val pressed = event.changes.count { it.pressed }
                                if (pressed > 1) {
                                    suppressed = true
                                    tracker.extraPointer()
                                    hold.cancel()
                                } else if (!suppressed) {
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change != null && change.pressed &&
                                        tracker.move(change.position.x, change.position.y)
                                    ) {
                                        hold.cancel()
                                    }
                                }
                                val primaryUp = event.changes.any { it.id == down.id && !it.pressed }
                                if (primaryUp || pressed == 0) {
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (!suppressed) {
                                        tracker.up(
                                            change?.position?.x ?: down.position.x,
                                            change?.position?.y ?: down.position.y,
                                        )?.let { performPictureGesture(player, it) }
                                    } else {
                                        tracker.cancel()
                                    }
                                    break
                                }
                            }
                        } finally {
                            hold.cancel()
                        }
                    }
                }
            } finally {
                tracker.cancel()
            }
        },
    )
}

private const val PlayButtonAlpha = 0.58f
private const val SeekHintAlpha = 0.85f

@Composable
internal fun PictureGestureHintLayer(player: Player, scale: Float, modifier: Modifier = Modifier) {
    val token = PictureGestureHints.token
    val playVisible = PictureGestureHints.playVisible
    val seek = PictureGestureHints.seek
    LaunchedEffect(token) {
        if (!playVisible && seek == null) return@LaunchedEffect
        delay(PictureHintMs)
        PictureGestureHints.clear()
    }
    val playing by player.status.collectAsState()
    Box(modifier) {
        if (playVisible) {
            val showPause = playing.playWhenReady
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(64.dp * scale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = PlayButtonAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (showPause) Res.string.cd_pause else Res.string.cd_play),
                    Modifier.size(32.dp * scale),
                    tint = Color.Black,
                )
            }
        }
        when (seek) {
            PictureGesture.Rewind -> SeekHint(
                Icons.Default.FastRewind,
                stringResource(Res.string.cd_rewind10),
                AbsoluteAlignment.TopLeft,
                scale,
            )
            PictureGesture.FastForward -> SeekHint(
                Icons.Default.FastForward,
                stringResource(Res.string.cd_forward10),
                AbsoluteAlignment.TopRight,
                scale,
            )
            else -> Unit
        }
    }
}

@Composable
private fun BoxScope.SeekHint(
    icon: ImageVector,
    description: String,
    alignment: Alignment,
    scale: Float,
) {
    Icon(
        icon,
        description,
        Modifier.align(alignment).padding(16.dp * scale).size(40.dp * scale),
        tint = Color.White.copy(alpha = SeekHintAlpha),
    )
}
