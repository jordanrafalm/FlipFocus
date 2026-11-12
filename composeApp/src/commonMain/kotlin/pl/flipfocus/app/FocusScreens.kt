package pl.flipfocus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.draw.clipToBounds
import pl.flipfocus.platform.PlatformVideoPlayer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.flipfocus.domain.FocusState
import pl.flipfocus.presentation.*
import pl.flipfocus.resources.*

private const val START_PROMPT_SECONDS = 10

/** https://www.figma.com/design/Uvw2EdJMOEZ5XV7JagGpmd/Untitled?node-id=2-442 */
private const val LOGO_LONG_PRESS_MILLIS = 4_000L

@Composable
fun FocusHomeScreen(
    state: FocusUiState,
    copy: Copy,
    onIntent: (FocusIntent) -> Unit,
    modifier: Modifier = Modifier,
    onLogoLongPress: () -> Unit = {},
    video: ByteArray? = null,
    onVideoEnded: () -> Unit = {},
) {
    val session = state.session
    val interrupted = session?.state == FocusState.GRACE_PERIOD
    val focusing = session?.state == FocusState.FOCUSING
    var startPromptTaps by remember { mutableStateOf(0) }
    var startPromptSecondsLeft by remember { mutableStateOf(0) }
    LaunchedEffect(startPromptTaps) {
        if (startPromptTaps == 0) return@LaunchedEffect
        startPromptSecondsLeft = START_PROMPT_SECONDS
        while (startPromptSecondsLeft > 0) {
            delay(1_000)
            startPromptSecondsLeft -= 1
        }
    }
    LaunchedEffect(interrupted, focusing) {
        if (interrupted || focusing) startPromptSecondsLeft = 0
    }
    Column(modifier.fillMaxSize()) {
        FocusHeader(state, copy, { onIntent(FocusIntent.OpenScreen(FocusScreen.HISTORY)) }, onLogoLongPress)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth > maxHeight) {
                FocusHomeLandscape(
                    state, copy, onIntent, interrupted, focusing,
                    startPromptSecondsLeft, { startPromptTaps += 1 }, video, onVideoEnded,
                )
            } else {
                FocusHomePortrait(
                    state, copy, onIntent, interrupted, focusing,
                    startPromptSecondsLeft, { startPromptTaps += 1 }, maxHeight, video, onVideoEnded,
                )
            }
        }
    }
}

@Composable
private fun FocusHomePortrait(
    state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit,
    interrupted: Boolean, focusing: Boolean, startPromptSecondsLeft: Int, onTimerTap: () -> Unit,
    availableHeight: androidx.compose.ui.unit.Dp, video: ByteArray?, onVideoEnded: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = availableHeight)
            .padding(horizontal = FocusSpace.page),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        FocusTimerSection(state, copy, interrupted, focusing, startPromptSecondsLeft, onTimerTap, video, onVideoEnded)
        FocusHomeDetails(state, copy, onIntent, interrupted, focusing)
        FocusHomeSessionState(state, copy, onIntent, interrupted, focusing)
    }
}

@Composable
private fun FocusHomeLandscape(
    state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit,
    interrupted: Boolean, focusing: Boolean, startPromptSecondsLeft: Int, onTimerTap: () -> Unit,
    video: ByteArray?, onVideoEnded: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(horizontal = FocusSpace.page), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            FocusTimerSection(state, copy, interrupted, focusing, startPromptSecondsLeft, onTimerTap, video, onVideoEnded)
            FocusHomeSessionState(state, copy, onIntent, interrupted, focusing)
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            FocusHomeDetails(state, copy, onIntent, interrupted, focusing)
            if (!interrupted && !focusing) FlipInstruction(copy, Modifier.padding(top = 40.dp, bottom = 64.dp))
        }
    }
}

