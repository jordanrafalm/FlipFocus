@file:OptIn(kotlin.time.ExperimentalTime::class)

package pl.flipfocus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import kotlin.time.Instant
import pl.flipfocus.domain.*
import pl.flipfocus.presentation.*

@Composable
fun PageHeader(title: String, copy: Copy, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ ${copy.back}", color = FocusColors.muted) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
fun HistoryScreen(state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = FocusSpace.page), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(copy.history, copy, { onIntent(FocusIntent.OpenScreen(FocusScreen.FOCUS)) })
        FocusCard {
            StatRow(copy.totalToday, copy.time(state.dailyProgress.totalFocusedMillis))
            Spacer(Modifier.height(16.dp))
            StatRow(copy.text("Najdłuższa passa", "Longest streak"), copy.days(state.dailyProgress.longestStreakDays), FocusColors.lime)
        }
        if (state.history.isEmpty()) {
            Text(copy.text("Tu pojawi się Twój rytm. Odłóż telefon i daj sobie chwilę skupienia.",
                "Your rhythm starts here. Put your phone down and make a little room to focus."),
                style = MaterialTheme.typography.bodyLarge, color = FocusColors.muted, modifier = Modifier.padding(vertical = 32.dp))
        }
        state.history.sortedByDescending { it.endedAtEpochMillis }.forEach { entry ->
            val date = Instant.fromEpochMilliseconds(entry.endedAtEpochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
            FocusCard {
                StatRow("${date.date} · ${date.hour.toString().padStart(2, '0')}:${date.minute.toString().padStart(2, '0')}",
                    copy.time(entry.focusedMillis))
                Spacer(Modifier.height(8.dp))
                Text(if (entry.outcome == FocusState.COMPLETED) copy.text("Ukończona sesja", "Completed session")
                    else copy.text("Przerwana · bez zaliczenia dnia", "Interrupted · doesn't count toward streak"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.outcome == FocusState.COMPLETED) FocusColors.green else FocusColors.amber)
            }
        }
        if (state.history.isNotEmpty()) {
            TextButton(onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(copy.text("Usuń historię z urządzenia", "Delete history from this device"), color = FocusColors.amber)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SettingsScreen(state: FocusUiState, copy: Copy, onIntent: (FocusIntent) -> Unit, onSystemSettings: () -> Unit, modifier: Modifier = Modifier) {
    val settings = state.settings
    fun update(next: FocusSettings) = onIntent(FocusIntent.UpdateSettings(next))
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = FocusSpace.page), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(copy.settings, copy, { onIntent(FocusIntent.OpenScreen(FocusScreen.FOCUS)) })
        SectionLabel(copy.text("TWÓJ RYTUAŁ", "YOUR RITUAL"))
        FocusCard {
            SettingsToggle(copy.text("Dźwięki", "Sounds"), copy.text("Dźwięki startu, przerwania i wznowienia skupienia", "Sounds when focus starts, is interrupted or resumes"), settings.soundEnabled, onChange = { update(settings.copy(soundEnabled = it)) })
            SettingsToggle(copy.text("Wibracje", "Haptics"), copy.text("Dyskretne potwierdzenie gestu", "A gentle confirmation of your gesture"), settings.hapticsEnabled, onChange = { update(settings.copy(hapticsEnabled = it)) })
            SettingsToggle(copy.text("Przypomnienie", "Reminder"), copy.text("W dni robocze o wybranej godzinie. Pomija weekendy i święta ustawowe.", "On workdays at the selected time. Skips weekends and statutory holidays."), settings.remindersEnabled, onChange = { update(settings.copy(remindersEnabled = it)) })
            Text(copy.text("Godzina przypomnienia", "Reminder time"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (8..18).forEach { hour ->
                    FilterChip(settings.reminderHour == hour, { update(settings.copy(reminderHour = hour)) },
                        label = { Text("${hour.toString().padStart(2, '0')}:00") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        FocusCard {
            Text(copy.text("Dzień zalicza ukończona sesja trwająca co najmniej 15 minut.",
                "A completed session of at least 15 minutes counts toward your streak."), color = FocusColors.muted, style = MaterialTheme.typography.bodySmall)
        }
        SectionLabel(copy.text("PRYWATNOŚĆ", "PRIVACY"))
        FocusCard {
            Text(copy.text("Twoje skupienie należy do Ciebie.", "Your focus belongs to you."), style = MaterialTheme.typography.titleMedium)
            Text(copy.text("Historia pozostaje na urządzeniu. Udostępnianie w pokojach jest osobne i dobrowolne.",
                "Your history stays on this device. Sharing in rooms is separate and optional."), color = FocusColors.muted, style = MaterialTheme.typography.bodyMedium)
            SettingsToggle(copy.text("Lokalne statystyki jakości", "Local quality counters"),
                copy.text("Anonimowe liczniki zdarzeń. Bez wysyłania danych. Wyłączenie usuwa liczniki.",
                    "Event counts only, never uploaded. Turning this off clears the counters."), settings.analyticsEnabled, onChange = { update(settings.copy(analyticsEnabled = it)) })
        }
        SectionLabel(copy.text("JĘZYK", "LANGUAGE"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { language ->
                FilterChip(settings.language == language, { update(settings.copy(language = language)) },
                    label = { Text(when (language) { AppLanguage.SYSTEM -> "System"; AppLanguage.PL -> "Polski"; AppLanguage.EN -> "English" }) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
        FocusCard {
            Text(copy.text("Pomiar położenia", "Phone position"), style = MaterialTheme.typography.titleMedium)
            Text(copy.text("Połóż telefon płasko, ekranem do dołu. Podczas sesji ekran nie wygasza się sam. Blokada lub zmiana aplikacji wstrzyma pomiar i uruchomi czas na powrót.",
                "Lay your phone flat, screen-down. The display stays awake during a session. Locking your phone or switching apps pauses measurement and starts the return window."),
                color = FocusColors.muted, style = MaterialTheme.typography.bodyMedium)
            TextButton({ onIntent(FocusIntent.ReportMeasurementIssue) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(copy.text("Pomoc z pomiarem", "Measurement help"))
            }
            TextButton(onSystemSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.text("Ustawienia urządzenia", "Device settings")) }
        }
        Text("FlipFocus · 0.1.1", style = MaterialTheme.typography.bodySmall, color = FocusColors.muted, modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = FocusColors.muted, modifier = modifier)
}

@Composable
fun InsightsScreen(state: FocusUiState, copy: Copy, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val today = state.dailyProgress.today
    val calendar = ProgressCalculator.calendar(state.history, TimeZone.currentSystemDefault())
    val days = (6 downTo 0).map { today.minus(it, DateTimeUnit.DAY) }
    val totals = days.map { date -> calendar.find { it.date == date }?.totalFocusedMillis ?: 0 }
    val maxTime = totals.maxOrNull()?.coerceAtLeast(60_000L) ?: 60_000L
    val thisWeek = totals.sum()
    val lastWeek = calendar.filter { it.date >= today.minus(13, DateTimeUnit.DAY) && it.date < today.minus(6, DateTimeUnit.DAY) }.sumOf { it.totalFocusedMillis }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = FocusSpace.page), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PageHeader(copy.text("Twój rytm", "Your rhythm"), copy, onBack)
        Text(copy.text("Małe chwile.\nWażne rzeczy.", "Little moments.\nMeaningful things."), style = MaterialTheme.typography.headlineLarge)
        FocusCard {
            StatRow(copy.text("Ostatnie 7 dni", "Last 7 days"), copy.time(thisWeek), FocusColors.green)
            Row(Modifier.fillMaxWidth().padding(top = 24.dp).height(148.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                days.zip(totals).forEach { (date, total) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$date: ${copy.time(total)}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().height((100f * total.toFloat() / maxTime).coerceAtLeast(3f).dp)
                            .clip(RoundedCornerShape(6.dp)).background(if (date == today) FocusColors.green else FocusColors.greenSurface))
                        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = FocusColors.border)
            StatRow(copy.text("Poprzednie 7 dni", "Previous 7 days"), copy.time(lastWeek))
        }
        FocusCard {
            StatRow(copy.text("Ukończone sesje", "Completed sessions"), state.history.count { it.outcome == FocusState.COMPLETED }.toString())
            Spacer(Modifier.height(16.dp))
            StatRow(copy.text("Najdłuższa passa", "Longest streak"), copy.days(state.dailyProgress.longestStreakDays), FocusColors.lime)
        }
        Text(copy.text("To przestrzeń na zauważenie własnego rytmu. Przerwy i dni odpoczynku są jego częścią.",
            "A space to notice your own rhythm. Breaks and rest days are part of it."), style = MaterialTheme.typography.bodyLarge, color = FocusColors.muted)
        Spacer(Modifier.height(16.dp))
    }
}
