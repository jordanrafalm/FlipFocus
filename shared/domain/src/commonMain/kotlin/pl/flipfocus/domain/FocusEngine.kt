package pl.flipfocus.domain

/**
 * Deterministic session transitions. The caller supplies both clocks and persists the result.
 * Unobserved intervals never earn focus time; a gap over five seconds enters recovery.
 */
object FocusEngine {
    /** The time allowed to return after lifting the phone is fixed for every session. */
    const val GRACE_PERIOD_MILLIS: Long = 10_000
    const val MAX_TRUSTED_TICK_GAP_MILLIS: Long = 5_000

    fun ready(id: String, duration: FocusDuration, settings: FocusSettings = FocusSettings()): FocusSession =
        FocusSession(id = id, duration = duration, gracePeriodMillis = GRACE_PERIOD_MILLIS)

    fun phoneDown(session: FocusSession, now: TimeReading): FocusSession = when (session.state) {
        FocusState.READY -> session.copy(
            state = FocusState.FOCUSING,
            startedAtEpochMillis = now.epochMillis,
            lastTickMonotonicMillis = now.monotonicMillis,
            lastObservedAtEpochMillis = now.epochMillis,
        )
        FocusState.GRACE_PERIOD -> {
            val checked = tick(session, now)
            if (checked.isTerminal) checked else checked.copy(
                state = FocusState.FOCUSING,
                lastTickMonotonicMillis = now.monotonicMillis,
                lastObservedAtEpochMillis = now.epochMillis,
                graceDeadlineMonotonicMillis = null,
                graceDeadlineEpochMillis = null,
            )
        }
        FocusState.FOCUSING -> tick(session, now)
        FocusState.COMPLETED, FocusState.ABANDONED -> session
    }

    fun phoneLifted(session: FocusSession, now: TimeReading): FocusSession {
        if (session.state != FocusState.FOCUSING) return tick(session, now)
        val advanced = tick(session, now)
        return if (advanced.state == FocusState.FOCUSING) enterGrace(advanced, now) else advanced
    }

    fun tick(session: FocusSession, now: TimeReading): FocusSession = when (session.state) {
        FocusState.READY, FocusState.COMPLETED, FocusState.ABANDONED -> session
        FocusState.GRACE_PERIOD -> {
            val deadline = session.graceDeadlineMonotonicMillis
            val lastTick = session.lastTickMonotonicMillis
            when {
                deadline == null || lastTick == null || now.monotonicMillis < lastTick ->
                    finish(session, now.epochMillis, FocusEndReason.MEASUREMENT_LOST)
                now.monotonicMillis >= deadline ->
                    finish(session, session.graceDeadlineEpochMillis ?: now.epochMillis, FocusEndReason.GRACE_EXPIRED)
                else -> session.copy(
                    lastTickMonotonicMillis = now.monotonicMillis,
                    lastObservedAtEpochMillis = now.epochMillis,
                )
            }
        }
        FocusState.FOCUSING -> tickFocusing(session, now)
    }

    fun abandon(session: FocusSession, now: TimeReading): FocusSession {
        if (session.isTerminal || session.state == FocusState.READY) return session
        val advanced = tick(session, now)
        return if (advanced.isTerminal) advanced else finish(advanced, now.epochMillis, FocusEndReason.USER_ABANDONED)
    }

    /** Call before stopping sensor monitoring on background, permission loss, or sensor failure. */
    fun suspend(session: FocusSession, now: TimeReading): FocusSession = phoneLifted(session, now)

    /**
     * Monotonic values from another process/boot are discarded. Restoration can only preserve
     * already observed time. Recovery uses the original checkpoint/deadline and grants no extra grace.
     */
    fun restore(session: FocusSession, now: TimeReading): FocusSession {
        if (session.state == FocusState.READY || session.isTerminal) return session
        val observed = session.lastObservedAtEpochMillis
            ?: return finish(session, now.epochMillis, FocusEndReason.MEASUREMENT_LOST)
        if (now.epochMillis < observed) return finish(session, now.epochMillis, FocusEndReason.MEASUREMENT_LOST)
        val deadline = when (session.state) {
            FocusState.FOCUSING -> observed + session.gracePeriodMillis
            FocusState.GRACE_PERIOD -> session.graceDeadlineEpochMillis
                ?: return finish(session, now.epochMillis, FocusEndReason.MEASUREMENT_LOST)
            else -> return session
        }
        if (now.epochMillis >= deadline) return finish(session, deadline, FocusEndReason.GRACE_EXPIRED)
        return session.copy(
            state = FocusState.GRACE_PERIOD,
            lastTickMonotonicMillis = now.monotonicMillis,
            lastObservedAtEpochMillis = now.epochMillis,
            graceDeadlineEpochMillis = deadline,
            graceDeadlineMonotonicMillis = now.monotonicMillis + (deadline - now.epochMillis),
        )
    }

    fun graceRemainingMillis(session: FocusSession, now: TimeReading): Long =
        if (session.state == FocusState.GRACE_PERIOD) {
            ((session.graceDeadlineMonotonicMillis ?: now.monotonicMillis) - now.monotonicMillis)
                .coerceIn(0, session.gracePeriodMillis)
        } else 0

    private fun tickFocusing(session: FocusSession, now: TimeReading): FocusSession {
        val checkpoint = session.lastTickMonotonicMillis
            ?: return finish(session, now.epochMillis, FocusEndReason.MEASUREMENT_LOST)
        val delta = now.monotonicMillis - checkpoint
        if (delta < 0) return finish(session, now.epochMillis, FocusEndReason.MEASUREMENT_LOST)
        if (delta > MAX_TRUSTED_TICK_GAP_MILLIS) {
            val lastObserved = session.lastObservedAtEpochMillis
                ?: return finish(session, now.epochMillis, FocusEndReason.MEASUREMENT_LOST)
            return tick(enterGrace(session, TimeReading(lastObserved, checkpoint)), now)
        }
        val focused = (session.focusedMillis + delta).coerceAtMost(session.duration.millis)
        val advanced = session.copy(
            focusedMillis = focused,
            lastTickMonotonicMillis = now.monotonicMillis,
            lastObservedAtEpochMillis = now.epochMillis,
        )
        if (focused < session.duration.millis) return advanced
        val excess = delta - (session.duration.millis - session.focusedMillis)
        return finish(advanced, now.epochMillis - excess, FocusEndReason.TARGET_REACHED)
    }

    private fun enterGrace(session: FocusSession, now: TimeReading): FocusSession = session.copy(
        state = FocusState.GRACE_PERIOD,
        lastTickMonotonicMillis = now.monotonicMillis,
        lastObservedAtEpochMillis = now.epochMillis,
        graceDeadlineMonotonicMillis = now.monotonicMillis + session.gracePeriodMillis,
        graceDeadlineEpochMillis = now.epochMillis + session.gracePeriodMillis,
    )

    private fun finish(session: FocusSession, epochMillis: Long, reason: FocusEndReason): FocusSession = session.copy(
        state = if (reason == FocusEndReason.TARGET_REACHED) FocusState.COMPLETED else FocusState.ABANDONED,
        endedAtEpochMillis = epochMillis.coerceAtLeast(session.startedAtEpochMillis ?: epochMillis),
        endReason = reason,
        lastTickMonotonicMillis = null,
        graceDeadlineMonotonicMillis = null,
        graceDeadlineEpochMillis = null,
    )
}
