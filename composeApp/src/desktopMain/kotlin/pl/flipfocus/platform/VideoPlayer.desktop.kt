package pl.flipfocus.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

@Composable
actual fun PlatformVideoPlayer(video: ByteArray, onEnded: () -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) { onEnded() }
}
