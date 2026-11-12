@file:OptIn(kotlin.time.ExperimentalTime::class)

package pl.flipfocus.data

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.AuthCredential
import dev.gitlive.firebase.auth.EmailAuthProvider
import dev.gitlive.firebase.auth.FirebaseAuthException
import dev.gitlive.firebase.auth.FirebaseAuthUserCollisionException
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.auth.code
import dev.gitlive.firebase.firestore.FirebaseFirestoreException
import dev.gitlive.firebase.firestore.code
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import pl.flipfocus.domain.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Firebase-backed community service (Spark plan — no Cloud Functions): anonymous auth (native
 * session persistence — no manual token handling needed) plus Firestore for profiles, rooms,
 * nudges and the caller's own company completions. The company directory itself is fully local
 * ([LocalCompanyDirectory]); no companies collection is read or written.
 */
@OptIn(ExperimentalUuidApi::class)
class FirebaseSocialRepository : SocialRepository {
    private val auth get() = Firebase.auth
    private val db get() = Firebase.firestore

    override suspend fun loadConnection(): SocialConnection? = guarded {
        val user = auth.currentUser ?: return@guarded null
        val profile = readProfile(user.uid) ?: return@guarded null
        SocialConnection(ENDPOINT, profile)
    }

    override suspend fun connect(endpoint: String, nickname: String, timezone: String, privacyAccepted: Boolean, existingToken: String?): SocialConnection = guarded {
        if (!privacyAccepted) throw SocialException("privacy_required")
        val name = nickname.trim()
        if (name.length !in 1..40) throw SocialException("invalid_nickname")
        val uid = ensureSignedIn()
        val profile = SocialProfile(userId = uid, nickname = name, timezone = timezone)
        writeProfile(profile)
        SocialConnection(ENDPOINT, profile)
    }

    override suspend fun searchCompanies(endpoint: String, query: String): List<Company> =
        LocalCompanyDirectory.search(query)

    override suspend fun connectCompany(
        endpoint: String, companyId: String, nickname: String, jobTitle: String,
        timezone: String, privacyAccepted: Boolean, companyRankingOptIn: Boolean,
    ): SocialConnection = guarded {
        if (!privacyAccepted) throw SocialException("privacy_required")
        val name = nickname.trim()
        val title = jobTitle.trim()
        if (name.length !in 1..40) throw SocialException("invalid_nickname")
        if (title.length > 80) throw SocialException("invalid_job_title")
        val company = LocalCompanyDirectory.byId(companyId) ?: throw SocialException("company_not_found")
        val uid = ensureSignedIn()
        val existing = readProfileOrNull(uid)
        val profile = existing?.copy(
            nickname = name, timezone = timezone, companyId = company.id, companyName = company.name,
            jobTitle = title.ifEmpty { null }, companyRankingOptIn = companyRankingOptIn,
        ) ?: SocialProfile(
            userId = uid, nickname = name, timezone = timezone,
            companyId = company.id, companyName = company.name, jobTitle = title.ifEmpty { null },
            companyRankingOptIn = companyRankingOptIn,
        )
        writeProfile(profile)
        SocialConnection(ENDPOINT, profile)
    }

    override suspend fun dashboard(): SocialDashboard = guarded {
        val uid = currentUid()
        val profile = readProfile(uid) ?: throw SocialException("not_connected")
        val rooms = myRooms(uid, profile)
        val nudges = if (profile.nudgeOptIn) listNudges(uid) else emptyList()
        val companyId = profile.companyId
        // Stats/ranking are best-effort: a rules/index hiccup on these two reads must never fail the
        // whole Clan join, and must never be mistaken for "opted out" (which would wipe the ranking row).
        val personalStats = companyId?.let { readPersonalStatsOrNull(uid, it) }
        val companyRanking = if (companyId != null) {
            if (personalStats != null) writeCompanyRankingOrIgnore(uid, companyId, profile, personalStats)
            readCompanyRankingRowsOrEmpty(companyId)
        } else emptyList()
        SocialDashboard(profile, rooms, nudges, personalStats, companyAggregateDeferred = companyId != null, companyRanking = companyRanking)
    }

    override suspend fun updateProfile(profile: SocialProfile): SocialProfile = guarded {
        val uid = currentUid()
        require(profile.userId == uid)
        writeProfile(profile)
        profile
    }

