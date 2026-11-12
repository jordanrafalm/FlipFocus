@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package pl.flipfocus.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.darwin.NSObject

@Composable
actual fun PlatformVideoPlayer(video: ByteArray, onEnded: () -> Unit, modifier: Modifier) {
    val currentOnEnded = rememberUpdatedState(onEnded)
    val player = remember(video) {
        val path = NSTemporaryDirectory() + "easter_${video.contentHashCode()}.mp4"
        val data = video.usePinned { NSData.create(bytes = it.addressOf(0), length = video.size.toULong()) }
        data.writeToFile(path, atomically = true)
        AVPlayer(uRL = NSURL.fileURLWithPath(path))
    }
    val controller = remember(player) {
        AVPlayerViewController().apply {
            this.player = player
            showsPlaybackControls = false
        }
    }
    DisposableEffect(player) {
        val observer: NSObject = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, player.currentItem, null,
        ) { currentOnEnded.value() } as NSObject
        player.play()
        onDispose {
            player.pause()
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }
    UIKitView(factory = { controller.view }, modifier = modifier)
}
