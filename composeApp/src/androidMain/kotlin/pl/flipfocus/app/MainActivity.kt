package pl.flipfocus.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.flipfocus.data.LocalAnalyticsRecorder
import pl.flipfocus.data.LocalFocusRepository
import pl.flipfocus.data.FirebaseSocialRepository
import pl.flipfocus.data.StringStoreSocialOutboxStore
import pl.flipfocus.platform.AndroidFocusInterruption
import pl.flipfocus.platform.AndroidFocusPlatform
import pl.flipfocus.platform.AndroidReminders
import pl.flipfocus.platform.AndroidStringStore
import pl.flipfocus.platform.invitationDuration
import pl.flipfocus.presentation.FocusIntent
import pl.flipfocus.presentation.FocusViewModel
import pl.flipfocus.presentation.SocialViewModel

class MainActivity : ComponentActivity() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var platform: AndroidFocusPlatform
    private lateinit var viewModel: FocusViewModel
    private lateinit var socialViewModel: SocialViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        platform = AndroidFocusPlatform(this)
        val store = AndroidStringStore(this)
        viewModel = FocusViewModel(
            repository = LocalFocusRepository(store),
            timeProvider = platform.timeProvider,
            scope = applicationScope,
            analytics = LocalAnalyticsRecorder(store),
            systemLanguage = platform.languageCode,
        )
        socialViewModel = SocialViewModel(FirebaseSocialRepository(), applicationScope, companyOutbox = StringStoreSocialOutboxStore(store))
        AndroidReminders.restore(this)
        selectInvitedDuration(intent)
        handleInterruptionAction(intent)
        setContent { App(viewModel, platform, socialViewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectInvitedDuration(intent)
        handleInterruptionAction(intent)
    }

    private fun selectInvitedDuration(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) invitationDuration(intent.dataString)?.let {
            viewModel.accept(FocusIntent.SelectDuration(it))
        }
    }

    /** RESUME only brings the app forward; the sensor — not this tap — decides whether focus actually resumes. */
    private fun handleInterruptionAction(intent: Intent?) {
        when (intent?.action) {
            AndroidFocusInterruption.ACTION_ABANDON -> viewModel.accept(FocusIntent.Abandon)
            AndroidFocusInterruption.ACTION_RESUME -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        platform.foregroundChanged(true)
    }

    override fun onPause() {
        // Enqueue the interruption before the OS can suspend sensor callbacks.
        viewModel.accept(FocusIntent.ForegroundChanged(false))
        platform.foregroundChanged(false)
        super.onPause()
    }

    override fun onDestroy() {
        platform.dispose()
        socialViewModel.close()
        applicationScope.launch {
            try { viewModel.flushAndClose() } finally { applicationScope.cancel() }
        }
        super.onDestroy()
    }
}
