package pl.flipfocus.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import pl.flipfocus.domain.*

/** Optional, consent-gated network service. Credentials never enter focus snapshots or exports. */
class HttpSocialRepository(
    private val store: StringStore,
    private val secretStore: SecretStore,
    private val transport: HttpTransport = KtorHttpTransport(),
) : SocialRepository {
    private val connectionMutex = Mutex()
    private var connection: SocialConnection? = null
    private var token: String? = null

    override suspend fun loadConnection(): SocialConnection? = connectionMutex.withLock {
        val raw = store.read(CONNECTION_KEY) ?: return@withLock null
        try {
            val saved = storageJson.decodeFromString<ConnectionDto>(raw)
            if (!saved.privacyAccepted || saved.endpoint.isBlank()) return@withLock null
            val secret = secretStore.readSecret(TOKEN_KEY) ?: return@withLock null
            val endpoint = normalizedEndpoint(saved.endpoint)
            token = secret
            connection = SocialConnection(endpoint, saved.profile.toDomain())
            connection
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { throw SocialException("local_credentials_unavailable") }
    }

    override suspend fun connect(endpoint: String, nickname: String, timezone: String, privacyAccepted: Boolean, existingToken: String?): SocialConnection = connectionMutex.withLock {
        if (!privacyAccepted) throw SocialException("privacy_required")
        val address = try { normalizedEndpoint(endpoint) } catch (_: IllegalArgumentException) { throw SocialException("invalid_endpoint") }
        val name = nickname.trim()
        if (existingToken.isNullOrBlank() && name.length !in 1..40) throw SocialException("invalid_nickname")
        // Verify against the explicitly selected endpoint before replacing a previous account.
        val registration = if (existingToken.isNullOrBlank()) call("POST", "$address/v1/register", null,
            obj("nickname" to name, "timezone" to timezone, "privacy_version" to 1)) else null
        val newToken = existingToken?.trim()?.takeIf { it.isNotEmpty() } ?: registration!!.requiredString("token")
        val profile = decode<ProfileDto>(call("GET", "$address/v1/me", newToken)).toDomain()
        val saved = ConnectionDto(address, ProfileDto.from(profile), privacyAccepted = true)
        // Clear the metadata first: a failed write cannot bind a new token to an older endpoint.
        connection = null
        token = null
        store.writeAtomically(CONNECTION_KEY, storageJson.encodeToString(ConnectionDto()))
        secretStore.writeSecret(TOKEN_KEY, newToken)
        store.writeAtomically(CONNECTION_KEY, storageJson.encodeToString(saved))
        token = newToken
        SocialConnection(address, profile).also { connection = it }
    }

    override suspend fun searchCompanies(endpoint: String, query: String): List<Company> {
        val address = try { normalizedEndpoint(endpoint) } catch (_: IllegalArgumentException) { throw SocialException("invalid_endpoint") }
        return decode<List<CompanyDto>>(call("GET", "$address/v1/companies?q=${query.trim().encodeURLParameter()}", null)).map { it.toDomain() }
    }

    override suspend fun connectCompany(endpoint: String, companyId: String, nickname: String, jobTitle: String,
        timezone: String, privacyAccepted: Boolean, companyRankingOptIn: Boolean): SocialConnection = connectionMutex.withLock {
        if (!privacyAccepted) throw SocialException("privacy_required")
        val address = try { normalizedEndpoint(endpoint) } catch (_: IllegalArgumentException) { throw SocialException("invalid_endpoint") }
        val name = nickname.trim()
        val title = jobTitle.trim()
        if (name.length !in 1..40) throw SocialException("invalid_nickname")
        if (title.length > 80) throw SocialException("invalid_job_title")
        val registration = call("POST", "$address/v1/register", null,
            obj("nickname" to name, "job_title" to title, "company_id" to companyId.trim(),
                "timezone" to timezone, "privacy_version" to 1, "company_ranking_opt_in" to companyRankingOptIn))
        val newToken = registration.requiredString("token")
        val profile = decode<ProfileDto>(call("GET", "$address/v1/me", newToken)).toDomain()
        val saved = ConnectionDto(address, ProfileDto.from(profile), privacyAccepted = true)
        connection = null
        token = null
        store.writeAtomically(CONNECTION_KEY, storageJson.encodeToString(ConnectionDto()))
        secretStore.writeSecret(TOKEN_KEY, newToken)
        store.writeAtomically(CONNECTION_KEY, storageJson.encodeToString(saved))
        token = newToken
        SocialConnection(address, profile).also { connection = it }
    }

    override suspend fun dashboard(): SocialDashboard = coroutineScope {
        ensureConnection()
        val profile = async { decode<ProfileDto>(authorized("GET", "/v1/me")).toDomain() }
        val rooms = async { authorized("GET", "/v1/rooms").requiredArray("rooms").map { decode<RoomDto>(it).toDomain() } }
        val nudges = async { authorized("GET", "/v1/nudges").requiredArray("nudges").map { decode<NudgeDto>(it).toDomain() } }
        val loadedProfile = profile.await()
        val result = SocialDashboard(loadedProfile, rooms.await(), nudges.await())
        saveProfile(result.profile)
        result
    }

    override suspend fun updateProfile(profile: SocialProfile): SocialProfile {
        val result = decode<ProfileDto>(authorized("PATCH", "/v1/me/preferences", obj(
            "nickname" to profile.nickname, "timezone" to profile.timezone, "live_opt_in" to profile.liveOptIn,
            "ranking_opt_in" to profile.rankingOptIn, "nudge_opt_in" to profile.nudgeOptIn,
            "quiet_start_minute" to profile.quietStartMinute, "quiet_end_minute" to profile.quietEndMinute))).toDomain()
        saveProfile(result)
        return result
    }

    override suspend fun createRoom(name: String, kind: RoomKind, goalMinutes: Int, invitationDurationMinutes: Int, privacyAccepted: Boolean): SocialRoom {
        if (!privacyAccepted) throw SocialException("privacy_required")
        return room(authorized("POST", "/v1/rooms", obj("name" to name, "kind" to kind.name.lowercase(),
            "goal_minutes" to goalMinutes, "invitation_duration_minutes" to invitationDurationMinutes, "privacy_version" to 1)))
    }

    override suspend fun previewInvitation(code: String): SocialInvitation {
        val value = decode<InvitationDto>(authorized("GET", "/v1/rooms/invite/${path(code)}"))
        return SocialInvitation(code, value.name, kind(value.kind), value.privacy_version, value.privacy_text, value.invitation_duration_minutes)
    }
    override suspend fun joinRoom(code: String, privacyVersion: Int): SocialRoom = room(authorized("POST", "/v1/rooms/join", obj("code" to code, "privacy_version" to privacyVersion)))
    override suspend fun updateRoomPreferences(roomId: String, liveOptIn: Boolean, rankingOptIn: Boolean): SocialRoom =
        room(authorized("PATCH", "/v1/rooms/${path(roomId)}/preferences", obj("live_opt_in" to liveOptIn, "ranking_opt_in" to rankingOptIn)))
    override suspend fun leaveRoom(roomId: String) { authorized("POST", "/v1/rooms/${path(roomId)}/leave", obj()) }
    override suspend fun closeRoom(roomId: String): SocialRoom = room(authorized("POST", "/v1/rooms/${path(roomId)}/close", obj()))
    override suspend fun heartbeat(roomId: String, focusing: Boolean) { authorized("POST", "/v1/rooms/${path(roomId)}/heartbeat", obj("focusing" to focusing)) }
    override suspend fun submitCompletion(roomId: String, sessionId: String, minutes: Int): SocialRoom =
        room(authorized("POST", "/v1/rooms/${path(roomId)}/completions", obj("session_id" to sessionId, "minutes" to minutes))["room"] ?: throw SocialException("invalid_response"))
    override suspend fun sendNudge(roomId: String, recipientId: String) { authorized("POST", "/v1/rooms/${path(roomId)}/nudges", obj("recipient_id" to recipientId)) }
    override suspend fun declineNudge(nudgeId: String) { authorized("POST", "/v1/nudges/${path(nudgeId)}/decline", obj()) }
    override suspend fun unblockNudges(senderId: String) { authorized("DELETE", "/v1/nudge-blocks/${path(senderId)}") }
    override suspend fun reportRoom(roomId: String, reason: SocialReportReason) { authorized("POST", "/v1/rooms/${path(roomId)}/report", obj("reason" to reason.name.lowercase())) }
    override suspend fun exportAccount(): String = storageJson.encodeToString(authorized("GET", "/v1/me/export"))
    override suspend fun deleteAccount() { authorized("DELETE", "/v1/me"); disconnect() }
    override suspend fun disconnect() = connectionMutex.withLock {
        // Remove endpoint/profile even when deleting the native credential subsequently fails.
        store.writeAtomically(CONNECTION_KEY, storageJson.encodeToString(ConnectionDto()))
        connection = null
        token = null
        secretStore.writeSecret(TOKEN_KEY, null)
    }
    override fun close() { transport.close() }

    private suspend fun ensureConnection(): SocialConnection = connection ?: loadConnection() ?: throw SocialException("not_connected")
    private suspend fun authorized(method: String, path: String, body: JsonObject? = null): JsonObject {
        val current = ensureConnection()
        return call(method, current.endpoint + path, token ?: throw SocialException("not_connected"), body)
    }
    private suspend fun call(method: String, url: String, token: String?, body: JsonObject? = null): JsonObject {
        val response = try { transport.request(method, url, token, body?.toString()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw SocialException("network_unavailable") }
        val parsed = try { storageJson.parseToJsonElement(response.body).jsonObject }
        catch (_: Exception) { throw SocialException(if (response.status >= 500) "server_unavailable" else "invalid_response") }
        if (response.status !in 200..299) throw SocialException(parsed["error"]?.jsonPrimitive?.content ?: "request_failed")
        return parsed
    }
    private suspend fun saveProfile(profile: SocialProfile) {
        val current = ensureConnection().copy(profile = profile)
        store.writeAtomically(CONNECTION_KEY, storageJson.encodeToString(ConnectionDto(current.endpoint, ProfileDto.from(profile), true)))
        connection = current
    }
    private inline fun <reified T> decode(element: JsonElement): T = try { storageJson.decodeFromJsonElement<T>(element) }
    catch (_: Exception) { throw SocialException("invalid_response") }
    private fun room(element: JsonElement): SocialRoom = decode<RoomDto>(element).toDomain()
    private fun path(value: String): String {
        if (value.isEmpty() || !value.all { it.isLetterOrDigit() || it in "-_" }) throw SocialException("invalid_identifier")
        return value
    }

    companion object {
        const val CONNECTION_KEY = "flipfocus.social.connection"
        const val TOKEN_KEY = "flipfocus.social.token"
    }
}

private fun obj(vararg values: Pair<String, Any>): JsonObject = buildJsonObject {
    values.forEach { (key, value) -> when (value) {
        is Boolean -> put(key, value)
        is Number -> put(key, value)
        else -> put(key, value.toString())
    } }
}
private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive?.content ?: throw SocialException("invalid_response")
private fun JsonObject.requiredArray(key: String): JsonArray = this[key]?.jsonArray ?: throw SocialException("invalid_response")
private fun kind(value: String): RoomKind = when (value) { "friends" -> RoomKind.FRIENDS; "organization" -> RoomKind.ORGANIZATION; else -> throw SocialException("invalid_response") }

@Serializable private data class ConnectionDto(val endpoint: String = "", val profile: ProfileDto = ProfileDto(), val privacyAccepted: Boolean = false)
@Serializable private data class ProfileDto(
    val user_id: String = "", val nickname: String = "", val timezone: String = "UTC",
    val live_opt_in: Boolean = false, val ranking_opt_in: Boolean = false, val nudge_opt_in: Boolean = false,
    val quiet_start_minute: Int = 1320, val quiet_end_minute: Int = 480,
    val company_id: String? = null, val company_name: String? = null, val job_title: String? = null,
    val company_ranking_opt_in: Boolean = false,
) {
    fun toDomain(): SocialProfile {
        if (user_id.isBlank() || nickname.isBlank() || quiet_start_minute !in 0..1439 || quiet_end_minute !in 0..1439) throw SocialException("invalid_response")
        return SocialProfile(user_id, nickname, timezone, live_opt_in, ranking_opt_in, nudge_opt_in, quiet_start_minute, quiet_end_minute,
            company_id, company_name, job_title, company_ranking_opt_in)
    }
    companion object { fun from(v: SocialProfile) = ProfileDto(v.userId, v.nickname, v.timezone, v.liveOptIn, v.rankingOptIn, v.nudgeOptIn,
        v.quietStartMinute, v.quietEndMinute, v.companyId, v.companyName, v.jobTitle, v.companyRankingOptIn) }
}
@Serializable private data class CompanyDto(val id: String, val name: String) { fun toDomain() = Company(id, name) }
@Serializable private data class AggregateDto(val visible: Boolean, val completed_minutes: Int? = null, val completed_sessions: Int? = null)
@Serializable private data class RankingDto(val user_id: String, val nickname: String, val completed_minutes: Int)
@Serializable private data class LiveDto(val user_id: String, val nickname: String)
@Serializable private data class RoomDto(
    val id: String, val name: String, val kind: String, val closed: Boolean, val role: String,
    val goal_minutes: Int, val week: String, val invitation_duration_minutes: Int,
    val privacy_version: Int, val privacy_text: String, val member_count: Int,
    val live_opt_in: Boolean, val ranking_opt_in: Boolean, val aggregate: AggregateDto,
    val ranking: List<RankingDto> = emptyList(), val live: List<LiveDto> = emptyList(), val invite_code: String? = null,
) {
    fun toDomain() = SocialRoom(id, name, kind(kind), closed, if (role == "owner") RoomRole.OWNER else RoomRole.PARTICIPANT,
        goal_minutes, week, invitation_duration_minutes, privacy_version, privacy_text, member_count, live_opt_in,
        ranking_opt_in, OrganizationAggregate(aggregate.visible, aggregate.completed_minutes, aggregate.completed_sessions),
        ranking.map { SocialRankingEntry(it.user_id, it.nickname, it.completed_minutes) }, live.map { SocialLiveMember(it.user_id, it.nickname) }, invite_code)
}
@Serializable private data class InvitationDto(val name: String, val kind: String, val privacy_version: Int, val privacy_text: String, val invitation_duration_minutes: Int)
@Serializable private data class NudgeDto(val id: String, val room_id: String, val sender_id: String, val sender_nickname: String) {
    fun toDomain() = SocialNudge(id, room_id, sender_id, sender_nickname)
}