    override suspend fun createRoom(name: String, kind: RoomKind, goalMinutes: Int, invitationDurationMinutes: Int, privacyAccepted: Boolean): SocialRoom = guarded {
        if (!privacyAccepted) throw SocialException("privacy_required")
        val title = name.trim()
        if (title.isEmpty()) throw SocialException("invalid_name")
        val uid = currentUid()
        val roomId = Uuid.random().toHexString()
        val inviteCode = Uuid.random().toHexString().take(12)
        val room = RoomDoc(
            name = title, kind = kind.name.lowercase(), ownerId = uid, closed = false,
            goalMinutes = goalMinutes, invitationDurationMinutes = invitationDurationMinutes,
            privacyVersion = 1, inviteCode = inviteCode,
        )
        val nickname = readProfile(uid)?.nickname ?: ""
        db.collection(ROOMS).document(roomId).set(RoomDoc.serializer(), room)
        db.collection(ROOMS).document(roomId).collection(MEMBERS).document(uid)
            .set(MemberDoc.serializer(), MemberDoc(uid = uid, role = "owner", nickname = nickname))
        roomDetail(roomId, uid) ?: throw SocialException("invalid_response")
    }

    override suspend fun previewInvitation(code: String): SocialInvitation = guarded {
        val snapshot = db.collection(ROOMS).where { "inviteCode" equalTo code }.where { "closed" equalTo false }
            .limit(1).get().documents.firstOrNull() ?: throw SocialException("invitation_unavailable")
        val room = snapshot.data(RoomDoc.serializer())
        SocialInvitation(code, room.name, kind(room.kind), room.privacyVersion, PRIVACY_TEXT, room.invitationDurationMinutes)
    }

    override suspend fun joinRoom(code: String, privacyVersion: Int): SocialRoom = guarded {
        val uid = currentUid()
        val snapshot = db.collection(ROOMS).where { "inviteCode" equalTo code }.where { "closed" equalTo false }
            .limit(1).get().documents.firstOrNull() ?: throw SocialException("invitation_unavailable")
        if (privacyVersion != snapshot.data(RoomDoc.serializer()).privacyVersion) throw SocialException("privacy_consent_required")
        val roomId = snapshot.id
        val memberRef = db.collection(ROOMS).document(roomId).collection(MEMBERS).document(uid)
        if (!memberRef.get().exists) {
            val nickname = readProfile(uid)?.nickname ?: ""
            memberRef.set(MemberDoc.serializer(), MemberDoc(uid = uid, role = "participant", nickname = nickname))
        }
        roomDetail(roomId, uid) ?: throw SocialException("invalid_response")
    }

    override suspend fun updateRoomPreferences(roomId: String, liveOptIn: Boolean, rankingOptIn: Boolean): SocialRoom = guarded {
        val uid = currentUid()
        val memberRef = db.collection(ROOMS).document(roomId).collection(MEMBERS).document(uid)
        val member = memberRef.get().takeIf { it.exists }?.data(MemberDoc.serializer()) ?: throw SocialException("not_member")
        memberRef.set(MemberDoc.serializer(), member.copy(
            liveOptIn = liveOptIn, rankingOptIn = rankingOptIn,
            liveUntilEpochSeconds = if (liveOptIn) member.liveUntilEpochSeconds else 0,
        ))
        roomDetail(roomId, uid) ?: throw SocialException("invalid_response")
    }

    override suspend fun leaveRoom(roomId: String) = guarded {
        val uid = currentUid()
        val room = db.collection(ROOMS).document(roomId).get().takeIf { it.exists }?.data(RoomDoc.serializer())
            ?: throw SocialException("room_unavailable")
        if (room.ownerId == uid && !room.closed) throw SocialException("owner_must_close_room")
        db.collection(ROOMS).document(roomId).collection(MEMBERS).document(uid).delete()
        Unit
    }

    override suspend fun closeRoom(roomId: String): SocialRoom = guarded {
        val uid = currentUid()
        val ref = db.collection(ROOMS).document(roomId)
        val room = ref.get().takeIf { it.exists }?.data(RoomDoc.serializer()) ?: throw SocialException("room_unavailable")
        if (room.ownerId != uid) throw SocialException("owner_required")
        ref.set(RoomDoc.serializer(), room.copy(closed = true))
        roomDetail(roomId, uid) ?: throw SocialException("invalid_response")
    }

