package pl.flipfocus.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusEngineTest {
    private val initial = TimeReading(1_700_000_000_000, 10_000)
    private fun time(delta: Long) = TimeReading(initial.epochMillis + delta, initial.monotonicMillis + delta)
    private fun ready() = FocusEngine.ready("session-1", FocusDuration.ofMinutes(1))
    private fun running() = FocusEngine.phoneDown(ready(), initial)
    private fun finish(): FocusSession {
        var session = running()
        repeat(12) { session = FocusEngine.tick(session, time((it + 1) * 5_000L)) }
        return session
    }

    @Test fun readyNeverAccumulatesTime() {
        val session = ready()
        assertEquals(session, FocusEngine.tick(session, time(80_000)))
        assertNull(session.startedAtEpochMillis)
    }

    @Test fun focusAccumulatesMonotonicTimeOnly() {
        val session = FocusEngine.tick(running(), TimeReading(initial.epochMillis + 1_000_000, initial.monotonicMillis + 2_000))
        assertEquals(2_000, session.focusedMillis)
        assertEquals(FocusState.FOCUSING, session.state)
    }

    @Test fun liftCountsObservedIntervalAndStartsGrace() {
        val session = FocusEngine.phoneLifted(running(), time(2_000))
        assertEquals(FocusState.GRACE_PERIOD, session.state)
        assertEquals(2_000, session.focusedMillis)
        assertEquals(time(12_000).monotonicMillis, session.graceDeadlineMonotonicMillis)
        assertEquals(10_000, FocusEngine.graceRemainingMillis(session, time(2_000)))
    }

    @Test fun graceDoesNotCountFocusAndResumeKeepsEarnedTime() {
        val lifted = FocusEngine.phoneLifted(running(), time(2_000))
        val paused = FocusEngine.tick(lifted, time(8_000))
        assertEquals(2_000, paused.focusedMillis)
        val resumed = FocusEngine.phoneDown(paused, time(11_999))
        assertEquals(FocusState.FOCUSING, resumed.state)
        assertEquals(3_000, FocusEngine.tick(resumed, time(12_999)).focusedMillis)
        assertNull(resumed.graceDeadlineEpochMillis)
    }

    @Test fun deadlineIsStrictEvenWhenReturnArrivesAtBoundary() {
        val lifted = FocusEngine.phoneLifted(running(), time(2_000))
        val expired = FocusEngine.phoneDown(lifted, time(12_000))
        assertEquals(FocusState.ABANDONED, expired.state)
        assertEquals(FocusEndReason.GRACE_EXPIRED, expired.endReason)
        assertEquals(time(12_000).epochMillis, expired.endedAtEpochMillis)
    }

    @Test fun lateTickEndsAtDeadlineInsteadOfDeliveryTime() {
        val lifted = FocusEngine.phoneLifted(running(), time(2_000))
        val expired = FocusEngine.tick(lifted, time(50_000))
        assertEquals(time(12_000).epochMillis, expired.endedAtEpochMillis)
        assertEquals(2_000, expired.focusedMillis)
    }

    @Test fun completionIsClampedIdempotentAndHasSingleHistoryEntry() {
        val completed = finish()
        assertEquals(FocusState.COMPLETED, completed.state)
        assertEquals(60_000, completed.focusedMillis)
        assertEquals(0, completed.remainingMillis)
        assertEquals(FocusEndReason.TARGET_REACHED, completed.endReason)
        assertEquals(completed, FocusEngine.tick(completed, time(70_000)))
        assertEquals(completed, FocusEngine.abandon(completed, time(70_000)))
        val saved = FocusSnapshot().withSession(completed).withSession(completed)
        assertEquals(1, saved.history.size)
    }

    @Test fun completionUsesActualTargetCrossingTime() {
        val nearEnd = running().copy(focusedMillis = 59_900)
        val completed = FocusEngine.tick(nearEnd, time(500))
        assertEquals(time(100).epochMillis, completed.endedAtEpochMillis)
        assertEquals(60_000, completed.focusedMillis)
    }

    @Test fun targetAndGraceAreFrozenAtStart() {
        val session = FocusEngine.ready("frozen", FocusDuration.ofMinutes(25), FocusSettings(graceSeconds = 12))
        val differentSettings = FocusSettings(graceSeconds = 4)
        assertEquals(25, session.duration.minutes)
        assertEquals(10_000, session.gracePeriodMillis)
        assertEquals(4, differentSettings.graceSeconds)
    }

    @Test fun cancellationPreservesPartialTimeButDoesNotComplete() {
        val canceled = FocusEngine.abandon(running(), time(3_000))
        assertEquals(FocusState.ABANDONED, canceled.state)
        assertEquals(FocusEndReason.USER_ABANDONED, canceled.endReason)
        assertEquals(3_000, canceled.focusedMillis)
        assertEquals(canceled, FocusEngine.phoneDown(canceled, time(4_000)))
    }

    @Test fun restartNeverCountsUnobservedTimeAndRebasesMonotonicClock() {
        val checkpoint = FocusEngine.tick(running(), time(2_000))
        val restored = FocusEngine.restore(checkpoint, TimeReading(time(6_000).epochMillis, 100))
        assertEquals(FocusState.GRACE_PERIOD, restored.state)
        assertEquals(2_000, restored.focusedMillis)
        assertEquals(6_100, restored.graceDeadlineMonotonicMillis)
        val resumed = FocusEngine.phoneDown(restored, TimeReading(time(7_000).epochMillis, 1_100))
        assertEquals(2_000, resumed.focusedMillis)
    }

    @Test fun graceRestartPreservesOriginalDeadlineAcrossRepeatedRestores() {
        val checkpoint = FocusEngine.phoneLifted(running(), time(2_000))
        val restored = FocusEngine.restore(checkpoint, TimeReading(time(6_000).epochMillis, 100))
        val restoredAgain = FocusEngine.restore(restored, TimeReading(time(10_000).epochMillis, 50))
        assertEquals(time(12_000).epochMillis, restoredAgain.graceDeadlineEpochMillis)
        assertEquals(2_050, restoredAgain.graceDeadlineMonotonicMillis)
        val expired = FocusEngine.restore(restoredAgain, TimeReading(time(12_000).epochMillis, 20))
        assertEquals(FocusState.ABANDONED, expired.state)
    }

    @Test fun staleFocusingRestartAbandonsWithoutGivingExtraGrace() {
        val restored = FocusEngine.restore(running(), TimeReading(time(30_000).epochMillis, 1))
        assertEquals(FocusState.ABANDONED, restored.state)
        assertEquals(0, restored.focusedMillis)
    }

    @Test fun unannouncedBackgroundGapNeverFabricatesFocusTime() {
        val session = FocusEngine.tick(running(), time(8_000))
        assertEquals(FocusState.GRACE_PERIOD, session.state)
        assertEquals(0, session.focusedMillis)
        assertEquals(2_000, FocusEngine.graceRemainingMillis(session, time(8_000)))
        val expired = FocusEngine.tick(session, time(10_000))
        assertEquals(FocusState.ABANDONED, expired.state)
    }

    @Test fun backgroundTransitionCountsOnlyMeasuredTime() {
        val background = FocusEngine.suspend(running(), time(1_000))
        assertEquals(FocusState.GRACE_PERIOD, background.state)
        assertEquals(1_000, background.focusedMillis)
    }

    @Test fun backwardClocksFailSafelyAndDoNotCreateNegativeHistoryIntervals() {
        val invalidMono = FocusEngine.tick(running(), TimeReading(initial.epochMillis - 100, 0))
        assertEquals(FocusEndReason.MEASUREMENT_LOST, invalidMono.endReason)
        assertEquals(initial.epochMillis, invalidMono.endedAtEpochMillis)
        val invalidEpoch = FocusEngine.restore(running(), TimeReading(initial.epochMillis - 1, 0))
        assertEquals(FocusEndReason.MEASUREMENT_LOST, invalidEpoch.endReason)
    }

    @Test fun replayIsDeterministic() {
        fun replay(): FocusSnapshot {
            var session = running()
            session = FocusEngine.tick(session, time(1_000))
            session = FocusEngine.phoneLifted(session, time(2_000))
            session = FocusEngine.phoneDown(session, time(5_000))
            session = FocusEngine.abandon(session, time(8_000))
            return FocusSnapshot().withSession(session)
        }
        assertEquals(replay(), replay())
    }

    @Test fun invalidDurationsCannotEnterThroughConstructorOrCopy() {
        assertFailsWith<IllegalArgumentException> { FocusDuration.ofMinutes(0) }
        assertFailsWith<IllegalArgumentException> { FocusDuration.ofMinutes(181) }
        assertFailsWith<IllegalArgumentException> { FocusDuration.ofMinutes(25).copy(minutes = -1) }
        assertEquals(listOf(15, 25, 45), FocusDuration.presets.map { it.minutes })
        assertFalse(ready().isTerminal)
        assertTrue(finish().isTerminal)
    }
}
