package pl.flipfocus.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlinx.datetime.TimeZone
import pl.flipfocus.domain.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModelTest {
    @Test fun startPauseRecoverCompleteAndHistoryAreConsistent() = runTest {
        val clock = TestClock()
        val repository = TestRepository(FocusSnapshot(selectedDurationMinutes = 1))
        val vm = FocusViewModel(repository, clock, backgroundScope, autoTick = false, timeZone = TimeZone.UTC)
        runCurrent()
        assertEquals(FocusState.READY, vm.state.value.session?.state)
        clock.advance(1_000)
        vm.accept(FocusIntent.Tick)
        runCurrent()
        assertEquals(0, vm.state.value.session?.focusedMillis)
        vm.accept(FocusIntent.PhonePositionChanged(true))
        runCurrent()
        clock.advance(4_000)
        vm.accept(FocusIntent.PhonePositionChanged(false))
        runCurrent()
        assertEquals(FocusState.GRACE_PERIOD, vm.state.value.session?.state)
        assertEquals(4_000, vm.state.value.session?.focusedMillis)
        clock.advance(3_000)
        vm.accept(FocusIntent.PhonePositionChanged(true))
        runCurrent()
        assertEquals(FocusState.FOCUSING, vm.state.value.session?.state)
        assertEquals(4_000, vm.state.value.session?.focusedMillis)
        repeat(14) { clock.advance(4_000); vm.accept(FocusIntent.Tick); runCurrent() }
        assertEquals(FocusState.COMPLETED, vm.state.value.session?.state)
        assertEquals(60_000, vm.state.value.dailyProgress.totalFocusedMillis)
        assertEquals(1, repository.value.history.size)
        repeat(3) { vm.accept(FocusIntent.Tick) }
        runCurrent()
        assertEquals(1, vm.state.value.history.size)
        assertEquals(FeedbackPrompt.FIRST_SESSION, vm.state.value.feedbackPrompt)
        vm.close()
    }

    @Test fun screenOffDoesNotInterruptRunningSessionAndLiftStillDoes() = runTest {
        val clock = TestClock()
        val vm = FocusViewModel(TestRepository(), clock, backgroundScope, autoTick = false)
        runCurrent()
        vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        clock.advance(2_000)
        vm.accept(FocusIntent.ForegroundChanged(false)); runCurrent()
        clock.advance(3_000); vm.accept(FocusIntent.Tick); runCurrent()
        assertEquals(FocusState.FOCUSING, vm.state.value.session?.state)
        assertEquals(5_000, vm.state.value.session?.focusedMillis)
        vm.accept(FocusIntent.PhonePositionChanged(false)); runCurrent()
        assertEquals(FocusState.GRACE_PERIOD, vm.state.value.session?.state)
        vm.close()
    }

    @Test fun backgroundWithoutRunningSessionIgnoresSensor() = runTest {
        val clock = TestClock()
        val vm = FocusViewModel(TestRepository(), clock, backgroundScope, autoTick = false)
        runCurrent()
        vm.accept(FocusIntent.ForegroundChanged(false)); runCurrent()
        vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        assertEquals(FocusState.READY, vm.state.value.session?.state ?: FocusState.READY)
        vm.close()
    }

    @Test fun longGapOrMeasurementLossDoesNotEarnTime() = runTest {
        val clock = TestClock()
        val vm = FocusViewModel(TestRepository(), clock, backgroundScope, autoTick = false)
        runCurrent(); vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        clock.advance(6_000); vm.accept(FocusIntent.Tick); runCurrent()
        assertEquals(FocusState.GRACE_PERIOD, vm.state.value.session?.state)
        assertEquals(0, vm.state.value.session?.focusedMillis)
        vm.accept(FocusIntent.MeasurementUnavailable("No sensor")); runCurrent()
        assertEquals("No sensor", vm.state.value.sensorError)
        clock.advance(5_000); vm.accept(FocusIntent.Tick); runCurrent()
        assertEquals(FocusState.ABANDONED, vm.state.value.session?.state)
        assertFalse(vm.state.value.dailyProgress.qualifiesForStreak)
        vm.close()
    }

    @Test fun orientationStartsSessionFromAnyScreenAndShowsFocus() = runTest {
        val repository = TestRepository(FocusSnapshot(selectedDurationMinutes = 45))
        val vm = FocusViewModel(repository, TestClock(), backgroundScope, autoTick = false)
        runCurrent()
        vm.accept(FocusIntent.OpenScreen(FocusScreen.SETTINGS))
        vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        assertEquals(FocusState.FOCUSING, vm.state.value.session?.state)
        assertEquals(FocusScreen.FOCUS, vm.state.value.screen)
        assertEquals(45, vm.state.value.session?.duration?.minutes)
        vm.close()
    }

    @Test fun lateNewSessionOrDurationChangesCannotReplaceAnActiveSession() = runTest {
        val vm = FocusViewModel(TestRepository(), TestClock(), backgroundScope, autoTick = false)
        runCurrent(); vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        val original = vm.state.value.session
        vm.accept(FocusIntent.NewSession)
        vm.accept(FocusIntent.EndBreak)
        vm.accept(FocusIntent.SelectDuration(45))
        vm.accept(FocusIntent.UpdateSettings(FocusSettings(graceSeconds = 30)))
        runCurrent()
        assertEquals(original?.id, vm.state.value.session?.id)
        assertEquals(25, vm.state.value.session?.duration?.minutes)
        assertEquals(10_000, vm.state.value.session?.gracePeriodMillis)
        vm.accept(FocusIntent.Abandon); runCurrent()
        assertEquals(FocusState.ABANDONED, vm.state.value.session?.state)
        assertEquals(1, vm.state.value.history.size)
        vm.accept(FocusIntent.NewSession); runCurrent()
        assertEquals(10_000, vm.state.value.session?.gracePeriodMillis)
        assertNotEquals(original?.id, vm.state.value.session?.id)
        vm.close()
    }

    @Test fun restartRecoversOriginalGraceDeadlineWithoutAwardingOfflineTime() = runTest {
        val clock = TestClock()
        var session = FocusEngine.phoneDown(FocusEngine.ready("restore", FocusDuration.ofMinutes(25)), clock.now())
        clock.advance(2_000)
        session = FocusEngine.tick(session, clock.now())
        val repository = TestRepository(FocusSnapshot(activeSession = session))
        clock.advance(4_000)
        val vm = FocusViewModel(repository, clock, backgroundScope, autoTick = false)
        runCurrent()
        assertEquals(FocusState.GRACE_PERIOD, vm.state.value.session?.state)
        assertEquals(2_000, vm.state.value.session?.focusedMillis)
        assertEquals(6_000, vm.state.value.graceRemainingMillis)
        clock.advance(6_000); vm.accept(FocusIntent.Tick); runCurrent()
        assertEquals(FocusState.ABANDONED, vm.state.value.session?.state)
        assertEquals(1, repository.value.history.size)
        vm.close()
    }

    @Test fun storageFailureCanBeRetriedWithoutLosingInMemoryProgress() = runTest {
        val repository = TestRepository()
        val vm = FocusViewModel(repository, TestClock(), backgroundScope, autoTick = false, systemLanguage = "en")
        runCurrent()
        repository.failWrites = true
        vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        assertEquals(FocusState.FOCUSING, vm.state.value.session?.state)
        assertTrue(vm.state.value.storageError!!.startsWith("Progress"))
        repository.failWrites = false
        vm.accept(FocusIntent.RetryStorage); runCurrent()
        assertNull(vm.state.value.storageError)
        assertEquals(FocusState.FOCUSING, repository.value.activeSession?.state)
        vm.close()
    }

    @Test fun breakFinishesOnceAndEndBreakReturnsToReady() = runTest {
        val clock = TestClock()
        val completed = FocusSession("complete", FocusDuration.ofMinutes(1), FocusState.COMPLETED,
            startedAtEpochMillis = clock.epoch - 60_000, focusedMillis = 60_000,
            endedAtEpochMillis = clock.epoch, endReason = FocusEndReason.TARGET_REACHED)
        val vm = FocusViewModel(TestRepository(FocusSnapshot().withSession(completed)), clock, backgroundScope, autoTick = false)
        val effects = mutableListOf<FocusEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        runCurrent(); vm.accept(FocusIntent.StartBreak(1)); runCurrent()
        assertEquals(60_000, vm.state.value.breakRemainingMillis)
        clock.advance(60_000); vm.accept(FocusIntent.Tick); runCurrent()
        vm.accept(FocusIntent.Tick); runCurrent()
        assertEquals(1, effects.filterIsInstance<FocusEffect.Feedback>().size)
        vm.accept(FocusIntent.EndBreak); runCurrent()
        assertNull(vm.state.value.breakRemainingMillis)
        assertEquals(FocusState.READY, vm.state.value.session?.state)
        vm.close()
    }

    @Test fun optInControlsAnalyticsAndDisablingClearsCounters() = runTest {
        val recorder = TestAnalytics()
        val vm = FocusViewModel(TestRepository(), TestClock(), backgroundScope, autoTick = false, analytics = recorder)
        runCurrent(); vm.accept(FocusIntent.ReportMeasurementIssue); runCurrent()
        assertTrue(recorder.events.isEmpty())
        vm.accept(FocusIntent.UpdateSettings(FocusSettings(analyticsEnabled = true)))
        vm.accept(FocusIntent.ReportMeasurementIssue); runCurrent()
        assertEquals(listOf(AnalyticsEvent.MEASUREMENT_PROBLEM), recorder.events)
        vm.accept(FocusIntent.UpdateSettings(FocusSettings(analyticsEnabled = false))); runCurrent()
        assertTrue(recorder.events.isEmpty())
        assertEquals(1, recorder.clearCount)
        vm.close()
    }

    @Test fun gracefulShutdownFlushesPendingLifecycleEventsBeforeCancelling() = runTest {
        val repository = TestRepository()
        val clock = TestClock()
        val vm = FocusViewModel(repository, clock, backgroundScope, autoTick = false)
        runCurrent(); vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        clock.advance(2_000)
        vm.accept(FocusIntent.ForegroundChanged(false))
        val shutdown = launch { vm.flushAndClose() }
        runCurrent(); shutdown.join()
        assertEquals(2_000, repository.value.activeSession?.focusedMillis)
        assertEquals(FocusState.GRACE_PERIOD, repository.value.activeSession?.state)
    }

    @Test fun exportUsesValidEscapingAndProtectsSpreadsheetCells() {
        val entry = FocusHistoryEntry("=x,\"y\"", 1, 60_000, 0, 60_000, FocusState.COMPLETED, FocusEndReason.TARGET_REACHED)
        assertTrue(HistoryExport.format(listOf(entry), ExportFormat.CSV).contains("\"'=x,\"\"y\"\"\""))
        assertTrue(HistoryExport.format(listOf(entry), ExportFormat.JSON).contains("\\\"y\\\""))
    }

    @Test fun lockScreenInterruptionStartsOnceAndEndsOnResumeWithoutRepeatsFromRepeatedTicks() = runTest {
        val clock = TestClock()
        val vm = FocusViewModel(TestRepository(), clock, backgroundScope, autoTick = false)
        val effects = mutableListOf<FocusEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        runCurrent()
        vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        clock.advance(2_000)
        vm.accept(FocusIntent.PhonePositionChanged(false)); runCurrent()
        val sessionId = vm.state.value.session?.id
        val deadline = vm.state.value.session?.graceDeadlineEpochMillis
        // Repeated ticks while in grace must not re-emit START/UPDATE.
        repeat(5) { clock.advance(500); vm.accept(FocusIntent.Tick); runCurrent() }
        vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        val interruptions = effects.filterIsInstance<FocusEffect.LockScreenInterruption>()
        assertEquals(listOf(InterruptionAction.START, InterruptionAction.END), interruptions.map { it.action })
        assertEquals(sessionId, interruptions[0].sessionId)
        assertEquals(deadline, interruptions[0].deadlineEpochMillis)
        assertEquals(sessionId, interruptions[1].sessionId)
        vm.close()
    }

    @Test fun lockScreenInterruptionEndsWhenGraceExpiresIntoAbandoned() = runTest {
        val clock = TestClock()
        val vm = FocusViewModel(TestRepository(), clock, backgroundScope, autoTick = false)
        val effects = mutableListOf<FocusEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        runCurrent()
        vm.accept(FocusIntent.PhonePositionChanged(true)); runCurrent()
        clock.advance(2_000)
        vm.accept(FocusIntent.PhonePositionChanged(false)); runCurrent()
        clock.advance(10_000); vm.accept(FocusIntent.Tick); runCurrent()
        assertEquals(FocusState.ABANDONED, vm.state.value.session?.state)
        val interruptions = effects.filterIsInstance<FocusEffect.LockScreenInterruption>()
        assertEquals(listOf(InterruptionAction.START, InterruptionAction.END), interruptions.map { it.action })
        vm.close()
    }

    @Test fun restartDuringGracePublishesStartWithRecoveredDeadlineAndLanguage() = runTest {
        val clock = TestClock()
        var session = FocusEngine.phoneDown(FocusEngine.ready("restore-lock", FocusDuration.ofMinutes(25)), clock.now())
        clock.advance(2_000)
        session = FocusEngine.phoneLifted(session, clock.now())
        val repository = TestRepository(FocusSnapshot(activeSession = session, settings = FocusSettings(language = AppLanguage.EN)))
        clock.advance(1_000)
        val vm = FocusViewModel(repository, clock, backgroundScope, autoTick = false, systemLanguage = "en")
        val effects = mutableListOf<FocusEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        runCurrent()
        assertEquals(FocusState.GRACE_PERIOD, vm.state.value.session?.state)
        val interruption = effects.filterIsInstance<FocusEffect.LockScreenInterruption>().single()
        assertEquals(InterruptionAction.START, interruption.action)
        assertEquals(session.id, interruption.sessionId)
        assertEquals(session.graceDeadlineEpochMillis, interruption.deadlineEpochMillis)
        assertEquals("en", interruption.language)
        vm.close()
    }

    @Test fun restartAfterProcessDeathDuringExpiredGraceClearsStaleInterruption() = runTest {
        val clock = TestClock()
        var session = FocusEngine.phoneDown(FocusEngine.ready("stale-lock", FocusDuration.ofMinutes(25)), clock.now())
        clock.advance(2_000)
        session = FocusEngine.phoneLifted(session, clock.now())
        val repository = TestRepository(FocusSnapshot(activeSession = session))
        // The process was killed and only resumes after the grace deadline has long passed.
        clock.advance(60_000)
        val vm = FocusViewModel(repository, clock, backgroundScope, autoTick = false)
        val effects = mutableListOf<FocusEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        runCurrent()
        assertEquals(FocusState.ABANDONED, vm.state.value.session?.state)
        val interruption = effects.filterIsInstance<FocusEffect.LockScreenInterruption>().single()
        assertEquals(InterruptionAction.END, interruption.action)
        assertEquals(session.id, interruption.sessionId)
        vm.close()
    }

    private class TestClock(var epoch: Long = 1_700_000_000_000, var monotonic: Long = 0) : TimeProvider {
        override fun epochMillis(): Long = epoch
        override fun monotonicMillis(): Long = monotonic
        fun advance(millis: Long) { epoch += millis; monotonic += millis }
    }
    private class TestRepository(var value: FocusSnapshot = FocusSnapshot()) : FocusRepository {
        var failWrites = false
        override suspend fun load(): FocusSnapshot = value
        override suspend fun save(snapshot: FocusSnapshot) { check(!failWrites); value = snapshot }
        override suspend fun clearHistory(): FocusSnapshot {
            value = value.copy(history = emptyList(), activeSession = value.activeSession?.takeUnless { it.isTerminal })
            return value
        }
    }
    private class TestAnalytics : AnalyticsRecorder {
        val events = mutableListOf<AnalyticsEvent>()
        var clearCount = 0
        override suspend fun record(event: AnalyticsEvent) { events += event }
        override suspend fun clear() { clearCount++; events.clear() }
    }
}