    override suspend fun heartbeat(roomId: String, focusing: Boolean) = guarded {
        val uid = currentUid()
        val profile = readProfile(uid) ?: throw SocialException("not_connected")
        val memberRef = db.collection(ROOMS).document(roomId).collection(MEMBERS).document(uid)
        val member = memberRef.get().takeIf { it.exists }?.data(MemberDoc.serializer()) ?: throw SocialException("not_member")
        val room = db.collection(ROOMS).document(roomId).get().takeIf { it.exists }?.data(RoomDoc.serializer())
            ?: throw SocialException("room_unavailable")
        if (focusing && (room.kind != "friends" || !profile.liveOptIn || !member.liveOptIn)) throw SocialException("live_consent_required")
        val until = if (focusing) Clock.System.now().epochSeconds + LIVE_TTL_SECONDS else 0L
        memberRef.set(MemberDoc.serializer(), member.copy(liveUntilEpochSeconds = until))
        Unit
    }

    override suspend fun submitCompletion(roomId: String, sessionId: String, minutes: Int): SocialRoom = guarded {
        if (minutes !in 1..180) throw SocialException("invalid_minutes")
        val uid = currentUid()
        val week = currentWeek()
        val roomCompletionRef = db.collection(ROOMS).document(roomId).collection(COMPLETIONS).document("${uid}_$sessionId")
        if (!roomCompletionRef.get().exists) {
            roomCompletionRef.set(CompletionDoc.serializer(), CompletionDoc(uid, week, minutes))
            val totalRef = db.collection(ROOMS).document(roomId).collection(WEEKLY_TOTALS).document("${week}_$uid")
            val existing = totalRef.get().takeIf { it.exists }?.data(WeeklyTotalDoc.serializer())
            totalRef.set(WeeklyTotalDoc.serializer(), WeeklyTotalDoc(
                week = week, uid = uid,
                minutes = (existing?.minutes ?: 0) + minutes, sessions = (existing?.sessions ?: 0) + 1,
            ))
        }
        // Company points are submitted independently via submitCompanyCompletion, regardless of room membership.
        roomDetail(roomId, uid) ?: throw SocialException("invalid_response")
    }

    override suspend fun submitCompanyCompletion(sessionId: String, minutes: Int) = guarded {
        if (minutes !in 1..180) throw SocialException("invalid_minutes")
        val uid = currentUid()
        val profile = readProfile(uid) ?: throw SocialException("not_connected")
        val companyId = profile.companyId ?: throw SocialException("company_not_selected")
        val ref = db.collection(COMPANIES).document(companyId).collection(COMPLETIONS).document("${uid}_$sessionId")
        if (!ref.get().exists) {
            ref.set(CompletionDoc.serializer(), CompletionDoc(uid, currentWeek(), minutes, Clock.System.now().toEpochMilliseconds()))
        }
        Unit
    }

    override suspend fun linkEmailPassword(email: String, password: String): SocialProfile = guarded {
        val credential = EmailAuthProvider.credential(email, password)
        linkCredential(credential)
    }

    override suspend fun linkGoogleIdToken(idToken: String, accessToken: String?): SocialProfile = guarded {
        val credential = GoogleAuthProvider.credential(idToken, accessToken)
        linkCredential(credential)
    }

    override suspend fun leaveCompany(): SocialProfile = guarded {
        val uid = currentUid()
        val profile = readProfile(uid) ?: throw SocialException("not_connected")
        // Stops new completion uploads (companyId cleared) and disables the public ranking row; prior
        // completions/rows already written stay in Firestore per data retention policy.
        profile.companyId?.let { companyId -> db.collection(COMPANIES).document(companyId).collection(COMPANY_RANKING).document(uid).delete() }
        val updated = profile.copy(companyId = null, companyName = null, jobTitle = null, companyRankingOptIn = false)
        writeProfile(updated)
        updated
    }

