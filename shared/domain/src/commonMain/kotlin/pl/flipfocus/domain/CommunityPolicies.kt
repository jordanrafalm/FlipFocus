package pl.flipfocus.domain

enum class RoomKind { FRIENDS, ORGANIZATION }
enum class RoomRole { OWNER, PARTICIPANT }

data class RoomMembership(
    val userId: String,
    val role: RoomRole = RoomRole.PARTICIPANT,
    val active: Boolean = true,
    val privacyAccepted: Boolean = false,
    val liveOptIn: Boolean = false,
    val leaderboardOptIn: Boolean = false,
)

data class FocusRoom(
    val id: String,
    val ownerId: String,
    val kind: RoomKind = RoomKind.FRIENDS,
    val closed: Boolean = false,
    val privacyPolicyVersion: Int = 1,
)

object CommunityPolicy {
    fun canJoin(room: FocusRoom, acceptedPrivacyVersion: Int): Boolean =
        !room.closed && acceptedPrivacyVersion == room.privacyPolicyVersion

    fun canClose(room: FocusRoom, actorId: String): Boolean = !room.closed && room.ownerId == actorId

    fun canShareLive(room: FocusRoom, member: RoomMembership): Boolean =
        !room.closed && member.active && member.privacyAccepted && member.liveOptIn && room.kind == RoomKind.FRIENDS

    fun canAppearInRanking(room: FocusRoom, member: RoomMembership): Boolean =
        !room.closed && member.active && member.privacyAccepted && member.leaderboardOptIn && room.kind == RoomKind.FRIENDS
}

data class QuietHours(val startMinute: Int = 22 * 60, val endMinute: Int = 8 * 60) {
    init { require(startMinute in 0..1439 && endMinute in 0..1439) }

    fun contains(localMinute: Int): Boolean {
        require(localMinute in 0..1439)
        return when {
            startMinute == endMinute -> false
            startMinute < endMinute -> localMinute >= startMinute && localMinute < endMinute
            else -> localMinute >= startMinute || localMinute < endMinute
        }
    }
}

enum class NudgeDecision { ALLOWED, NOT_OPTED_IN, DECLINED, QUIET_HOURS, RATE_LIMITED, INVALID_MEMBERSHIP }

object NudgePolicy {
    const val MIN_INTERVAL_MILLIS: Long = 60 * 60 * 1_000L
    const val MAX_DAILY_NUDGES: Int = 3

    fun evaluate(
        sender: RoomMembership,
        recipient: RoomMembership,
        room: FocusRoom,
        optedIn: Boolean,
        declinedSender: Boolean,
        recipientLocalMinute: Int,
        quietHours: QuietHours,
        nowEpochMillis: Long,
        previousNudgeEpochMillis: Long?,
        receivedToday: Int,
    ): NudgeDecision = when {
        room.closed || !sender.active || !recipient.active || sender.userId == recipient.userId ||
            !sender.privacyAccepted || !recipient.privacyAccepted -> NudgeDecision.INVALID_MEMBERSHIP
        !optedIn -> NudgeDecision.NOT_OPTED_IN
        declinedSender -> NudgeDecision.DECLINED
        quietHours.contains(recipientLocalMinute) -> NudgeDecision.QUIET_HOURS
        receivedToday >= MAX_DAILY_NUDGES || (previousNudgeEpochMillis != null &&
            nowEpochMillis - previousNudgeEpochMillis < MIN_INTERVAL_MILLIS) -> NudgeDecision.RATE_LIMITED
        else -> NudgeDecision.ALLOWED
    }
}

data class WeeklyContribution(val userId: String, val completedMinutes: Int, val completedSessions: Int) {
    init { require(completedMinutes >= 0 && completedSessions >= 0) }
}

data class OrganizationAggregate(
    val visible: Boolean,
    val completedMinutes: Int?,
    val completedSessions: Int?,
)

object OrganizationPrivacyPolicy {
    const val MIN_CONTRIBUTORS: Int = 3

    /** Suppress small cohorts, expose only weekly totals, and never return personal rows. */
    fun aggregate(contributions: List<WeeklyContribution>): OrganizationAggregate {
        val unique = contributions.groupBy { it.userId }.map { (userId, rows) ->
            WeeklyContribution(userId, rows.sumOf { it.completedMinutes }, rows.sumOf { it.completedSessions })
        }.filter { it.completedSessions > 0 }
        if (unique.size < MIN_CONTRIBUTORS) return OrganizationAggregate(false, null, null)
        return OrganizationAggregate(true, unique.sumOf { it.completedMinutes }, unique.sumOf { it.completedSessions })
    }
}

enum class ProductFeature {
    FOCUS_TIMER, GRACE_PERIOD, STREAK, HISTORY, EXPORT_AND_DELETE,
    ADVANCED_STATISTICS, UNLIMITED_ROOMS, CUSTOM_GOALS, CELEBRATION_THEMES, ACCOUNT_RESTORE,
}

object PremiumPolicy {
    fun isAvailable(feature: ProductFeature, hasVerifiedEntitlement: Boolean): Boolean =
        hasVerifiedEntitlement || feature in setOf(
            ProductFeature.FOCUS_TIMER, ProductFeature.GRACE_PERIOD, ProductFeature.STREAK,
            ProductFeature.HISTORY, ProductFeature.EXPORT_AND_DELETE,
        )
}
