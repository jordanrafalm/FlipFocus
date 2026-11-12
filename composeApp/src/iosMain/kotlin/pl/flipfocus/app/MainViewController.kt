package pl.flipfocus.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.initialize
import pl.flipfocus.data.LocalAnalyticsRecorder
import pl.flipfocus.data.LocalFocusRepository
import pl.flipfocus.data.FirebaseSocialRepository
import pl.flipfocus.data.StringStoreSocialOutboxStore
import pl.flipfocus.platform.IosFocusPlatform
import pl.flipfocus.platform.IosStringStore
import pl.flipfocus.presentation.FocusViewModel
import pl.flipfocus.presentation.SocialViewModel
import pl.flipfocus.presentation.FocusIntent
import pl.flipfocus.platform.invitationDuration

private val invitationLinks = Channel<Int>(Channel.BUFFERED)

fun HandleFocusLink(url: String) {
    invitationDuration(url)?.let { invitationLinks.trySend(it) }
}

fun MainViewController() = ComposeUIViewController {
    Firebase.initialize()
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val platform = remember { IosFocusPlatform() }
    val store = remember { IosStringStore() }
    val viewModel = remember {
        FocusViewModel(
            repository = LocalFocusRepository(store),
            timeProvider = platform.timeProvider,
            scope = scope,
            analytics = LocalAnalyticsRecorder(store),
            systemLanguage = platform.languageCode,
        )
    }
    val socialViewModel = remember { SocialViewModel(FirebaseSocialRepository(), scope, companyOutbox = StringStoreSocialOutboxStore(store)) }
    DisposableEffect(Unit) {
        onDispose {
            platform.dispose()
            socialViewModel.close()
            scope.launch {
                try { viewModel.flushAndClose() } finally { scope.cancel() }
            }
        }
    }
    LaunchedEffect(viewModel) {
        for (minutes in invitationLinks) viewModel.accept(FocusIntent.SelectDuration(minutes))
    }
    App(viewModel, platform, socialViewModel)
}
