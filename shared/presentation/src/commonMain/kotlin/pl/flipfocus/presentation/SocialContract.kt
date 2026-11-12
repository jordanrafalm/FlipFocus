package pl.flipfocus.presentation

import pl.flipfocus.domain.*

data class SocialUiState(
    val connection: SocialConnection? = null,
    val rooms: List<SocialRoom> = emptyList(),
    val nudges: List<SocialNudge> = emptyList(),
    val selectedRoomId: String? = null,
    val invitation: SocialInvitation? = null,
    val companies: List<Company> = emptyList(),
    /** True once a company is selected: Spark plan has no server-side aggregation, so the company-wide total is deferred. */
    val companyAggregateDeferred: Boolean = false,
    val personalStats: PersonalStats? = null,
    /** Only coworkers who opted into company ranking are present; self-reported, never server-verified. */
    val companyRanking: List<CompanyRankingEntry> = emptyList(),
    /** Room + company completions not yet confirmed uploaded. Disconnecting loses these; surface this before confirming. */
    val pendingSyncCount: Int = 0,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val errorCode: String? = null,
) {
    val selectedRoom: SocialRoom? get() = rooms.firstOrNull { it.id == selectedRoomId }
}

sealed interface SocialIntent {
    data class Connect(val endpoint: String, val nickname: String, val privacyAccepted: Boolean,
        val existingToken: String? = null, val timezone: String = "UTC") : SocialIntent
    data class SearchCompanies(val endpoint: String, val query: String) : SocialIntent
    data class ConnectCompany(val endpoint: String, val companyId: String, val nickname: String, val jobTitle: String = "",
        val privacyAccepted: Boolean, val companyRankingOptIn: Boolean = false, val timezone: String = "UTC") : SocialIntent
    data class SelectRoom(val roomId: String) : SocialIntent
    data class CreateRoom(val name: String, val kind: RoomKind = RoomKind.FRIENDS,
        val goalMinutes: Int = 500, val invitationDurationMinutes: Int = 25,
        val privacyAccepted: Boolean = false) : SocialIntent
    data class PreviewInvitation(val code: String) : SocialIntent
    data class JoinInvitation(val privacyAccepted: Boolean) : SocialIntent
    data class UpdateProfile(val profile: SocialProfile) : SocialIntent
    data class LinkEmailPassword(val email: String, val password: String) : SocialIntent
    data class LinkGoogle(val idToken: String, val accessToken: String? = null) : SocialIntent
    data object LeaveCompany : SocialIntent
    data class UpdateRoomPreferences(val roomId: String, val liveOptIn: Boolean, val rankingOptIn: Boolean) : SocialIntent
    data class LeaveRoom(val roomId: String) : SocialIntent
    data class CloseRoom(val roomId: String) : SocialIntent
    data class SendNudge(val roomId: String, val recipientId: String) : SocialIntent
    data class DeclineNudge(val nudgeId: String) : SocialIntent
    data class UnblockNudges(val senderId: String) : SocialIntent
    data class ReportRoom(val roomId: String, val reason: SocialReportReason) : SocialIntent
    data class FocusChanged(val session: FocusSession?) : SocialIntent
    data object Disconnect : SocialIntent
    data object Refresh : SocialIntent
    data object DismissInvitation : SocialIntent
    data object ExportAccount : SocialIntent
    data object DeleteAccount : SocialIntent
    data object ClearError : SocialIntent
}

sealed interface SocialEffect {
    data class Message(val code: String) : SocialEffect
    data class ExportReady(val content: String, val fileName: String) : SocialEffect
    data class StartInvitationSession(val minutes: Int) : SocialEffect
}
