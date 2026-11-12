@file:OptIn(kotlin.time.ExperimentalTime::class)

package pl.flipfocus.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.random.Random
import pl.flipfocus.domain.*

/** A single intent actor serializes sensors, lifecycle, user actions and persistence. */
class FocusViewModel(
    private val repository: FocusRepository,
    private val timeProvider: TimeProvider,
    scope: CoroutineScope,
    autoTick: Boolean = true,
    private val analytics: AnalyticsRecorder? = null,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val systemLanguage: String = "pl",
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val workScope = CoroutineScope(scope.coroutineContext + job)
    private sealed interface Request {
        data class Action(val intent: FocusIntent) : Request
        data class Shutdown(val completion: CompletableDeferred<Unit>) : Request
    }
    private val intents = Channel<Request>(Channel.UNLIMITED)
    private val effectChannel = Channel<FocusEffect>(Channel.BUFFERED)
    private val mutableState = MutableStateFlow(FocusUiState())
    val state: StateFlow<FocusUiState> = mutableState.asStateFlow()
    val effects: Flow<FocusEffect> = effectChannel.receiveAsFlow()

    private var snapshot = FocusSnapshot()
    private var storageReady = false
    private var dirty = false
    private var lastCheckpoint = 0L
    private var breakDeadline: Long? = null
    private var breakNotified = false
    private var interruptionSessionId: String? = null
    private var interruptionDeadline: Long? = null
    private val dismissedPrompts = mutableSetOf<FeedbackPrompt>()
    private var countedReturnDate: LocalDate? = null

    init {
        workScope.launch {
            initialize()
            for (request in intents) {
                if (request is Request.Shutdown) {
                    try {
                        snapshot.activeSession?.let { transition(FocusEngine.suspend(it, timeProvider.now()), forceSave = true) }
                    } finally { request.completion.complete(Unit) }
                    break
                }
                try {
                    handle((request as Request.Action).intent)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    emit(FocusEffect.Message(copy("Nie udało się wykonać tej czynności. Spróbuj ponownie.", "This action could not be completed. Please try again.")))
                }
            }
        }
        if (autoTick) workScope.launch {
            while (isActive) {
                delay(250)
                if (intents.trySend(Request.Action(FocusIntent.Tick)).isFailure) break
            }
        }
    }

    fun accept(intent: FocusIntent) { intents.trySend(Request.Action(intent)) }
    fun close() { intents.close(); job.cancel(); effectChannel.close() }

    /** Call from a scope that survives host disposal, before cancelling the injected parent scope. */
    suspend fun flushAndClose() {
        val completion = CompletableDeferred<Unit>()
        val cancellation = job.invokeOnCompletion { completion.complete(Unit) }
        if (intents.trySend(Request.Shutdown(completion)).isSuccess) {
            intents.close()
            completion.await()
        }
        cancellation.dispose()
        close()
    }

    private suspend fun initialize() {
        mutableState.update { it.copy(isLoading = true) }
        try {
            val loaded = repository.load()
            snapshot = loaded
            storageReady = true
            val now = timeProvider.now()
            val wasGraceBeforeRestore = loaded.activeSession?.state == FocusState.GRACE_PERIOD
            val restored = loaded.activeSession?.let { FocusEngine.restore(it, now) } ?: readySession()
            snapshot = loaded.withSession(restored)
            mutableState.update { it.copy(isLoading = false, storageError = null) }
            publish()
            if (snapshot != loaded) persist()
            countReturn()
            if (restored.state == FocusState.GRACE_PERIOD) syncInterruption(restored)
            // The previous process may have died mid grace period; clear any stale lock-screen surface for it.
            else if (wasGraceBeforeRestore) emitInterruption(InterruptionAction.END, loaded.activeSession!!.id, null)
            if (snapshot.settings.remindersEnabled) emit(FocusEffect.ReminderPreferenceChanged(true, snapshot.settings.reminderHour))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            storageReady = false
            mutableState.update { it.copy(isLoading = false, storageError = copy("Nie udało się odczytać danych. Zachowano zapis. Spróbuj ponownie.", "Saved data could not be read. The original has been kept. Please try again.")) }
        }
    }

    private suspend fun handle(intent: FocusIntent) {
        if (intent == FocusIntent.RetryStorage) {
            if (storageReady) persist() else initialize()
            return
        }
        if (intent is FocusIntent.OpenScreen) {
            mutableState.update { it.copy(screen = intent.screen) }
            return
        }
        if (!storageReady) return
        val now = timeProvider.now()
        val session = snapshot.activeSession ?: readySession()
        when (intent) {
            is FocusIntent.PhonePositionChanged -> {
                if (!state.value.isForeground && !session.isRunning) return
                val wasFaceDown = state.value.isFaceDown
                mutableState.update { it.copy(isFaceDown = intent.isFaceDown, sensorError = null) }
                // From a finished session or a break, only a fresh face-up -> face-down flip starts the next one,
                // so a phone still lying face-down after completion never restarts by itself.
                if (session.isTerminal || breakDeadline != null) {
                    if (intent.isFaceDown && !wasFaceDown) {
                        prepareNext()
                        snapshot.activeSession?.let { transition(FocusEngine.phoneDown(it, now)) }
                    }
                    return
                }
                if (breakDeadline == null) transition(
                    if (intent.isFaceDown) FocusEngine.phoneDown(session, now) else FocusEngine.phoneLifted(session, now))
            }
            is FocusIntent.ForegroundChanged -> {
                // A running session keeps measuring in the background (screen off); the host keeps the sensor alive.
                if (!intent.isForeground && session.isRunning) {
                    mutableState.update { it.copy(isForeground = false) }
                    return
                }
                mutableState.update { it.copy(isForeground = intent.isForeground, isFaceDown = if (session.isRunning) it.isFaceDown else false) }
                if (!intent.isForeground) transition(FocusEngine.suspend(session, now), forceSave = true)
                else {
                    transition(FocusEngine.tick(session, now))
                    countReturn()
                }
            }
            is FocusIntent.MeasurementUnavailable -> {
                mutableState.update { it.copy(sensorError = intent.message, isFaceDown = false) }
                transition(FocusEngine.suspend(session, now), forceSave = true)
            }
            is FocusIntent.SelectDuration -> {
                if (session.state in setOf(FocusState.FOCUSING, FocusState.GRACE_PERIOD)) return
                if (intent.minutes !in 1..180) {
                    emit(FocusEffect.Message(copy("Wybierz czas od 1 do 180 minut.", "Choose a duration between 1 and 180 minutes.")))
                    return
                }
                snapshot = snapshot.copy(selectedDurationMinutes = intent.minutes)
                prepareNext()
            }
            is FocusIntent.UpdateSettings -> updateSettings(intent.settings)
            FocusIntent.Abandon -> transition(FocusEngine.abandon(session, now), forceSave = true)
            FocusIntent.NewSession, FocusIntent.EndBreak -> {
                if (session.state !in setOf(FocusState.FOCUSING, FocusState.GRACE_PERIOD)) prepareNext()
            }
            is FocusIntent.StartBreak -> {
                if (!session.isTerminal || intent.durationMinutes !in 1..60) return
                breakDeadline = now.monotonicMillis + intent.durationMinutes * 60_000L
                breakNotified = false
                mutableState.update { it.copy(screen = FocusScreen.FOCUS) }
                publish()
            }
            FocusIntent.Tick -> {
                transition(FocusEngine.tick(session, now))
                val remaining = breakDeadline?.let { (it - now.monotonicMillis).coerceAtLeast(0) }
                if (remaining == 0L && !breakNotified) {
                    breakNotified = true
                    emit(FocusEffect.Message(copy("Przerwa dobiegła końca. Możesz rozpocząć kolejną sesję.", "Your break is over. You can start another session.")))
                    emit(FocusEffect.Feedback(FeedbackKind.COMPLETED))
                }
            }
            FocusIntent.ClearHistory -> {
                try {
                    // Save a pending current checkpoint before deleting history so no active progress is rolled back.
                    if (dirty && !persist()) return
                    snapshot = repository.clearHistory()
                    if (snapshot.activeSession == null) snapshot = snapshot.withSession(readySession())
                    mutableState.update { it.copy(feedbackPrompt = null, storageError = null) }
                    publish()
                    emit(FocusEffect.Message(copy("Historia została usunięta z tego urządzenia.", "History has been deleted from this device.")))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    // The deletion barrier may have committed even if a later snapshot write failed.
                    try { snapshot = repository.load(); publish() } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { mutableState.update { it.copy(history = emptyList()) } }
                    mutableState.update { it.copy(storageError = copy("Nie udało się zakończyć usuwania historii. Spróbuj ponownie.", "History deletion could not be completed. Please try again.")) }
                }
            }
            is FocusIntent.ExportHistory -> emit(FocusEffect.ExportReady(
                HistoryExport.format(snapshot.history, intent.format), "flipfocus-history.${intent.format.name.lowercase()}"))
            FocusIntent.ReportMeasurementIssue -> {
                record(AnalyticsEvent.MEASUREMENT_PROBLEM)
                emit(FocusEffect.Message(copy("Spróbuj położyć telefon płasko, ekranem w dół. Sprawdź też dostęp do czujników w ustawieniach urządzenia.", "Place your phone flat with the screen facing down. Check sensor access in device settings.")))
            }
            is FocusIntent.SubmitFeedback -> {
                record(if (intent.helpful) AnalyticsEvent.FEEDBACK_HELPFUL else AnalyticsEvent.FEEDBACK_NEEDS_WORK)
                dismissFeedback()
                emit(FocusEffect.Message(copy("Dziękujemy za opinię. Odpowiedź pozostaje na tym urządzeniu.", "Thank you for your feedback. Your answer stays on this device.")))
            }
            FocusIntent.DismissFeedback -> dismissFeedback()
            is FocusIntent.OpenScreen, FocusIntent.RetryStorage -> Unit
        }
    }

    private suspend fun prepareNext() {
        breakDeadline = null
        breakNotified = false
        snapshot = snapshot.withSession(readySession())
        mutableState.update { it.copy(screen = FocusScreen.FOCUS, feedbackPrompt = null) }
        // Wait for a fresh stable sensor event; a stale pre-break reading cannot start a session.
        publish()
        persist()
    }

    private suspend fun updateSettings(settings: FocusSettings) {
        val before = snapshot.settings
        snapshot = snapshot.copy(settings = settings)
        // Updating grace affects a future session, never an active or interrupted session.
        if (snapshot.activeSession?.state == FocusState.READY) snapshot = snapshot.withSession(readySession())
        publish()
        persist()
        if (before.remindersEnabled != settings.remindersEnabled ||
            (settings.remindersEnabled && before.reminderHour != settings.reminderHour)
        ) {
            emit(FocusEffect.ReminderPreferenceChanged(settings.remindersEnabled, settings.reminderHour))
            if (!settings.remindersEnabled) record(AnalyticsEvent.REMINDERS_DISABLED)
        }
        if (before.analyticsEnabled && !settings.analyticsEnabled) {
            try { analytics?.clear() } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { emit(FocusEffect.Message(copy("Analityka jest wyłączona. Nie udało się jeszcze usunąć lokalnych liczników.", "Analytics is off. Local counters could not be deleted yet."))) }
        }
    }

    private suspend fun transition(next: FocusSession, forceSave: Boolean = false) {
        val previous = snapshot.activeSession
        val changedState = previous?.state != next.state
        if (previous != next) {
            snapshot = snapshot.withSession(next)
            dirty = true
        }
        publish()
        if (changedState) {
            when (next.state) {
                FocusState.FOCUSING -> {
                    // Flipping the phone face-down starts focus from any screen; show the session.
                    mutableState.update { it.copy(screen = FocusScreen.FOCUS) }
                    publish()
                    val resuming = previous?.state == FocusState.GRACE_PERIOD
                    record(if (resuming) AnalyticsEvent.SESSION_RECOVERED else AnalyticsEvent.SESSION_STARTED)
                    emit(FocusEffect.Feedback(if (resuming) FeedbackKind.RESUMED else FeedbackKind.STARTED))
                }
                FocusState.GRACE_PERIOD -> {
                    record(AnalyticsEvent.GRACE_ENTERED)
                    emit(FocusEffect.Feedback(FeedbackKind.INTERRUPTED))
                }
                FocusState.COMPLETED -> {
                    record(AnalyticsEvent.SESSION_COMPLETED)
                    emit(FocusEffect.Feedback(FeedbackKind.COMPLETED))
                    maybeShowFeedback()
                }
                FocusState.ABANDONED -> record(AnalyticsEvent.SESSION_ABANDONED)
                FocusState.READY -> Unit
            }
        }
        syncInterruption(next)
        if (forceSave || changedState || (dirty && timeProvider.monotonicMillis() - lastCheckpoint >= CHECKPOINT_MILLIS)) persist()
    }

    private fun publish() {
        val now = timeProvider.now()
        val progress = ProgressCalculator.calculate(snapshot.history, today(), timeZone, snapshot.settings.minimumStreakMinutes)
        mutableState.update {
            it.copy(session = snapshot.activeSession, settings = snapshot.settings, history = snapshot.history,
                selectedDurationMinutes = snapshot.selectedDurationMinutes, dailyProgress = progress,
                graceRemainingMillis = snapshot.activeSession?.let { session -> FocusEngine.graceRemainingMillis(session, now) } ?: 0,
                breakRemainingMillis = breakDeadline?.let { deadline -> (deadline - now.monotonicMillis).coerceAtLeast(0) })
        }
    }

    private suspend fun persist(): Boolean {
        dirty = true
        return try {
            repository.save(snapshot)
            dirty = false
            lastCheckpoint = timeProvider.monotonicMillis()
            mutableState.update { it.copy(storageError = null) }
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(storageError = copy("Nie udało się zapisać postępu. Spróbuj ponownie; sesja pozostaje na ekranie.", "Progress could not be saved. Please retry; your session is still on screen.")) }
            false
        }
    }

    private suspend fun record(event: AnalyticsEvent) {
        if (!snapshot.settings.analyticsEnabled) return
        try { analytics?.record(event) } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Optional local counters must never interrupt focus. */ }
    }

    private suspend fun countReturn() {
        val date = today()
        if (countedReturnDate == date) return
        countedReturnDate = date
        if (snapshot.history.any { Instant.fromEpochMilliseconds(it.endedAtEpochMillis).toLocalDateTime(timeZone).date < date }) {
            record(AnalyticsEvent.RETURN_NEXT_DAY)
        }
    }

    private fun maybeShowFeedback() {
        val completed = snapshot.history.filter { it.outcome == FocusState.COMPLETED }
        val firstDate = completed.minOfOrNull { Instant.fromEpochMilliseconds(it.endedAtEpochMillis).toLocalDateTime(timeZone).date }
        val prompt = when {
            completed.size == 1 -> FeedbackPrompt.FIRST_SESSION
            firstDate != null && firstDate.daysUntil(today()) >= 6 -> FeedbackPrompt.SEVEN_DAYS
            else -> null
        }
        if (prompt != null && prompt !in dismissedPrompts) mutableState.update { it.copy(feedbackPrompt = prompt) }
    }

    private fun dismissFeedback() {
        state.value.feedbackPrompt?.let(dismissedPrompts::add)
        mutableState.update { it.copy(feedbackPrompt = null) }
    }

    private fun readySession(): FocusSession = FocusEngine.ready(
        id = "${timeProvider.epochMillis().toString(36)}-${Random.nextLong().toULong().toString(36)}",
        duration = FocusDuration.ofMinutes(snapshot.selectedDurationMinutes), settings = snapshot.settings)
    private fun today(): LocalDate = Instant.fromEpochMilliseconds(timeProvider.epochMillis()).toLocalDateTime(timeZone).date
    private fun emit(effect: FocusEffect) { effectChannel.trySend(effect) }
    private fun copy(pl: String, en: String): String = if (snapshot.settings.language == AppLanguage.PL ||
        snapshot.settings.language == AppLanguage.SYSTEM && systemLanguage.startsWith("pl")) pl else en

    /** Emits START/UPDATE only when the observable id or deadline actually changes; END fires once per exit. */
    private fun syncInterruption(next: FocusSession) {
        if (next.state == FocusState.GRACE_PERIOD) {
            if (interruptionSessionId != next.id || interruptionDeadline != next.graceDeadlineEpochMillis) {
                val action = if (interruptionSessionId == next.id) InterruptionAction.UPDATE else InterruptionAction.START
                interruptionSessionId = next.id
                interruptionDeadline = next.graceDeadlineEpochMillis
                emitInterruption(action, next.id, next)
            }
        } else if (interruptionSessionId != null) {
            emitInterruption(InterruptionAction.END, interruptionSessionId!!, null)
            interruptionSessionId = null
            interruptionDeadline = null
        }
    }

    private fun emitInterruption(action: InterruptionAction, sessionId: String, session: FocusSession?) {
        emit(FocusEffect.LockScreenInterruption(
            action = action,
            sessionId = sessionId,
            startedAtEpochMillis = session?.startedAtEpochMillis ?: 0,
            deadlineEpochMillis = session?.graceDeadlineEpochMillis ?: 0,
            remainingMillis = session?.let { FocusEngine.graceRemainingMillis(it, timeProvider.now()) } ?: 0,
            language = languageTag(),
        ))
    }

    private fun languageTag(): String = if (snapshot.settings.language == AppLanguage.PL ||
        (snapshot.settings.language == AppLanguage.SYSTEM && systemLanguage.startsWith("pl"))) "pl" else "en"

    companion object { private const val CHECKPOINT_MILLIS = 5_000L }
}
