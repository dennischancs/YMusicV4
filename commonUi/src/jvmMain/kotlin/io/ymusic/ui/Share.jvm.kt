package io.ymusic.ui

import androidx.compose.runtime.Composable

/** Desktop has no share sheet. [rememberSharer] copies the text instead. */
@Composable
internal actual fun rememberNativeSharer(): ((ShareText) -> Boolean)? = null
