package io.ymusic.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberNativeSharer(): ((ShareText) -> Boolean)? {
    val context = LocalContext.current
    return remember(context) { { payload -> openShareSheet(context, payload) } }
}

private fun openShareSheet(context: Context, payload: ShareText): Boolean {
    val stream = payload.text.takeIf { it.startsWith("content:") }?.let(Uri::parse)
    val send = Intent(Intent.ACTION_SEND).apply {
        if (stream != null) {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, stream)
            payload.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            clipData = ClipData.newRawUri(payload.subject ?: "", stream)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, payload.text)
            payload.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
    }
    val host = context.findActivity()
    val chooser = Intent.createChooser(send, payload.subject).apply {
        // A content uri's read grant has to ride the chooser, or the target app cannot open the stream.
        if (stream != null) {
            clipData = send.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (host == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        (host ?: context).startActivity(chooser)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: android.util.AndroidRuntimeException) {
        false
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
