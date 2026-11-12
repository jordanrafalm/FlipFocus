package pl.flipfocus.domain

data class SocialProfile(
    val userId: String, val nickname: String, val timezone: String = "UTC",
    val liveOptIn: Boolean = false, val rankingOptIn: Boolean = false,
    val nudgeOptIn: Boolean = false, val quietStartMinute: Int = 1320, val quietEndMinute: Int = 480,
    val companyId: String? = null, val companyName: String? = null, val jobTitle: String? = null,
    /** Separate from [rankingOptIn] (friend rooms): controls visibility in this company's public ranking. */
    val companyRankingOptIn: Boolean = false,
)
data class Company(val id: String, val name: String)

/** Minutes/sessions for one time range. Never split out by individual user. */
data class StatsRange(val completedMinutes: Int, val completedSessions: Int)

/** The signed-in user's own totals; never derived from other employees' data. */
data class PersonalStats(val last7Days: StatsRange, val allTime: StatsRange)
data class SocialConnection(val endpoint: String, val profile: SocialProfile)
data class SocialRankingEntry(val userId: String, val nickname: String, val completedMinutes: Int)

/**
 * One company coworker visible in the ranking: only shown once they opt in (see
 * [SocialProfile.companyRankingOptIn]). Scores are self-reported by each client (Spark plan has no
 * server-side verification), never claim to be tamper-proof.
 */
data class CompanyRankingEntry(
    val userId: String, val nickname: String, val jobTitle: String? = null,
    val last7Days: StatsRange, val allTime: StatsRange,
)
data class SocialLiveMember(val userId: String, val nickname: String)
data class SocialRoom(
    val id: String, val name: String, val kind: RoomKind, val closed: Boolean, val role: RoomRole,
    val goalMinutes: Int, val week: String, val invitationDurationMinutes: Int,
    val privacyVersion: Int, val privacyText: String, val memberCount: Int,
    val liveOptIn: Boolean, val rankingOptIn: Boolean, val aggregate: OrganizationAggregate,
    val ranking: List<SocialRankingEntry>, val live: List<SocialLiveMember>, val inviteCode: String? = null,
)
data class SocialInvitation(
    val code: String, val name: String, val kind: RoomKind, val privacyVersion: Int,
    val privacyText: String, val durationMinutes: Int,
)
data class SocialNudge(val id: String, val roomId: String, val senderId: String, val senderNickname: String)
data class SocialDashboard(
    val profile: SocialProfile, val rooms: List<SocialRoom>, val nudges: List<SocialNudge>,
    val personalStats: PersonalStats? = null,
    /** True once a company is selected: the free (Spark) plan has no server-side aggregation, so the
     * company-wide total stays deferred until a paid backend (Cloud Functions/Blaze) is enabled. */
    val companyAggregateDeferred: Boolean = false,
    /** Only coworkers who opted into [SocialProfile.companyRankingOptIn] appear here. */
    val companyRanking: List<CompanyRankingEntry> = emptyList(),
)
enum class SocialReportReason { SPAM, HARASSMENT, PRIVACY, OTHER }
class SocialException(val code: String) : IllegalStateException(code)

/** Durable key-value persistence for a single outbox string, kept domain-level so presentation never depends on the data layer. */
interface SocialOutboxStore {
    suspend fun read(): String?
    suspend fun write(value: String)
}

/** Optional account and room service. The local focus timer never requires this interface. */
interface SocialRepository {
    suspend fun loadConnection(): SocialConnection?
    suspend fun connect(endpoint: String, nickname: String, timezone: String, privacyAccepted: Boolean, existingToken: String? = null): SocialConnection
    /** Searches the local bundled [LocalCompanyDirectory]; no network call, `endpoint` is unused and kept only for call-site symmetry. */
    suspend fun searchCompanies(endpoint: String, query: String): List<Company> = emptyList()
    /** [jobTitle] is optional; [companyRankingOptIn] separately controls visibility in the company ranking. */
    suspend fun connectCompany(endpoint: String, companyId: String, nickname: String, jobTitle: String = "",
        timezone: String, privacyAccepted: Boolean, companyRankingOptIn: Boolean = false): SocialConnection =
        connect(endpoint, nickname, timezone, privacyAccepted)
    suspend fun dashboard(): SocialDashboard
    suspend fun updateProfile(profile: SocialProfile): SocialProfile
    suspend fun createRoom(name: String, kind: RoomKind, goalMinutes: Int, invitationDurationMinutes: Int, privacyAccepted: Boolean): SocialRoom
    suspend fun previewInvitation(code: String): SocialInvitation
    suspend fun joinRoom(code: String, privacyVersion: Int): SocialRoom
    suspend fun updateRoomPreferences(roomId: String, liveOptIn: Boolean, rankingOptIn: Boolean): SocialRoom
    suspend fun leaveRoom(roomId: String)
    suspend fun closeRoom(roomId: String): SocialRoom
    suspend fun heartbeat(roomId: String, focusing: Boolean)
    suspend fun submitCompletion(roomId: String, sessionId: String, minutes: Int): SocialRoom
    /** Idempotent per userId+sessionId; contributes to the signed-in user's company, independent of rooms. */
    suspend fun submitCompanyCompletion(sessionId: String, minutes: Int) {}
    /** Links a credential to the current (usually anonymous) Firebase user, preserving its UID. */
    suspend fun linkEmailPassword(email: String, password: String): SocialProfile = throw SocialException("not_supported")
    suspend fun linkGoogleIdToken(idToken: String, accessToken: String? = null): SocialProfile = throw SocialException("not_supported")
    /** Stops company sharing: clears companyId/companyName/jobTitle. Does not delete already-submitted history. */
    suspend fun leaveCompany(): SocialProfile = throw SocialException("not_supported")
    suspend fun sendNudge(roomId: String, recipientId: String)
    suspend fun declineNudge(nudgeId: String)
    suspend fun unblockNudges(senderId: String)
    suspend fun reportRoom(roomId: String, reason: SocialReportReason)
    suspend fun exportAccount(): String
    suspend fun deleteAccount()
    suspend fun disconnect()
    fun close() {}
}
