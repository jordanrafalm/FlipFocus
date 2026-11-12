package pl.flipfocus.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Plays an mp4 once with sound and calls [onEnded] when finished (or immediately where unsupported). */
@Composable
expect fun PlatformVideoPlayer(video: ByteArray, onEnded: () -> Unit, modifier: Modifier = Modifier)
