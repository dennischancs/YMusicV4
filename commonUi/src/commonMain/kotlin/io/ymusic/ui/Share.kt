package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.ymusic.player.PlayableItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.share_copied
import io.ymusic.ui.resources.share_failed
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Text a platform share sheet sends for one stream. A YouTube id shares the watch URL; a local
 *  file with no id shares its uri. */
internal data class ShareText(val text: String, val subject: String?)

internal fun shareTextForYouTube(videoId: String, title: String?): ShareText =
    ShareText(text = "https://www.youtube.com/watch?v=$videoId", subject = title?.takeIf { it.isNotBlank() })

internal fun shareTextForFile(uri: String, title: String?): ShareText =
    ShareText(text = uri, subject = title?.takeIf { it.isNotBlank() })

internal enum class ShareOutcome { Shown, Copied, Failed }

/** True when a share sheet was presented. False leaves the caller to copy [payload] instead. */
@Composable
internal expect fun rememberNativeSharer(): ((ShareText) -> Boolean)?

@Composable
@Suppress("DEPRECATION") // ClipEntry is platform-shaped; the legacy clipboard still copies one string.
internal fun rememberSharer(): (ShareText) -> ShareOutcome {
    val native = rememberNativeSharer()
    val clipboard = LocalClipboardManager.current
    return remember(native, clipboard) {
        { payload ->
            if (native?.invoke(payload) == true) {
                ShareOutcome.Shown
            } else {
                try {
                    clipboard.setText(AnnotatedString(payload.text))
                    ShareOutcome.Copied
                } catch (e: Exception) {
                    ShareOutcome.Failed
                }
            }
        }
    }
}

@Composable
internal fun rememberShareAction(): (ShareText) -> Unit {
    val share = rememberSharer()
    val snackbar = LocalSnackbar.current
    val copied = stringResource(Res.string.share_copied)
    val failed = stringResource(Res.string.share_failed)
    return { payload ->
        when (share(payload)) {
            ShareOutcome.Shown -> Unit
            ShareOutcome.Copied -> snackbar(copied)
            ShareOutcome.Failed -> snackbar(failed)
        }
    }
}

/** Share a queue item. A library row's YouTube id is resolved before the sheet opens, so a download
 *  shares the watch URL rather than a content uri. */
@Composable
internal fun rememberSharePlayable(graph: AppGraph): (PlayableItem) -> Unit {
    val share = rememberShareAction()
    val scope = rememberCoroutineScope()
    return { item ->
        when (item) {
            is PlayableItem.YtMedia -> share(shareTextForYouTube(item.videoId, item.displayTitle))
            is PlayableItem.LocalMedia -> scope.launch {
                val videoId = item.mediaId?.let { graph.mediaStore?.findItem(it)?.youtubeVid }
                val payload = if (videoId != null) {
                    shareTextForYouTube(videoId, item.displayTitle)
                } else {
                    shareTextForFile(item.uri, item.displayTitle)
                }
                share(payload)
            }
        }
    }
}
