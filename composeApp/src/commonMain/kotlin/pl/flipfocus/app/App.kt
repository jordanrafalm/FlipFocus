package pl.flipfocus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.flipfocus.domain.*
import pl.flipfocus.platform.FocusPlatform
import pl.flipfocus.platform.ReminderPermission
import pl.flipfocus.presentation.*

/** Only a currently-live, opted-in FRIENDS room may enrich the interruption surface; never fabricated data. */
private fun liveClanInfo(socialViewModel: SocialViewModel?): pl.flipfocus.platform.LiveClanInfo? {
    val social = socialViewModel?.state?.value ?: return null
    val profile = social.connection?.profile ?: return null
    if (!profile.liveOptIn) return null
    val room = social.rooms.firstOrNull { !it.closed && it.kind == RoomKind.FRIENDS && it.liveOptIn } ?: return null
    return pl.flipfocus.platform.LiveClanInfo(
        roomName = room.name, memberCount = room.memberCount, liveNicknames = room.live.map { it.nickname })
}

/** Native hosts own the VM. This root collects effects and forwards platform events. */
@Composable
fun App(viewModel: FocusViewModel, platform: FocusPlatform, socialViewModel: SocialViewModel? = null) {
    val state by viewModel.state.collectAsState()
    val copy = Copy.from(state.settings.language, platform.languageCode)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var customDuration by remember { mutableStateOf(false) }
    var deleteHistory by remember { mutableStateOf(false) }
    var extraScreen by remember { mutableStateOf<ExtraScreen?>(null) }
    val destination = currentDestination(state.screen, extraScreen)
    fun navigate(target: AppDestination) {
        when (val action = destinationAction(target)) {
            is DestinationAction.OpenVmScreen -> { extraScreen = null; viewModel.accept(FocusIntent.OpenScreen(action.screen)) }
            is DestinationAction.ShowExtra -> extraScreen = action.extra
        }
    }
    val currentSettings by rememberUpdatedState(state.settings)
    val currentCopy by rememberUpdatedState(copy)
    val currentState by rememberUpdatedState(state)
    val foreground by platform.isForeground.collectAsState()
    val monitoringEnabled = foreground && !state.isLoading && destination == AppDestination.FOCUS &&
        state.breakRemainingMillis == null && state.session?.isTerminal == false && !customDuration

    LaunchedEffect(platform, viewModel) {
        platform.isForeground.collect { viewModel.accept(FocusIntent.ForegroundChanged(it)) }
    }
    // Keyed on id+state (not the whole session) so per-second timer ticks don't re-fire this;
    // SocialViewModel.focusChanged is itself idempotent against replays of the same transition.
    LaunchedEffect(socialViewModel, state.session?.id, state.session?.state) {
        socialViewModel?.accept(SocialIntent.FocusChanged(state.session))
    }
    LaunchedEffect(platform, viewModel) {
        platform.monitoring.collect { reading ->
            when {
                reading.availability != SensorAvailability.AVAILABLE -> viewModel.accept(FocusIntent.MeasurementUnavailable(
                    currentCopy.text("Pomiar jest niedostępny. Sprawdź uprawnienia i położenie telefonu.",
                        "Measurement is unavailable. Check permissions and your phone's position.")))
                reading.orientation == DeviceOrientation.FACE_DOWN -> viewModel.accept(FocusIntent.PhonePositionChanged(true))
                reading.orientation == DeviceOrientation.LIFTED -> viewModel.accept(FocusIntent.PhonePositionChanged(false))
                currentState.session?.state == FocusState.FOCUSING -> viewModel.accept(FocusIntent.MeasurementUnavailable(
                    currentCopy.text("Pomiar został wstrzymany.", "Measurement paused.")))
            }
        }
    }
    DisposableEffect(platform, monitoringEnabled, state.session?.id) {
        if (monitoringEnabled) platform.startMonitoring() else platform.stopMonitoring()
        onDispose { platform.stopMonitoring() }
    }
    DisposableEffect(platform, state.session?.state, foreground) {
        platform.keepScreenAwake(foreground && state.session?.state in setOf(FocusState.FOCUSING, FocusState.GRACE_PERIOD))
        onDispose { platform.keepScreenAwake(false) }
    }
    LaunchedEffect(viewModel, platform) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FocusEffect.Feedback -> platform.feedback(
                    pl.flipfocus.platform.FeedbackKind.valueOf(effect.kind.name), currentSettings.soundEnabled, currentSettings.hapticsEnabled)
                is FocusEffect.ExportReady -> platform.shareExport(effect.content, effect.fileName)
                is FocusEffect.Message -> scope.launch { snackbar.showSnackbar(effect.text) }
                is FocusEffect.ReminderPreferenceChanged -> {
                    val permission = platform.setRemindersEnabled(effect.enabled, effect.hour)
                    if (effect.enabled && permission != ReminderPermission.GRANTED) {
                        viewModel.accept(FocusIntent.UpdateSettings(currentSettings.copy(remindersEnabled = false)))
                        scope.launch { snackbar.showSnackbar(currentCopy.text("Przypomnienia wymagają zgody w ustawieniach urządzenia.", "Allow notifications in device settings to enable reminders.")) }
                    }
                }
                is FocusEffect.LockScreenInterruption -> platform.publishInterruption(effect, liveClanInfo(socialViewModel))
            }
        }
    }

    FocusTheme {
        val interrupted = state.session?.state == FocusState.GRACE_PERIOD
        Box(Modifier.fillMaxSize().background(FocusColors.background)) {
            if (interrupted) Box(Modifier.fillMaxSize().background(Brush.radialGradient(
                listOf(FocusColors.amber.copy(alpha = 0.095f), Color.Transparent))))
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                val contentModifier = if (maxWidth > maxHeight) Modifier.fillMaxSize()
                else Modifier.widthIn(max = 560.dp).fillMaxSize()
                Column(contentModifier) {
                    if (state.storageError != null || state.sensorError != null) {
                        ErrorBanner(state, copy, viewModel::accept)
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        if (state.isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else {
                            SwipeableDestinationHost(destination, ::navigate, Modifier.fillMaxSize()) { current ->
                                Box(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                                    when (current) {
                                        AppDestination.CLAN -> if (socialViewModel != null) ClanRoute(socialViewModel, copy, { navigate(AppDestination.FOCUS) }, platform::shareText, onGoogleSignIn = platform::googleSignIn)
                                        else ClanWelcome(copy, { navigate(AppDestination.FOCUS) }, {
                                            platform.shareText(copy.text("Dołącz do mnie na 25 minut spokojnej pracy. Odłóż telefon ekranem do dołu. FlipFocus: flipfocus://session?minutes=25",
                                                "Join me for 25 minutes of quiet focus. Put your phone screen-down. FlipFocus: flipfocus://session?minutes=25"))
                                        })
                                        AppDestination.HISTORY -> HistoryScreen(state, copy, viewModel::accept, { deleteHistory = true })
                                        AppDestination.SETTINGS -> SettingsScreen(state, copy, viewModel::accept, platform::openSystemSettings)
                                        AppDestination.FOCUS -> when {
                                            state.breakRemainingMillis != null -> BreakScreen(state.breakRemainingMillis ?: 0, copy, { viewModel.accept(FocusIntent.EndBreak) })
                                            state.session?.state == FocusState.COMPLETED -> CompletedScreen(state, copy, viewModel::accept)
                                            state.session?.state == FocusState.ABANDONED -> AbandonedScreen(state, copy, viewModel::accept)
                                            else -> FocusHomeScreen(state, copy, viewModel::accept, onLogoLongPress = { customDuration = true })
                                        }
                                    }
                                }
                            }
                            BottomNavBar(destination, copy, ::navigate, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = FocusSpace.touch + FocusSpace.section, start = 16.dp, end = 16.dp))
            }
        }
        if (customDuration) CustomDurationDialog(state.selectedDurationMinutes, copy,
            { customDuration = false }, { viewModel.accept(FocusIntent.SelectDuration(it)); customDuration = false })
        if (deleteHistory) AlertDialog(
            onDismissRequest = { deleteHistory = false }, title = { Text(copy.text("Usunąć historię?", "Delete history?")) },
            text = { Text(copy.text("Usuniesz zapisane sesje oraz wynikającą z nich passę z tego urządzenia. Tej czynności nie można cofnąć.",
                "This removes saved sessions and their streak from this device. This can't be undone.")) },
            confirmButton = { TextButton({ viewModel.accept(FocusIntent.ClearHistory); deleteHistory = false }) { Text(copy.text("Usuń", "Delete")) } },
            dismissButton = { TextButton({ deleteHistory = false }) { Text(copy.cancel) } },
        )
    }
}

@Composable
private fun ErrorBanner(state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit) {
    Surface(color = FocusColors.amberSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(state.storageError ?: state.sensorError.orEmpty(), style = MaterialTheme.typography.bodySmall)
            if (state.storageError != null) TextButton({ onIntent(FocusIntent.RetryStorage) }) { Text(copy.text("Ponów zapis / odczyt", "Retry storage")) }
        }
    }
}

