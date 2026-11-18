package pl.flipfocus.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import pl.flipfocus.domain.*

/** Optional rooms have their own actor; network failures cannot delay the local focus timer. */
class SocialViewModel(
    private val repository: SocialRepository,
    scope: CoroutineScope,
    autoRefresh: Boolean = true,
    private val companyOutbox: SocialOutboxStore = InMemorySocialOutboxStore(),
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val workScope = CoroutineScope(scope.coroutineContext + job)
    private val intents = Channel<SocialIntent>(Channel.UNLIMITED)
    private val effectChannel = Channel<SocialEffect>(Channel.BUFFERED)
    private val mutableState = MutableStateFlow(SocialUiState())
    val state: StateFlow<SocialUiState> = mutableState.asStateFlow()
    val effects: Flow<SocialEffect> = effectChannel.receiveAsFlow()
    private var focus: FocusSession? = null
    private val eligibleRooms = mutableMapOf<String, Set<String>>()
    private data class Contribution(val roomId: String, val sessionId: String, val minutes: Int)
    private val pending = mutableSetOf<Contribution>()
    private val submitted = mutableSetOf<Pair<String, String>>()
    private val visibleRooms = mutableSetOf<String>()
    private data class CompanyContribution(val sessionId: String, val minutes: Int)
    private val companyPending = mutableSetOf<CompanyContribution>()

    init {
        workScope.launch {
            loadCompanyOutbox()
            updatePendingCount()
            guarded {
                val connection = repository.loadConnection()
                mutableState.update { it.copy(connection = connection, isLoading = false) }
                if (connection != null) refresh()
            }
            mutableState.update { it.copy(isLoading = false) }
            for (intent in intents) {
                val showBusy = intent !is SocialIntent.FocusChanged && intent != SocialIntent.ClearError
                if (showBusy) mutableState.update { it.copy(isBusy = true) }
                guarded { handle(intent) }
                if (showBusy) mutableState.update { it.copy(isBusy = false) }
            }
        }
        if (autoRefresh) workScope.launch {
            while (isActive) {
                delay(30_000)
                if (state.value.connection != null) intents.trySend(SocialIntent.Refresh)
            }
        }
    }

    fun accept(intent: SocialIntent) { intents.trySend(intent) }
    fun close() { intents.close(); job.cancel(); effectChannel.close(); repository.close() }

    private suspend fun handle(intent: SocialIntent) {
        when (intent) {
            is SocialIntent.SearchCompanies -> {
                val companies = repository.searchCompanies(intent.endpoint, intent.query)
                mutableState.update { it.copy(companies = companies, errorCode = null) }
            }
            is SocialIntent.CreateCompany -> {
                val connection = repository.createCompany(intent.name)
                mutableState.update { it.copy(connection = connection, errorCode = null) }
                refresh()
            }
            is SocialIntent.ConnectCompany -> {
                if (!intent.privacyAccepted) throw SocialException("privacy_required")
                val connection = repository.connectCompany(intent.endpoint, intent.companyId, intent.nickname, intent.jobTitle,
                    intent.timezone, true, intent.companyRankingOptIn)
                if (state.value.connection == null) {
                    resetSessionSharing()
                    mutableState.value = SocialUiState(connection = connection, isLoading = false, isBusy = true)
                } else {
                    mutableState.update { it.copy(connection = connection, errorCode = null) }
                }
                refresh()
            }
            is SocialIntent.Connect -> {
                if (!intent.privacyAccepted) throw SocialException("privacy_required")
                val connection = repository.connect(intent.endpoint, intent.nickname, intent.timezone, true, intent.existingToken)
                resetSessionSharing()
                mutableState.value = SocialUiState(connection = connection, isLoading = false, isBusy = true)
                refresh()
            }
            SocialIntent.Disconnect -> {
                // Best-effort final flush so a reachable network still delivers pending data instead of losing it.
                flushPending()
                stopPresenceBestEffort()
                repository.disconnect()
                resetSessionSharing()
                mutableState.value = SocialUiState(isLoading = false)
            }
            SocialIntent.DeleteAccount -> {
                repository.deleteAccount()
                resetSessionSharing()
                mutableState.value = SocialUiState(
                    isLoading = false,
                    companies = state.value.companies,
                )
                emit(SocialEffect.Message("account_deleted"))
            }
            SocialIntent.Refresh -> if (state.value.connection != null) {
                flushPending()
                refresh()
                publishPresence()
            }
            is SocialIntent.SelectRoom -> if (state.value.rooms.any { it.id == intent.roomId }) {
                mutableState.update { it.copy(selectedRoomId = intent.roomId) }
            }
            is SocialIntent.CreateRoom -> {
                val room = repository.createRoom(intent.name, intent.kind, intent.goalMinutes, intent.invitationDurationMinutes, intent.privacyAccepted)
                upsert(room, select = true)
            }
            is SocialIntent.PreviewInvitation -> {
                val invitation = repository.previewInvitation(intent.code.trim())
                mutableState.update { it.copy(invitation = invitation) }
            }
            is SocialIntent.JoinInvitation -> {
                if (!intent.privacyAccepted) throw SocialException("privacy_required")
                val invitation = state.value.invitation ?: throw SocialException("invitation_required")
                val room = repository.joinRoom(invitation.code, invitation.privacyVersion)
                upsert(room, select = true)
                mutableState.update { it.copy(invitation = null) }
                emit(SocialEffect.StartInvitationSession(invitation.durationMinutes))
            }
            SocialIntent.DismissInvitation -> mutableState.update { it.copy(invitation = null) }
            is SocialIntent.UpdateProfile -> {
                val profile = repository.updateProfile(intent.profile)
                mutableState.update { it.copy(connection = it.connection?.copy(profile = profile)) }
                publishPresence()
                refresh()
            }
            is SocialIntent.SelectCompany -> {
                val profile = repository.selectCompany(intent.companyId)
                mutableState.update { it.copy(connection = it.connection?.copy(profile = profile)) }
                refresh()
            }
            is SocialIntent.LinkEmailPassword -> {
                val profile = repository.linkEmailPassword(intent.email, intent.password)
                mutableState.update { it.copy(connection = it.connection?.copy(profile = profile)) }
                emit(SocialEffect.Message("account_secured"))
            }
            is SocialIntent.LinkGoogle -> {
                val profile = repository.linkGoogleIdToken(intent.idToken, intent.accessToken)
                mutableState.update { it.copy(connection = it.connection?.copy(profile = profile)) }
                emit(SocialEffect.Message("account_secured"))
            }
            SocialIntent.LeaveCompany -> {
                val profile = repository.leaveCompany()
                companyPending.clear()
                saveCompanyOutbox()
                mutableState.update { it.copy(connection = it.connection?.copy(profile = profile),
                    companyAggregateDeferred = false, personalStats = null, companyRanking = emptyList()) }
                emit(SocialEffect.Message("company_sharing_stopped"))
            }
            is SocialIntent.UpdateRoomPreferences -> {
                upsert(repository.updateRoomPreferences(intent.roomId, intent.liveOptIn, intent.rankingOptIn))
                publishPresence()
            }
            is SocialIntent.LeaveRoom -> {
                repository.leaveRoom(intent.roomId)
                pending.removeAll { it.roomId == intent.roomId }
                eligibleRooms.entries.removeAll { intent.roomId in it.value }
                mutableState.update { it.copy(rooms = it.rooms.filterNot { room -> room.id == intent.roomId }) }
                selectAvailableRoom()
            }
            is SocialIntent.CloseRoom -> {
                upsert(repository.closeRoom(intent.roomId))
                pending.removeAll { it.roomId == intent.roomId }
            }
            is SocialIntent.SendNudge -> {
                repository.sendNudge(intent.roomId, intent.recipientId)
                emit(SocialEffect.Message("nudge_sent"))
            }
            is SocialIntent.DeclineNudge -> {
                repository.declineNudge(intent.nudgeId)
                mutableState.update { it.copy(nudges = it.nudges.filterNot { nudge -> nudge.id == intent.nudgeId }) }
                emit(SocialEffect.Message("nudge_declined"))
            }
            is SocialIntent.UnblockNudges -> { repository.unblockNudges(intent.senderId); emit(SocialEffect.Message("nudge_unblocked")) }
            is SocialIntent.ReportRoom -> { repository.reportRoom(intent.roomId, intent.reason); emit(SocialEffect.Message("report_received")) }
            SocialIntent.ExportAccount -> emit(SocialEffect.ExportReady(repository.exportAccount(), "flipfocus-account.json"))
            is SocialIntent.FocusChanged -> focusChanged(intent.session)
            SocialIntent.ClearError -> mutableState.update { it.copy(errorCode = null) }
        }
    }

    private suspend fun focusChanged(session: FocusSession?) {
        val previous = focus
        focus = session
        if (state.value.connection == null) return
        if (previous?.id == session?.id && previous?.state == session?.state) return
        if (session?.state == FocusState.FOCUSING && session.id !in eligibleRooms) {
            // Only sessions observed active after connecting can contribute; never replay local history.
            eligibleRooms[session.id] = state.value.rooms.filterNot { it.closed }.map { it.id }.toSet()
        }
        if (session?.state == FocusState.COMPLETED) {
            val eligible = eligibleRooms.remove(session.id)
            eligible?.forEach { roomId ->
                if ((roomId to session.id) !in submitted) pending += Contribution(roomId, session.id, session.duration.minutes)
            }
            val companies = state.value.connection?.profile?.let { companyIds(it) }.orEmpty()
            if (eligible != null && companies.isNotEmpty() && companyPending.none { it.sessionId == session.id }) {
                companyPending += CompanyContribution(session.id, session.duration.minutes)
                saveCompanyOutbox()
            }
        } else if (session?.state == FocusState.ABANDONED) eligibleRooms.remove(session.id)
        updatePendingCount()
        publishPresence()
        flushPending()
    }

    /** Not-yet-confirmed-uploaded completions; the UI warns before an action (disconnect) would drop these. */
    private fun updatePendingCount() {
        mutableState.update { it.copy(pendingSyncCount = pending.size + companyPending.size) }
    }

    private suspend fun publishPresence() {
        val profile = state.value.connection?.profile ?: return
        val focusing = focus?.state == FocusState.FOCUSING
        val desired = state.value.rooms.filter { !it.closed && it.kind == RoomKind.FRIENDS &&
            focusing && profile.liveOptIn && it.liveOptIn }.map { it.id }.toSet()
        for (roomId in desired + visibleRooms) {
            try {
                val visible = roomId in desired
                repository.heartbeat(roomId, visible)
                if (visible) visibleRooms += roomId else visibleRooms -= roomId
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: SocialException) { mutableState.update { it.copy(errorCode = error.code) } }
        }
    }

    private suspend fun stopPresenceBestEffort() {
        for (roomId in visibleRooms.toList()) {
            try { repository.heartbeat(roomId, false) } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Server-side 90-second expiry handles an unavailable network. */ }
        }
    }

    private suspend fun flushPending() {
        for (item in pending.toList()) {
            try {
                upsert(repository.submitCompletion(item.roomId, item.sessionId, item.minutes))
                pending.remove(item)
                submitted += item.roomId to item.sessionId
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: SocialException) {
                if (error.code in setOf("not_member", "room_closed", "room_not_found", "forbidden")) pending.remove(item)
                mutableState.update { it.copy(errorCode = error.code) }
            }
        }
        var outboxChanged = false
        for (item in companyPending.toList()) {
            try {
                repository.submitCompanyCompletion(item.sessionId, item.minutes)
                companyPending.remove(item)
                outboxChanged = true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: SocialException) {
                if (error.code in setOf("company_not_selected", "invalid_minutes")) { companyPending.remove(item); outboxChanged = true }
                mutableState.update { it.copy(errorCode = error.code) }
            }
        }
        if (outboxChanged) saveCompanyOutbox()
        updatePendingCount()
    }

    private suspend fun loadCompanyOutbox() {
        val raw = try { companyOutbox.read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return
        raw.split(';').mapNotNull { entry ->
            val (sessionId, minutes) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            minutes.toIntOrNull()?.let { CompanyContribution(sessionId, it) }
        }.forEach { companyPending += it }
    }

    private suspend fun saveCompanyOutbox() {
        val encoded = companyPending.joinToString(";") { "${it.sessionId}:${it.minutes}" }
        try { companyOutbox.write(encoded) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* retried on next flush */ }
    }

    private suspend fun refresh() {
        val dashboard = repository.dashboard()
        mutableState.update { it.copy(connection = it.connection?.copy(profile = dashboard.profile),
            rooms = dashboard.rooms, nudges = dashboard.nudges, companyAggregateDeferred = dashboard.companyAggregateDeferred,
            personalStats = dashboard.personalStats, companyRanking = dashboard.companyRanking) }
        selectAvailableRoom()
    }

    private fun upsert(room: SocialRoom, select: Boolean = false) {
        mutableState.update { current -> current.copy(
            rooms = if (current.rooms.any { it.id == room.id }) current.rooms.map { if (it.id == room.id) room else it } else current.rooms + room,
            selectedRoomId = if (select) room.id else current.selectedRoomId) }
        selectAvailableRoom()
    }
    private fun selectAvailableRoom() {
        mutableState.update { current -> current.copy(selectedRoomId = current.selectedRoomId?.takeIf { id -> current.rooms.any { it.id == id } }
            ?: current.rooms.firstOrNull { !it.closed }?.id ?: current.rooms.firstOrNull()?.id) }
    }
    private fun resetSessionSharing() {
        focus = null; eligibleRooms.clear(); pending.clear(); submitted.clear(); visibleRooms.clear()
        companyPending.clear(); workScope.launch { saveCompanyOutbox() }
        updatePendingCount()
    }
    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: SocialException) { mutableState.update { it.copy(errorCode = error.code) }
        } catch (_: Exception) { mutableState.update { it.copy(errorCode = "network_unavailable") } }
    }
    private fun emit(effect: SocialEffect) { effectChannel.trySend(effect) }
}

private fun companyIds(profile: SocialProfile): List<String> =
    profile.companyMemberships.map { it.id }.ifEmpty { listOfNotNull(profile.companyId) }.distinct()

/** Default outbox for previews/tests; production hosts provide a persistent-store-backed implementation. */
class InMemorySocialOutboxStore : SocialOutboxStore {
    private var value: String? = null
    override suspend fun read(): String? = value
    override suspend fun write(value: String) { this.value = value }
}
