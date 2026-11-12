package pl.flipfocus.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommunityPoliciesTest {
    private val sender = RoomMembership("sender", privacyAccepted = true)
    private val recipient = RoomMembership("recipient", privacyAccepted = true)
    private val room = FocusRoom("room", "sender")

    @Test fun liveAndRankingsRequireIndependentConsentAndAreNeverShownForOrganizations() {
        assertFalse(CommunityPolicy.canShareLive(room, recipient))
        assertFalse(CommunityPolicy.canAppearInRanking(room, recipient.copy(liveOptIn = true)))
        assertTrue(CommunityPolicy.canShareLive(room, recipient.copy(liveOptIn = true)))
        assertTrue(CommunityPolicy.canAppearInRanking(room, recipient.copy(leaderboardOptIn = true)))
        assertFalse(CommunityPolicy.canShareLive(room.copy(kind = RoomKind.ORGANIZATION), recipient.copy(liveOptIn = true)))
        assertFalse(CommunityPolicy.canAppearInRanking(room.copy(kind = RoomKind.ORGANIZATION), recipient.copy(leaderboardOptIn = true)))
    }

    @Test fun roomConsentAndOwnershipAreRequired() {
        assertFalse(CommunityPolicy.canJoin(room, 0))
        assertTrue(CommunityPolicy.canJoin(room, 1))
        assertFalse(CommunityPolicy.canJoin(room.copy(closed = true), 1))
        assertFalse(CommunityPolicy.canClose(room, recipient.userId))
        assertTrue(CommunityPolicy.canClose(room, sender.userId))
    }

    private fun nudge(optedIn: Boolean = true, declined: Boolean = false, minute: Int = 12 * 60,
                      previous: Long? = null, received: Int = 0) = NudgePolicy.evaluate(
        sender, recipient, room, optedIn, declined, minute, QuietHours(), 100_000_000, previous, received)

    @Test fun nudgeHonorsConsentQuietHoursRefusalAndRateLimits() {
        assertEquals(NudgeDecision.ALLOWED, nudge())
        assertEquals(NudgeDecision.NOT_OPTED_IN, nudge(optedIn = false))
        assertEquals(NudgeDecision.DECLINED, nudge(declined = true))
        assertEquals(NudgeDecision.QUIET_HOURS, nudge(minute = 23 * 60))
        assertEquals(NudgeDecision.QUIET_HOURS, nudge(minute = 7 * 60))
        assertEquals(NudgeDecision.ALLOWED, nudge(minute = 8 * 60))
        assertEquals(NudgeDecision.RATE_LIMITED, nudge(previous = 99_999_999))
        assertEquals(NudgeDecision.RATE_LIMITED, nudge(received = 3))
    }

    @Test fun quietHoursSupportSameDayAndOvernightIntervals() {
        assertTrue(QuietHours(10 * 60, 12 * 60).contains(11 * 60))
        assertFalse(QuietHours(10 * 60, 12 * 60).contains(12 * 60))
        assertFalse(QuietHours(0, 0).contains(100))
    }

    @Test fun organizationTotalsRequireThreeDistinctContributors() {
        val one = WeeklyContribution("a", 25, 1)
        val two = WeeklyContribution("b", 25, 1)
        val hidden = OrganizationPrivacyPolicy.aggregate(listOf(one, one, two))
        assertFalse(hidden.visible)
        assertNull(hidden.completedMinutes)
        val visible = OrganizationPrivacyPolicy.aggregate(listOf(one, two, WeeklyContribution("c", 15, 1)))
        assertTrue(visible.visible)
        assertEquals(65, visible.completedMinutes)
        assertEquals(3, visible.completedSessions)
    }

    @Test fun coreRitualAndUserDataRightsAreAlwaysFree() {
        listOf(ProductFeature.FOCUS_TIMER, ProductFeature.GRACE_PERIOD, ProductFeature.STREAK,
            ProductFeature.HISTORY, ProductFeature.EXPORT_AND_DELETE).forEach {
            assertTrue(PremiumPolicy.isAvailable(it, false))
        }
        assertFalse(PremiumPolicy.isAvailable(ProductFeature.CUSTOM_GOALS, false))
        assertTrue(PremiumPolicy.isAvailable(ProductFeature.CUSTOM_GOALS, true))
    }
}