    private suspend fun linkCredential(credential: AuthCredential): SocialProfile {
        val user = auth.currentUser ?: throw SocialException("not_connected")
        try {
            user.linkWithCredential(credential)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (collision: FirebaseAuthUserCollisionException) { throw SocialException("credential_already_in_use") }
        return readProfile(user.uid) ?: throw SocialException("not_connected")
    }

    override suspend fun sendNudge(roomId: String, recipientId: String) = guarded {
        val sender = currentUid()
        if (sender == recipientId) throw SocialException("nudge_unavailable")
        val room = db.collection(ROOMS).document(roomId).get().takeIf { it.exists }?.data(RoomDoc.serializer())
            ?: throw SocialException("room_unavailable")
        if (room.kind != "friends") throw SocialException("nudges_disabled_for_organizations")
        val recipient = readPublicProfile(recipientId) ?: throw SocialException("nudge_unavailable")
        if (!recipient.nudgeOptIn) throw SocialException("nudge_unavailable")
        val blocked = db.collection(NUDGE_BLOCKS).document("${recipientId}_$sender").get().exists
        if (blocked) throw SocialException("nudge_unavailable")
        val nudgeId = Uuid.random().toHexString()
        db.collection(NUDGES).document(nudgeId).set(NudgeDoc.serializer(), NudgeDoc(
            roomId = roomId, senderId = sender, senderNickname = readProfile(sender)?.nickname ?: "",
            recipientId = recipientId, createdAtEpochSeconds = Clock.System.now().epochSeconds,
        ))
        Unit
    }

    override suspend fun declineNudge(nudgeId: String) = guarded {
        val uid = currentUid()
        val ref = db.collection(NUDGES).document(nudgeId)
        val nudge = ref.get().takeIf { it.exists }?.data(NudgeDoc.serializer()) ?: throw SocialException("nudge_unavailable")
        if (nudge.recipientId != uid) throw SocialException("nudge_unavailable")
        ref.set(NudgeDoc.serializer(), nudge.copy(declined = true))
        db.collection(NUDGE_BLOCKS).document("${uid}_${nudge.senderId}").set(BlockDoc.serializer(), BlockDoc(uid, nudge.senderId))
        Unit
    }

    override suspend fun unblockNudges(senderId: String) = guarded {
        val uid = currentUid()
        db.collection(NUDGE_BLOCKS).document("${uid}_$senderId").delete()
        Unit
    }

    override suspend fun reportRoom(roomId: String, reason: SocialReportReason) = guarded {
        val uid = currentUid()
        val reportId = Uuid.random().toHexString()
        db.collection(REPORTS).document(reportId).set(ReportDoc.serializer(), ReportDoc(roomId, uid, reason.name.lowercase()))
        Unit
    }

    override suspend fun exportAccount(): String = guarded {
        val uid = currentUid()
        val profile = readProfile(uid) ?: throw SocialException("not_connected")
        "{\"user_id\":\"${profile.userId}\",\"nickname\":\"${profile.nickname}\"}"
    }

    override suspend fun deleteAccount() = guarded {
        val uid = currentUid()
        db.collection(USERS).document(uid).delete()
        db.collection(PUBLIC_PROFILES).document(uid).delete()
        auth.currentUser?.delete()
        Unit
    }

    override suspend fun disconnect() = guarded { auth.signOut() }
    override fun close() {}

    // --- helpers ---

    private suspend fun ensureSignedIn(): String = auth.currentUser?.uid ?: auth.signInAnonymously().user!!.uid
    private fun currentUid(): String = auth.currentUser?.uid ?: throw SocialException("not_connected")

    private suspend fun readProfile(uid: String): SocialProfile? {
        val snapshot = db.collection(USERS).document(uid).get()
        if (!snapshot.exists) return null
        return snapshot.data(UserDoc.serializer()).toDomain(uid)
    }

    private suspend fun writeProfile(profile: SocialProfile) {
        db.collection(USERS).document(profile.userId).set(UserDoc.serializer(), UserDoc.from(profile))
        db.collection(PUBLIC_PROFILES).document(profile.userId).set(PublicProfileDoc.serializer(),
            PublicProfileDoc(profile.nickname, profile.nudgeOptIn, profile.rankingOptIn))
        propagateNicknameToRooms(profile.userId, profile.nickname)
    }

    /** Room membership docs carry their own nickname copy so ranking/live views never read another user's private profile. */
    private suspend fun propagateNicknameToRooms(uid: String, nickname: String) {
        val memberships = db.collectionGroup(MEMBERS).where { "uid" equalTo uid }.get().documents
        for (snapshot in memberships) {
            if (snapshot.data(MemberDoc.serializer()).nickname == nickname) continue
            snapshot.reference.update("nickname" to nickname)
        }
    }

    private suspend fun myRooms(uid: String, profile: SocialProfile): List<SocialRoom> {
        val memberships = db.collectionGroup(MEMBERS).where { "uid" equalTo uid }.get().documents
        return memberships.mapNotNull { snapshot ->
            val roomId = snapshot.reference.parent.parent?.id ?: return@mapNotNull null
            roomDetail(roomId, uid, profile)
        }
    }

    private suspend fun roomDetail(roomId: String, uid: String): SocialRoom? = roomDetail(roomId, uid, readProfileOrNull(uid))

    private suspend fun roomDetail(roomId: String, uid: String, profile: SocialProfile?): SocialRoom? {
        val roomRef = db.collection(ROOMS).document(roomId)
        val roomSnapshot = roomRef.get().takeIf { it.exists } ?: return null
        val room = roomSnapshot.data(RoomDoc.serializer())
        val memberRef = roomRef.collection(MEMBERS).document(uid)
        val member = memberRef.get().takeIf { it.exists }?.data(MemberDoc.serializer()) ?: return null
        val memberCount = roomRef.collection(MEMBERS).count()
        val week = currentWeek(previous = room.kind == "organization")
        val completions = roomRef.collection(WEEKLY_TOTALS).where { "week" equalTo week }.get().documents
            .map { it.data(WeeklyTotalDoc.serializer()) }
        val contributors = completions.map { it.uid }.distinct()
        val visible = room.kind != "organization" || contributors.size >= 3
        val ranking = if (room.kind == "friends" && profile != null) {
            completions.mapNotNull { total ->
                val memberDoc = roomRef.collection(MEMBERS).document(total.uid).get().takeIf { it.exists }?.data(MemberDoc.serializer())
                if (memberDoc?.rankingOptIn == true) SocialRankingEntry(total.uid, memberDoc.nickname, total.minutes) else null
            }.sortedByDescending { it.completedMinutes }
        } else emptyList()
        val live = if (room.kind == "friends") {
            val now = Clock.System.now().epochSeconds
            roomRef.collection(MEMBERS).get().documents.map { it.data(MemberDoc.serializer()) }
                .filter { it.liveOptIn && it.liveUntilEpochSeconds > now }
                .map { m -> SocialLiveMember(m.uid, m.nickname) }
        } else emptyList()
        return SocialRoom(
            id = roomId, name = room.name, kind = kind(room.kind), closed = room.closed,
            role = if (member.role == "owner") RoomRole.OWNER else RoomRole.PARTICIPANT,
            goalMinutes = room.goalMinutes, week = week, invitationDurationMinutes = room.invitationDurationMinutes,
            privacyVersion = room.privacyVersion, privacyText = PRIVACY_TEXT, memberCount = memberCount.toInt(),
            liveOptIn = member.liveOptIn, rankingOptIn = member.rankingOptIn,
            aggregate = OrganizationAggregate(visible, if (visible) completions.sumOf { it.minutes } else null,
                if (visible) completions.sumOf { it.sessions } else null),
            ranking = ranking, live = live,
            inviteCode = if (member.role == "owner" && !room.closed) room.inviteCode else null,
        )
    }

    private suspend fun readProfileOrNull(uid: String): SocialProfile? = try { readProfile(uid) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    private suspend fun readPublicProfile(uid: String): PublicProfileDoc? =
        db.collection(PUBLIC_PROFILES).document(uid).get().takeIf { it.exists }?.data(PublicProfileDoc.serializer())

    private suspend fun listNudges(uid: String): List<SocialNudge> = db.collection(NUDGES)
        .where { "recipientId" equalTo uid }.where { "declined" equalTo false }
        .get().documents.map { it.data(NudgeDoc.serializer()) }
        .filter { Clock.System.now().epochSeconds - it.createdAtEpochSeconds < 86_400 }
        .map { SocialNudge(it.roomId + "_" + it.senderId, it.roomId, it.senderId, it.senderNickname) }

    /** Computed from the caller's own completions only; never reads another employee's data. */
    private suspend fun personalStats(uid: String, companyId: String): PersonalStats {
        val cutoff = Clock.System.now().toEpochMilliseconds() - LAST_7_DAYS_MILLIS
        val completions = db.collection(COMPANIES).document(companyId).collection(COMPLETIONS)
            .where { "uid" equalTo uid }.get().documents.map { it.data(CompletionDoc.serializer()) }
        val recent = completions.filter { it.epochMillis >= cutoff }
        return PersonalStats(
            last7Days = StatsRange(recent.sumOf { it.minutes }, recent.size),
            allTime = StatsRange(completions.sumOf { it.minutes }, completions.size),
        )
    }

    /**
     * Keeps the caller's own public ranking row (`companies/{companyId}/members/{uid}`) in sync with
     * its own [personalStats], recomputed client-side on every dashboard load (Spark plan: no Cloud
     * Function keeps this fresh in the background). Opting out deletes the row so coworkers can no
     * longer see it; scores are self-reported and never server-verified.
     */
    private suspend fun syncCompanyRanking(uid: String, companyId: String, profile: SocialProfile, stats: PersonalStats?) {
        val ref = db.collection(COMPANIES).document(companyId).collection(COMPANY_RANKING).document(uid)
        if (profile.companyRankingOptIn && stats != null) {
            ref.set(CompanyMemberDoc.serializer(), CompanyMemberDoc(
                uid = uid, nickname = profile.nickname, jobTitle = profile.jobTitle,
                last7DaysMinutes = stats.last7Days.completedMinutes, last7DaysSessions = stats.last7Days.completedSessions,
                allTimeMinutes = stats.allTime.completedMinutes, allTimeSessions = stats.allTime.completedSessions,
            ))
        } else ref.delete()
    }

    /** Only same-company coworkers who opted in; enforced both by what we write and by Firestore rules. */
    private suspend fun companyRankingRows(companyId: String): List<CompanyRankingEntry> =
        db.collection(COMPANIES).document(companyId).collection(COMPANY_RANKING).get().documents
            .map { it.data(CompanyMemberDoc.serializer()) }
            .map { doc -> CompanyRankingEntry(
                userId = doc.uid, nickname = doc.nickname, jobTitle = doc.jobTitle,
                last7Days = StatsRange(doc.last7DaysMinutes, doc.last7DaysSessions),
                allTime = StatsRange(doc.allTimeMinutes, doc.allTimeSessions),
            ) }

    /** Degrades to "no personal stats yet" instead of failing the join on a permission/index hiccup. */
    private suspend fun readPersonalStatsOrNull(uid: String, companyId: String): PersonalStats? =
        try { personalStats(uid, companyId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FirebaseFirestoreException) { null }

    /** Best-effort ranking sync; never lets a transient read/write failure block the dashboard. */
    private suspend fun writeCompanyRankingOrIgnore(uid: String, companyId: String, profile: SocialProfile, stats: PersonalStats) {
        try { syncCompanyRanking(uid, companyId, profile, stats) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FirebaseFirestoreException) { }
    }

    /** Degrades to an empty leaderboard instead of failing the join when there are no rows yet. */
    private suspend fun readCompanyRankingRowsOrEmpty(companyId: String): List<CompanyRankingEntry> =
        try { companyRankingRows(companyId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FirebaseFirestoreException) { emptyList() }

    private fun currentWeek(previous: Boolean = false): String {
        val today = Clock.System.todayIn(TimeZone.UTC)
        val monday = today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
        return (if (previous) monday.minus(7, DateTimeUnit.DAY) else monday).toString()
    }

    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (social: SocialException) { throw social }
    catch (auth: FirebaseAuthException) { throw SocialException(mapAuthErrorCode(auth.code)) }
    catch (firestore: FirebaseFirestoreException) { throw SocialException(firestore.code.name.lowercase()) }
    catch (_: Exception) { throw SocialException("network_unavailable") }

    companion object { const val ENDPOINT = "Firebase" }
}

/**
 * Normalizes platform-specific Firebase Auth error codes (Android `errorCode` strings, iOS
 * `AuthErrorCode` names) into stable [SocialException] codes so Clan can explain what to enable or
 * configure instead of showing a generic network failure.
 */
private fun mapAuthErrorCode(code: String?): String {
    val upper = code?.uppercase() ?: return "network_unavailable"
    return when {
        "OPERATION_NOT_ALLOWED" in upper || "ADMIN_RESTRICTED_OPERATION" in upper -> "auth_provider_disabled"
        "USER_DISABLED" in upper -> "auth_user_disabled"
        "TOO_MANY_REQUESTS" in upper -> "auth_rate_limited"
        "NETWORK" in upper -> "network_unavailable"
        "USER_TOKEN_EXPIRED" in upper || "INVALID_USER_TOKEN" in upper || "USER_NOT_FOUND" in upper -> "unauthorized"
        else -> "auth_error"
    }
}

private const val USERS = "users"
private const val COMPANIES = "companies"
private const val ROOMS = "rooms"
private const val MEMBERS = "members"
/** Distinct from [MEMBERS] (rooms) so the `db.collectionGroup(MEMBERS)` room queries never pick up company ranking rows. */
private const val COMPANY_RANKING = "ranking"
private const val WEEKLY_TOTALS = "weeklyTotals"
private const val COMPLETIONS = "completions"
private const val NUDGES = "nudges"
private const val NUDGE_BLOCKS = "nudgeBlocks"
private const val REPORTS = "reports"
private const val PUBLIC_PROFILES = "publicProfiles"
private const val LIVE_TTL_SECONDS = 90L
private const val LAST_7_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
private const val PRIVACY_TEXT = "Rooms receive only voluntarily submitted weekly focus totals. LIVE and rankings are off by default and require separate account and room consent."

private fun kind(value: String): RoomKind = when (value) { "friends" -> RoomKind.FRIENDS; "organization" -> RoomKind.ORGANIZATION; else -> throw SocialException("invalid_response") }

@Serializable
private data class UserDoc(
    val nickname: String = "", val timezone: String = "UTC",
    val liveOptIn: Boolean = false, val rankingOptIn: Boolean = false, val nudgeOptIn: Boolean = false,
    val quietStartMinute: Int = 1320, val quietEndMinute: Int = 480,
    val companyId: String? = null, val companyName: String? = null, val jobTitle: String? = null,
    val companyRankingOptIn: Boolean = false,
) {
    fun toDomain(uid: String) = SocialProfile(uid, nickname, timezone, liveOptIn, rankingOptIn, nudgeOptIn,
        quietStartMinute, quietEndMinute, companyId, companyName, jobTitle, companyRankingOptIn)
    companion object {
        fun from(v: SocialProfile) = UserDoc(v.nickname, v.timezone, v.liveOptIn, v.rankingOptIn, v.nudgeOptIn,
            v.quietStartMinute, v.quietEndMinute, v.companyId, v.companyName, v.jobTitle, v.companyRankingOptIn)
    }
}

@Serializable
private data class RoomDoc(
    val name: String = "", val kind: String = "friends", val ownerId: String = "", val closed: Boolean = false,
    val goalMinutes: Int = 500, val invitationDurationMinutes: Int = 25, val privacyVersion: Int = 1,
    val inviteCode: String = "",
)

@Serializable
private data class MemberDoc(
    val uid: String = "", val role: String = "participant", val nickname: String = "",
    val liveOptIn: Boolean = false, val rankingOptIn: Boolean = false, val liveUntilEpochSeconds: Long = 0,
)

@Serializable
private data class WeeklyTotalDoc(val week: String = "", val uid: String = "", val minutes: Int = 0, val sessions: Int = 0)

@Serializable
private data class CompletionDoc(val uid: String = "", val week: String = "", val minutes: Int = 0, val epochMillis: Long = 0)

/** Public ranking row; only exists while the owner has opted in (see [FirebaseSocialRepository.syncCompanyRanking]). */
@Serializable
private data class CompanyMemberDoc(
    val uid: String = "", val nickname: String = "", val jobTitle: String? = null,
    val last7DaysMinutes: Int = 0, val last7DaysSessions: Int = 0,
    val allTimeMinutes: Int = 0, val allTimeSessions: Int = 0,
)

/** Minimal cross-user profile: only what room/nudge features need to show about another person. */
@Serializable
private data class PublicProfileDoc(val nickname: String = "", val nudgeOptIn: Boolean = false, val rankingOptIn: Boolean = false)

@Serializable
private data class NudgeDoc(
    val roomId: String = "", val senderId: String = "", val senderNickname: String = "",
    val recipientId: String = "", val createdAtEpochSeconds: Long = 0, val declined: Boolean = false,
)

@Serializable
private data class BlockDoc(val recipientId: String = "", val senderId: String = "")

@Serializable
private data class ReportDoc(val roomId: String = "", val reporterId: String = "", val reason: String = "")
