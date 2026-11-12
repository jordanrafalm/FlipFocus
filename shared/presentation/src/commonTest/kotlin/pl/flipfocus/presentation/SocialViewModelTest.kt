package pl.flipfocus.presentation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import pl.flipfocus.domain.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SocialViewModelTest {
    @Test fun anUnconnectedAppDoesNotMakeNetworkCallsAndConsentCannotBeSkipped() = runTest {
        val repository = TestRepository(connected = false)
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent()
        assertNull(vm.state.value.connection)
        assertEquals(0, repository.dashboardCalls)
        vm.accept(SocialIntent.Connect("https://focus.example", "Ada", false)); runCurrent()
        assertEquals("privacy_required", vm.state.value.errorCode)
        assertEquals(0, repository.connectCalls)
        vm.accept(SocialIntent.FocusChanged(active())); runCurrent()
        assertTrue(repository.heartbeats.isEmpty())
        vm.close()
    }

    @Test fun aHistoricalCompletedSessionIsNeverUploaded() = runTest {
        val repository = TestRepository()
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent()
        vm.accept(SocialIntent.FocusChanged(completed())); runCurrent()
        assertTrue(repository.completions.isEmpty())
        vm.close()
    }

    @Test fun aNewCompletionIsUploadedOnceWithoutLiveConsent() = runTest {
        val repository = TestRepository()
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent()
        vm.accept(SocialIntent.FocusChanged(active())); runCurrent()
        vm.accept(SocialIntent.FocusChanged(active().copy(focusedMillis = 10_000))); runCurrent()
        vm.accept(SocialIntent.FocusChanged(completed())); runCurrent()
        vm.accept(SocialIntent.FocusChanged(completed())); runCurrent()
        assertEquals(listOf("room-1/session-1/1"), repository.completions)
        assertTrue(repository.heartbeats.isEmpty())
        vm.close()
    }

    @Test fun liveRequiresBothConsentsAndStopsImmediatelyWhenPaused() = runTest {
        val repository = TestRepository().apply {
            profile = profile.copy(liveOptIn = true)
            rooms = listOf(room().copy(liveOptIn = true))
        }
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent()
        vm.accept(SocialIntent.FocusChanged(active())); runCurrent()
        vm.accept(SocialIntent.FocusChanged(active().copy(focusedMillis = 2_000))); runCurrent()
        assertEquals(listOf("room-1" to true), repository.heartbeats)
        vm.accept(SocialIntent.FocusChanged(active().copy(state = FocusState.GRACE_PERIOD))); runCurrent()
        assertEquals(listOf("room-1" to true, "room-1" to false), repository.heartbeats)
        vm.accept(SocialIntent.UpdateRoomPreferences("room-1", false, false)); runCurrent()
        vm.accept(SocialIntent.FocusChanged(active())); runCurrent()
        assertEquals(2, repository.heartbeats.size)
        vm.close()
    }

    @Test fun organizationsNeverPublishIndividualLiveStatus() = runTest {
        val repository = TestRepository().apply {
            profile = profile.copy(liveOptIn = true)
            rooms = listOf(room().copy(kind = RoomKind.ORGANIZATION, liveOptIn = true))
        }
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent(); vm.accept(SocialIntent.FocusChanged(active())); runCurrent()
        assertTrue(repository.heartbeats.isEmpty())
        vm.close()
    }

    @Test fun failedContributionsRetryIdempotentlyAndDisconnectDropsPendingAccountData() = runTest {
        val repository = TestRepository().apply { failCompletions = true }
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent()
        vm.accept(SocialIntent.FocusChanged(active())); runCurrent()
        vm.accept(SocialIntent.FocusChanged(completed())); runCurrent()
        assertEquals("network_unavailable", vm.state.value.errorCode)
        repository.failCompletions = false
        vm.accept(SocialIntent.Refresh); runCurrent()
        assertEquals(listOf("room-1/session-1/1", "room-1/session-1/1"), repository.completions)
        vm.accept(SocialIntent.Refresh); runCurrent()
        assertEquals(2, repository.completions.size)
        vm.accept(SocialIntent.Disconnect); runCurrent()
        assertNull(vm.state.value.connection)
        assertTrue(vm.state.value.rooms.isEmpty())
        vm.close()
    }

    @Test fun invitationNeedsPreviewAndExplicitRoomPrivacyAcceptance() = runTest {
        val repository = TestRepository()
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent()
        vm.accept(SocialIntent.PreviewInvitation("invite")); runCurrent()
        assertEquals("Study together", vm.state.value.invitation?.name)
        vm.accept(SocialIntent.JoinInvitation(false)); runCurrent()
        assertEquals(0, repository.joinCalls)
        assertEquals("privacy_required", vm.state.value.errorCode)
        vm.accept(SocialIntent.JoinInvitation(true)); runCurrent()
        assertEquals(1, repository.joinCalls)
        assertNull(vm.state.value.invitation)
        vm.close()
    }

    @Test fun leavingRoomDoesNotSendPendingCompletionToThatRoom() = runTest {
        val repository = TestRepository()
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent()
        vm.accept(SocialIntent.FocusChanged(active())); runCurrent()
        vm.accept(SocialIntent.LeaveRoom("room-1")); runCurrent()
        vm.accept(SocialIntent.FocusChanged(completed())); runCurrent()
        assertTrue(repository.completions.isEmpty())
        vm.close()
    }

    private fun active() = FocusSession("session-1", FocusDuration.ofMinutes(1), FocusState.FOCUSING, startedAtEpochMillis = 1_000)
    private fun completed() = active().copy(state = FocusState.COMPLETED, focusedMillis = 60_000, endedAtEpochMillis = 61_000, endReason = FocusEndReason.TARGET_REACHED)

    @Test fun companyRefreshUsesPersonalStatsAndDoesNotRequireCompanyAggregate() = runTest {
        val repository = TestRepository().apply {
            profile = profile.copy(companyId = "apple", companyName = "Apple", jobTitle = "Engineer")
            dashboardResult = SocialDashboard(
                profile = profile,
                rooms = rooms,
                nudges = emptyList(),
                personalStats = PersonalStats(StatsRange(50, 2), StatsRange(125, 5)),
                companyAggregateDeferred = true,
            )
        }
        val vm = SocialViewModel(repository, backgroundScope, autoRefresh = false)
        runCurrent()

        assertTrue(vm.state.value.companyAggregateDeferred)
        assertEquals(50, vm.state.value.personalStats?.last7Days?.completedMinutes)
        assertEquals(125, vm.state.value.personalStats?.allTime?.completedMinutes)
        vm.close()
    }

    private class TestRepository(connected: Boolean = true) : SocialRepository {
        var profile = SocialProfile("user-1", "Ada")
        var connection: SocialConnection? = if (connected) SocialConnection("https://focus.example", profile) else null
        var rooms = listOf(room())
        val heartbeats = mutableListOf<Pair<String, Boolean>>()
        val completions = mutableListOf<String>()
        var failCompletions = false
        var dashboardCalls = 0
        var dashboardResult: SocialDashboard? = null
        var connectCalls = 0
        var joinCalls = 0
        override suspend fun loadConnection(): SocialConnection? = connection?.copy(profile = profile)
        override suspend fun connect(endpoint: String, nickname: String, timezone: String, privacyAccepted: Boolean, existingToken: String?): SocialConnection {
            connectCalls++
            return SocialConnection(endpoint, profile).also { connection = it }
        }
        override suspend fun dashboard(): SocialDashboard {
            dashboardCalls++
            return dashboardResult ?: SocialDashboard(profile, rooms, emptyList())
        }
        override suspend fun updateProfile(profile: SocialProfile): SocialProfile { this.profile = profile; return profile }
        override suspend fun createRoom(name: String, kind: RoomKind, goalMinutes: Int, invitationDurationMinutes: Int, privacyAccepted: Boolean): SocialRoom = room()
        override suspend fun previewInvitation(code: String) = SocialInvitation(code, "Study together", RoomKind.FRIENDS, 1, "Room policy", 25)
        override suspend fun joinRoom(code: String, privacyVersion: Int): SocialRoom { joinCalls++; return room() }
        override suspend fun updateRoomPreferences(roomId: String, liveOptIn: Boolean, rankingOptIn: Boolean): SocialRoom {
            val result = rooms.first { it.id == roomId }.copy(liveOptIn = liveOptIn, rankingOptIn = rankingOptIn)
            rooms = rooms.map { if (it.id == roomId) result else it }; return result
        }
        override suspend fun leaveRoom(roomId: String) { rooms = rooms.filterNot { it.id == roomId } }
        override suspend fun closeRoom(roomId: String): SocialRoom = room().copy(closed = true)
        override suspend fun heartbeat(roomId: String, focusing: Boolean) { heartbeats += roomId to focusing }
        override suspend fun submitCompletion(roomId: String, sessionId: String, minutes: Int): SocialRoom {
            completions += "$roomId/$sessionId/$minutes"
            if (failCompletions) throw SocialException("network_unavailable")
            return rooms.first { it.id == roomId }
        }
        override suspend fun sendNudge(roomId: String, recipientId: String) {}
        override suspend fun declineNudge(nudgeId: String) {}
        override suspend fun unblockNudges(senderId: String) {}
        override suspend fun reportRoom(roomId: String, reason: SocialReportReason) {}
        override suspend fun exportAccount(): String = "{}"
        override suspend fun deleteAccount() { connection = null }
        override suspend fun disconnect() { connection = null }
    }

    companion object {
        private fun room() = SocialRoom("room-1", "Study", RoomKind.FRIENDS, false, RoomRole.PARTICIPANT,
            500, "2026-09-21", 25, 1, "Privacy", 2, false, false,
            OrganizationAggregate(true, 0, 0), emptyList(), emptyList())
    }
}
