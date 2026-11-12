package pl.flipfocus.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import pl.flipfocus.data.*
import pl.flipfocus.domain.*
import pl.flipfocus.platform.*
import pl.flipfocus.presentation.FocusViewModel
import java.awt.Desktop
import java.nio.file.Files
import java.util.Locale

/** Desktop is a development host with explicit, simulated orientation and isolated memory storage. */
fun main() = application {
    val scope = rememberCoroutineScope()
    val platform = remember { DesktopFocusPlatform() }
    val store = remember { MemoryStringStore() }
    val viewModel = remember { FocusViewModel(LocalFocusRepository(store), platform.timeProvider, scope, systemLanguage = platform.languageCode) }
    Window(onCloseRequest = { scope.launch { viewModel.flushAndClose(); exitApplication() } },
        title = "FlipFocus · Desktop preview", state = rememberWindowState(width = 393.dp, height = 912.dp)) {
        FocusTheme {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { App(viewModel, platform, onboardingStore = store) }
                Surface(color = FocusColors.raised) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text("Preview · simulated sensor · data resets on exit", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton({ platform.position(true) }) { Text("Screen down") }
                            TextButton({ platform.position(false) }) { Text("Lift phone") }
                        }
                    }
                }
            }
        }
    }
}

private class DesktopFocusPlatform : FocusPlatform {
    override val languageCode get() = Locale.getDefault().language
    override val timeProvider = object : TimeProvider {
        override fun epochMillis() = System.currentTimeMillis()
        override fun monotonicMillis() = System.nanoTime() / 1_000_000
    }
    override val monitoring = MutableStateFlow(MonitoringState())
    override val isForeground = MutableStateFlow(true)
    private var running = false
    override fun startMonitoring() { running = true; monitoring.value = MonitoringState(orientation = DeviceOrientation.LIFTED) }
    override fun stopMonitoring() { running = false; monitoring.value = MonitoringState() }
    fun position(down: Boolean) {
        if (running) monitoring.value = MonitoringState(orientation = if (down) DeviceOrientation.FACE_DOWN else DeviceOrientation.LIFTED)
    }
    override suspend fun setRemindersEnabled(enabled: Boolean, hour: Int) = ReminderPermission.UNAVAILABLE
    override fun feedback(kind: pl.flipfocus.platform.FeedbackKind, sound: Boolean, haptics: Boolean) = Unit
    override fun shareText(text: String) = shareExport(text, "flipfocus-invitation.txt")
    override fun shareExport(content: String, fileName: String) {
        val path = Files.createTempDirectory("flipfocus-export-").resolve(fileName.substringAfterLast('/'))
        Files.writeString(path, content)
        Desktop.getDesktop().open(path.toFile())
    }
    override fun openSystemSettings() = Unit
    override fun dispose() { stopMonitoring() }
}
