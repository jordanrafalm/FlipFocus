package pl.flipfocus.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import pl.flipfocus.domain.*

internal val storageJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal class UnsupportedSnapshotVersion(version: Int) : IllegalStateException("Unsupported storage version: $version")

@Serializable
private data class Envelope(val version: Int = 2, val revision: Long, val payload: String, val checksum: String)

internal data class DecodedSnapshot(val snapshot: FocusSnapshot, val revision: Long)

internal object SnapshotCodec {
    fun encode(snapshot: FocusSnapshot, revision: Long): String {
        val payload = storageJson.encodeToString(SnapshotDto.from(snapshot))
        return storageJson.encodeToString(Envelope(revision = revision, payload = payload, checksum = checksum(payload)))
    }

    fun decode(raw: String): DecodedSnapshot {
        val root = storageJson.parseToJsonElement(raw).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull
        if (version != null) {
            if (version > 2) throw UnsupportedSnapshotVersion(version)
            require(version == 2) { "Invalid envelope version" }
            val envelope = storageJson.decodeFromString<Envelope>(raw)
            require(envelope.revision >= 0 && checksum(envelope.payload) == envelope.checksum) { "Snapshot checksum mismatch" }
            return DecodedSnapshot(storageJson.decodeFromString<SnapshotDto>(envelope.payload).toDomain(), envelope.revision)
        }
        // Version 1 stored a plain JSON snapshot. Preserve its data on the next v2 write.
        val legacyVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        if (legacyVersion > 2) throw UnsupportedSnapshotVersion(legacyVersion)
        require(root.containsKey("settings") && root.containsKey("history")) { "Incomplete legacy snapshot" }
        return DecodedSnapshot(storageJson.decodeFromString<SnapshotDto>(raw).toDomain(), 0)
    }

    private fun checksum(value: String): String {
        var hash = 2166136261u
        for (byte in value.encodeToByteArray()) hash = (hash xor byte.toUByte().toUInt()) * 16777619u
        return hash.toString(16)
    }
}

@Serializable
private data class SnapshotDto(
    val schemaVersion: Int = 1,
    val settings: SettingsDto = SettingsDto(),
    val activeSession: SessionDto? = null,
    val history: List<HistoryDto> = emptyList(),
    val selectedDurationMinutes: Int = 25,
) {
    fun toDomain(): FocusSnapshot {
        require(schemaVersion in 1..2) { "Invalid snapshot schema" }
        FocusDuration.ofMinutes(selectedDurationMinutes)
        return FocusSnapshot(schemaVersion = 2, settings = settings.toDomain(), activeSession = activeSession?.toDomain(),
            history = history.map { it.toDomain() }.distinctBy { it.id }, selectedDurationMinutes = selectedDurationMinutes)
    }
    companion object {
        fun from(value: FocusSnapshot) = SnapshotDto(2, SettingsDto.from(value.settings), value.activeSession?.let(SessionDto::from),
            value.history.distinctBy { it.id }.map(HistoryDto::from), value.selectedDurationMinutes)
    }
}

@Serializable
private data class SettingsDto(
    val graceSeconds: Int = 10, val minimumStreakMinutes: Int = 15,
    val soundEnabled: Boolean = true, val hapticsEnabled: Boolean = true, val silentModeEnabled: Boolean = false,
    val remindersEnabled: Boolean = false, val reminderHour: Int = 11, val analyticsEnabled: Boolean = false,
    val liveStatusEnabled: Boolean = false, val leaderboardEnabled: Boolean = false,
    val language: String = "SYSTEM",
) {
    fun toDomain() = FocusSettings(
        graceSeconds = graceSeconds, minimumStreakMinutes = minimumStreakMinutes, soundEnabled = soundEnabled,
        hapticsEnabled = hapticsEnabled, silentModeEnabled = silentModeEnabled, remindersEnabled = remindersEnabled, reminderHour = reminderHour,
        analyticsEnabled = analyticsEnabled, liveStatusEnabled = liveStatusEnabled,
        leaderboardEnabled = leaderboardEnabled, language = AppLanguage.valueOf(language),
    )
    companion object {
        fun from(value: FocusSettings) = SettingsDto(value.graceSeconds, value.minimumStreakMinutes, value.soundEnabled,
            value.hapticsEnabled, value.silentModeEnabled, value.remindersEnabled, value.reminderHour, value.analyticsEnabled, value.liveStatusEnabled,
            value.leaderboardEnabled, value.language.name)
    }
}

@Serializable
private data class SessionDto(
    val id: String, val durationMinutes: Int, val state: String,
    val startedAtEpochMillis: Long? = null, val focusedMillis: Long = 0,
    val lastTickMonotonicMillis: Long? = null, val lastObservedAtEpochMillis: Long? = null,
    val graceDeadlineMonotonicMillis: Long? = null, val graceDeadlineEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null, val endReason: String? = null, val gracePeriodMillis: Long = 10_000,
) {
    fun toDomain(): FocusSession {
        val duration = FocusDuration.ofMinutes(durationMinutes)
        require(id.isNotBlank() && focusedMillis in 0..duration.millis && gracePeriodMillis > 0) { "Invalid persisted session" }
        return FocusSession(id, duration, FocusState.valueOf(state), startedAtEpochMillis, focusedMillis,
            lastTickMonotonicMillis, lastObservedAtEpochMillis, graceDeadlineMonotonicMillis,
            graceDeadlineEpochMillis, endedAtEpochMillis, endReason?.let(FocusEndReason::valueOf), gracePeriodMillis)
    }
    companion object {
        fun from(value: FocusSession) = SessionDto(value.id, value.duration.minutes, value.state.name,
            value.startedAtEpochMillis, value.focusedMillis, value.lastTickMonotonicMillis,
            value.lastObservedAtEpochMillis, value.graceDeadlineMonotonicMillis, value.graceDeadlineEpochMillis,
            value.endedAtEpochMillis, value.endReason?.name, value.gracePeriodMillis)
    }
}

@Serializable
private data class HistoryDto(
    val id: String, val durationMinutes: Int, val focusedMillis: Long,
    val startedAtEpochMillis: Long, val endedAtEpochMillis: Long, val outcome: String, val endReason: String,
) {
    fun toDomain(): FocusHistoryEntry {
        val duration = FocusDuration.ofMinutes(durationMinutes)
        val state = FocusState.valueOf(outcome)
        require(id.isNotBlank() && state in setOf(FocusState.COMPLETED, FocusState.ABANDONED)) { "Invalid history outcome" }
        require(focusedMillis in 0..duration.millis && endedAtEpochMillis >= startedAtEpochMillis) { "Invalid history timing" }
        return FocusHistoryEntry(id, durationMinutes, focusedMillis, startedAtEpochMillis, endedAtEpochMillis,
            state, FocusEndReason.valueOf(endReason))
    }
    companion object {
        fun from(value: FocusHistoryEntry) = HistoryDto(value.id, value.durationMinutes, value.focusedMillis,
            value.startedAtEpochMillis, value.endedAtEpochMillis, value.outcome.name, value.endReason.name)
    }
}
