package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberNativeSharer(): ((ShareText) -> Boolean)? = remember {
    { payload -> presentShareSheet(payload) }
}

@OptIn(ExperimentalForeignApi::class)
private fun presentShareSheet(payload: ShareText): Boolean {
    val host = topViewController() ?: return false
    val controller = UIActivityViewController(activityItems = listOf(activityItem(payload)), applicationActivities = null)
    controller.popoverPresentationController()?.let { pop ->
        pop.sourceView = host.view
        pop.sourceRect = host.view.bounds
    }
    host.presentViewController(controller, animated = true, completion = null)
    return true
}

@OptIn(ExperimentalForeignApi::class)
private fun activityItem(payload: ShareText): Any {
    val path = when {
        payload.text.startsWith("file://") -> payload.text.removePrefix("file://")
        payload.text.startsWith("/") -> payload.text
        else -> null
    }
    if (path != null && NSFileManager.defaultManager.fileExistsAtPath(path)) {
        return NSURL.fileURLWithPath(path)
    }
    return payload.text
}

@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) {
        vc = vc.presentedViewController
    }
    return vc
}