@Composable
private fun FocusTimerSection(
    state: FocusUiState, copy: Copy, interrupted: Boolean, focusing: Boolean,
    startPromptSecondsLeft: Int, onTimerTap: () -> Unit, video: ByteArray?, onVideoEnded: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        if (video != null) {
            // Same footprint as the timer ring; the 16:9 clip is enlarged and cropped to that square.
            Box(Modifier.padding(top = 4.dp).widthIn(max = FocusSpace.timer).fillMaxWidth().aspectRatio(1f).clipToBounds(),
                contentAlignment = Alignment.Center) {
                PlatformVideoPlayer(video, onVideoEnded, Modifier.requiredSize(746.dp, 420.dp))
            }
        } else TimerRing(
            state.session?.remainingMillis ?: state.selectedDurationMinutes * 60_000L,
            fraction = 1f - (state.session?.progress ?: 0f),
            color = if (interrupted) FocusColors.amber else FocusColors.green,
            label = if (interrupted || focusing) copy.left else null,
            modifier = Modifier.padding(top = 4.dp).then(
                if (!interrupted && !focusing) Modifier.clickable(onClick = onTimerTap) else Modifier
            ),
        )
    }
    if (startPromptSecondsLeft > 0) {
        Spacer(Modifier.height(16.dp))
        Text(copy.startPrompt(startPromptSecondsLeft), style = MaterialTheme.typography.bodyLarge,
            color = FocusColors.green, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FocusHomeDetails(
    state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit,
    interrupted: Boolean, focusing: Boolean,
) {
    if (!interrupted && !focusing) {
        Spacer(Modifier.height(24.dp))
        DurationSelector(state.selectedDurationMinutes, { onIntent(FocusIntent.SelectDuration(it)) },
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FocusHomeSessionState(
    state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit, interrupted: Boolean, focusing: Boolean,
) {
    when {
        interrupted -> InterruptedMessage(state, copy, Modifier.padding(top = 28.dp, bottom = 24.dp))
        focusing -> Column(Modifier.padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(copy.text("Czas na Twoją uwagę.", "Space for your attention."), style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(copy.text("Telefon odpoczywa. Ty rób swoje.", "Your phone is resting. Do your thing."),
                style = MaterialTheme.typography.bodyMedium, color = FocusColors.muted, textAlign = TextAlign.Center)
        }
        else -> FlipInstruction(copy, Modifier.padding(top = 40.dp, bottom = 64.dp))
    }
    if (interrupted || focusing) {
        TextButton(onClick = { onIntent(FocusIntent.Abandon) }, modifier = Modifier.padding(bottom = 4.dp).heightIn(min = 48.dp)) {
            Text(copy.giveUp, style = MaterialTheme.typography.bodyMedium, color = FocusColors.muted)
        }
    }
}

@Composable
private fun FocusHeader(state: FocusUiState, copy: Copy, onHistory: () -> Unit, onLogoLongPress: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = FocusSpace.page),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("FlipFocus", style = MaterialTheme.typography.headlineSmall, color = FocusColors.text,
            modifier = Modifier.pointerInput(onLogoLongPress) {
                awaitEachGesture {
                    awaitFirstDown()
                    // Released or cancelled before 4s returns a non-null value; only a full hold triggers.
                    val released = withTimeoutOrNull(LOGO_LONG_PRESS_MILLIS) { waitForUpOrCancellation() }
                    if (released == null) onLogoLongPress()
                }
            })
        StreakBadge(state.dailyProgress.currentStreakDays, copy, onHistory,
            color = if (state.session?.state == FocusState.GRACE_PERIOD) FocusColors.amber else FocusColors.lime)
    }
}

@Composable
fun FlipInstruction(copy: Copy, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AssetIcon(Res.drawable.phone_up, Modifier.size(40.dp, 58.dp))
            AssetIcon(Res.drawable.rotate_phone, Modifier.size(38.dp, 24.dp))
            Box(Modifier.size(40.dp, 58.dp).clip(RoundedCornerShape(8.dp)).background(FocusColors.raised)
                .border(1.5.dp, FocusColors.green, RoundedCornerShape(8.dp)))
        }
        Text(copy.text("Odłóż telefon ekranem do dołu,\nżeby rozpocząć skupienie", "Flip your phone screen-down on the desk to start focus"),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp))
        Text(copy.text("EKRAN W DÓŁ · CZAS RUSZY SAM", "FACE DOWN · TIMER STARTS AUTOMATICALLY"),
            style = MaterialTheme.typography.bodySmall, color = FocusColors.muted, textAlign = TextAlign.Center)
    }
}

/** https://www.figma.com/design/Uvw2EdJMOEZ5XV7JagGpmd/Untitled?node-id=2-478 */
@Composable
fun InterruptedMessage(state: FocusUiState, copy: Copy, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).background(FocusColors.amberSurface, CircleShape), contentAlignment = Alignment.Center) {
            AssetIcon(Res.drawable.alert_circle, Modifier.size(22.dp))
        }
        Text(copy.text("Skupienie wstrzymane.\nTelefon został podniesiony.", "Focus Paused! You picked up your phone."),
            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(copy.text("Odłóż go z powrotem, aby kontynuować. Twój dotychczasowy postęp zostaje.",
            "Flip screen-down to keep going. Your previous progress is safe."),
            style = MaterialTheme.typography.bodyLarge, color = FocusColors.muted, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(copy.text("ODŁÓŻ TELEFON", "RETURN PHONE"), style = MaterialTheme.typography.bodySmall, color = FocusColors.amber)
            Text("${(state.graceRemainingMillis + 999) / 1000}s", style = MaterialTheme.typography.titleLarge)
        }
        LinearProgressIndicator(
            progress = { (state.graceRemainingMillis.toFloat() / (state.session?.gracePeriodMillis ?: 10_000L)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = FocusColors.amber, trackColor = FocusColors.raised, drawStopIndicator = {},
        )
    }
}

/** https://www.figma.com/design/Uvw2EdJMOEZ5XV7JagGpmd/Untitled?node-id=2-512 */
@Composable
fun CompletedScreen(state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val minimumHeight = maxHeight
        AssetIcon(Res.drawable.celebration_aura, Modifier.size(340.dp).align(Alignment.TopCenter).offset(y = 60.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = minimumHeight)
            .padding(horizontal = FocusSpace.page).padding(top = 42.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(132.dp).background(FocusColors.surface, CircleShape)
                    .border(2.dp, FocusColors.green, CircleShape), contentAlignment = Alignment.Center) {
                    AssetIcon(Res.drawable.check_circle, Modifier.size(54.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(copy.focusComplete, color = FocusColors.green, style = MaterialTheme.typography.labelSmall)
                    Text(copy.completed(state.session?.duration?.minutes ?: state.selectedDurationMinutes),
                        style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                }
            }
            FocusCard(Modifier.padding(vertical = 48.dp)) {
                StatRow(copy.totalToday, copy.time(state.dailyProgress.totalFocusedMillis))
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = FocusColors.border)
                StatRow(copy.streakKept, "🔥 ${copy.days(state.dailyProgress.currentStreakDays)}", FocusColors.lime)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FocusButton(copy.breakAction, { onIntent(FocusIntent.StartBreak()) })
                Spacer(Modifier.height(4.dp))
                TextButton({ onIntent(FocusIntent.NewSession) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(copy.done, color = FocusColors.muted)
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String, color: Color = FocusColors.text, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = FocusColors.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

@Composable
fun BreakScreen(remainingMillis: Long, copy: Copy, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FocusSpace.page),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Text(copy.text("Chwila oddechu", "A little breathing space"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        TimerRing(remainingMillis, remainingMillis / 300_000f, FocusColors.green)
        Spacer(Modifier.height(32.dp))
        Text(copy.text("Przeciągnij się. Napij się wody.\nTwoja uwaga też potrzebuje odpoczynku.",
            "Stretch. Get some water.\nYour attention needs a rest, too."), color = FocusColors.muted,
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        FocusButton(copy.newSession, onFinish)
    }
}

@Composable
fun AbandonedScreen(state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FocusSpace.page),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        TimerRing(state.session?.remainingMillis ?: 0, 1f - (state.session?.progress ?: 0f), FocusColors.amber)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 32.dp)) {
            Text(copy.text("Możesz zacząć od nowa.", "A fresh start is always here."), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(copy.text("Ta sesja została zakończona. Wcześniejsze sesje i passa pozostają bez zmian.",
                "This session has ended. Your earlier sessions and streak are safe."),
                color = FocusColors.muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        FocusButton(copy.text("Spróbuj ponownie", "Try again"), { onIntent(FocusIntent.NewSession) })
    }
}
