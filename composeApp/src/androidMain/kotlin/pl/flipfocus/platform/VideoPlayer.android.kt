package pl.flipfocus.platform

import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

@Composable
actual fun PlatformVideoPlayer(video: ByteArray, onEnded: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val file = remember(video) {
        File(context.cacheDir, "easter_${video.contentHashCode()}.mp4").also {
            if (!it.exists() || it.length() != video.size.toLong()) it.writeBytes(video)
        }
    }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(file.absolutePath)
                setOnPreparedListener { start() }
                setOnCompletionListener { onEnded() }
                setOnErrorListener { _, _, _ -> onEnded(); true }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = modifier,
    )
}