@Composable
private fun CustomDurationDialog(initial: Int, copy: Copy, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var input by remember { mutableStateOf(initial.toString()) }
    val minutes = input.toIntOrNull()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(copy.text("Twój czas skupienia", "Your focus time")) },
        text = {
            OutlinedTextField(input, { input = it.filter(Char::isDigit).take(3) }, singleLine = true,
                label = { Text(copy.text("Minuty (1–180)", "Minutes (1–180)")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = minutes !in 1..180)
        }, confirmButton = { TextButton({ minutes?.let(onConfirm) }, enabled = minutes in 1..180) { Text(copy.done) } },
        dismissButton = { TextButton(onDismiss) { Text(copy.cancel) } })
}

@Composable
private fun ClanWelcome(copy: Copy, onBack: () -> Unit, onInvite: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PageHeader("Focus Clan", copy, onBack)
        Text(copy.text("Skupienie. Wspólnie.", "Focus. Together."), style = MaterialTheme.typography.headlineLarge)
        Text(copy.text("Zaproś kogoś na spokojne 25 minut. Twój osobisty rytm pozostaje prywatny.",
            "Invite someone for 25 quiet minutes. Your personal rhythm stays private."), color = FocusColors.muted)
        FocusButton(copy.text("Zaproś do sesji", "Invite to a session"), onInvite)
    }
}
