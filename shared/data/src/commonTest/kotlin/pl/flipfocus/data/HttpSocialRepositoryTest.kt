package pl.flipfocus.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pl.flipfocus.domain.*
import kotlin.test.*

class HttpSocialRepositoryTest {
    @Test fun accountRequiresConsentAndSecureEndpointBeforeAnyNetworkRequest() = runTest {
        val transport = FakeTransport()
        val repository = HttpSocialRepository(MemoryStringStore(), MemorySecretStore(), transport)
        assertEquals("privacy_required", assertFailsWith<SocialException> { repository.connect("https://focus.example", "Ada", "UTC", false) }.code)
        assertEquals("invalid_endpoint", assertFailsWith<SocialException> { repository.connect("http://focus.example", "Ada", "UTC", true) }.code)
        assertEquals("invalid_endpoint", assertFailsWith<SocialException> { repository.connect("https://user:password@focus.example", "Ada", "UTC", true) }.code)
        assertTrue(transport.requests.isEmpty())
        assertNull(repository.loadConnection())
    }

    @Test fun registrationPersistsTokenOnlyInSecretStoreAndRestoresWithoutNetwork() = runTest {
        val store = MemoryStringStore()
        val secrets = MemorySecretStore()
        val transport = FakeTransport().registered()
        val repository = HttpSocialRepository(store, secrets, transport)
        val connection = repository.connect("https://focus.example/", "Ada", "Europe/Warsaw", true)
        assertEquals("https://focus.example", connection.endpoint)
        assertEquals("Ada", connection.profile.nickname)
        assertEquals("secret-token", secrets.readSecret(HttpSocialRepository.TOKEN_KEY))
        assertFalse(store.read(HttpSocialRepository.CONNECTION_KEY)!!.contains("secret-token"))
        assertEquals("/v1/register", transport.requests[0].url.removePrefix("https://focus.example"))
        assertNull(transport.requests[0].token)
        assertEquals("secret-token", transport.requests[1].token)
        val nextTransport = FakeTransport()
        assertEquals(connection, HttpSocialRepository(store, secrets, nextTransport).loadConnection())
        assertTrue(nextTransport.requests.isEmpty())
    }

    @Test fun existingTokenIsVerifiedBeforeSavingAndAccountCanBeDisconnected() = runTest {
        val store = MemoryStringStore()
        val secrets = MemorySecretStore()
        val transport = FakeTransport().apply { replies += HttpResult(200, PROFILE) }
        val repository = HttpSocialRepository(store, secrets, transport)
        repository.connect("http://localhost:8787", "", "UTC", true, "existing-token")
        assertEquals(1, transport.requests.size)
        assertEquals("existing-token", transport.requests.single().token)
        repository.disconnect()
        assertNull(secrets.readSecret(HttpSocialRepository.TOKEN_KEY))
        assertNull(repository.loadConnection())
    }

    @Test fun completionUploadsOnlyIdAndMinutesAndMapsReturnedRoom() = runTest {
        val transport = FakeTransport().registered().apply { replies += HttpResult(200, "{\"accepted\":true,\"duplicate\":false,\"room\":$ROOM}") }
        val repository = HttpSocialRepository(MemoryStringStore(), MemorySecretStore(), transport)
        repository.connect("https://focus.example", "Ada", "UTC", true)
        val room = repository.submitCompletion("room-1", "session-1", 25)
        val sent = Json.parseToJsonElement(transport.requests.last().body!!).jsonObject
        assertEquals(setOf("session_id", "minutes"), sent.keys)
        assertEquals(25, sent["minutes"]!!.jsonPrimitive.int)
        assertEquals(500, room.goalMinutes)
        assertFalse(room.aggregate.visible)
        assertNull(room.aggregate.completedMinutes)
        assertEquals(RoomKind.ORGANIZATION, room.kind)
    }

    @Test fun serverErrorsAndInvalidIdentifiersNeverExposeCredentials() = runTest {
        val transport = FakeTransport().registered().apply { replies += HttpResult(429, "{\"error\":\"rate_limited\"}") }
        val repository = HttpSocialRepository(MemoryStringStore(), MemorySecretStore(), transport)
        repository.connect("https://focus.example", "Ada", "UTC", true)
        assertEquals("rate_limited", assertFailsWith<SocialException> { repository.sendNudge("room-1", "friend") }.code)
        assertEquals("invalid_identifier", assertFailsWith<SocialException> { repository.closeRoom("../another") }.code)
        assertEquals(3, transport.requests.size)
    }

    @Test fun exportUsesServerExportWithoutAddingLocalFocusHistoryOrToken() = runTest {
        val transport = FakeTransport().registered().apply { replies += HttpResult(200, "{\"profile\":$PROFILE,\"rooms\":[],\"weekly_totals\":[]}") }
        val repository = HttpSocialRepository(MemoryStringStore(), MemorySecretStore(), transport)
        repository.connect("https://focus.example", "Ada", "UTC", true)
        val exported = repository.exportAccount()
        assertFalse(exported.contains("secret-token"))
        assertFalse(exported.contains("startedAt"))
        assertEquals("https://focus.example/v1/me/export", transport.requests.last().url)
    }

    @Test fun dashboardReturnsOnlyActualServerRoomsAndNudges() = runTest {
        val transport = FakeTransport().registered().apply {
            replies += HttpResult(200, PROFILE)
            replies += HttpResult(200, "{\"rooms\":[$ROOM]}")
            replies += HttpResult(200, "{\"nudges\":[]}")
        }
        val repository = HttpSocialRepository(MemoryStringStore(), MemorySecretStore(), transport)
        repository.connect("https://focus.example", "Ada", "UTC", true)
        val dashboard = repository.dashboard()
        assertEquals("room-1", dashboard.rooms.single().id)
        assertTrue(dashboard.rooms.single().live.isEmpty())
        assertTrue(dashboard.rooms.single().ranking.isEmpty())
        assertTrue(dashboard.nudges.isEmpty())
    }

    private data class Request(val method: String, val url: String, val token: String?, val body: String?)
    private class FakeTransport : HttpTransport {
        val requests = mutableListOf<Request>()
        val replies = ArrayDeque<HttpResult>()
        override suspend fun request(method: String, url: String, token: String?, body: String?): HttpResult {
            requests += Request(method, url, token, body)
            return replies.removeFirst()
        }
        fun registered(): FakeTransport = apply {
            replies += HttpResult(200, "{\"user_id\":\"user-1\",\"token\":\"secret-token\",\"nickname\":\"Ada\"}")
            replies += HttpResult(200, PROFILE)
        }
    }
    companion object {
        private const val PROFILE = """{"user_id":"user-1","nickname":"Ada","timezone":"Europe/Warsaw"}"""
        private const val ROOM = """{"id":"room-1","name":"Study","kind":"organization","closed":false,"role":"owner","goal_minutes":500,"week":"2026-09-14","invitation_duration_minutes":25,"privacy_version":1,"privacy_text":"Weekly aggregate","member_count":2,"live_opt_in":false,"ranking_opt_in":false,"aggregate":{"visible":false,"completed_minutes":null,"completed_sessions":null},"ranking":[],"live":[],"invite_code":"invite-1"}"""
    }
}
