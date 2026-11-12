package pl.flipfocus.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import pl.flipfocus.domain.AnalyticsEvent
import pl.flipfocus.domain.AnalyticsRecorder

/** On-device aggregate counters only: no timestamps, identifiers, tasks or sensor samples. */
class LocalAnalyticsRecorder(private val store: StringStore) : AnalyticsRecorder {
    private val mutex = Mutex()

    override suspend fun record(event: AnalyticsEvent) = mutex.withLock {
        val counters = readUnlocked().toMutableMap()
        counters[event.name] = ((counters[event.name] ?: 0).coerceAtMost(Long.MAX_VALUE - 1) + 1)
        store.writeAtomically(KEY, storageJson.encodeToString(counters))
    }

    override suspend fun clear() = mutex.withLock { store.writeAtomically(KEY, "{}") }

    suspend fun counts(): Map<AnalyticsEvent, Long> = mutex.withLock {
        readUnlocked().mapNotNull { (key, value) ->
            AnalyticsEvent.entries.find { it.name == key }?.let { it to value }
        }.toMap()
    }

    private suspend fun readUnlocked(): Map<String, Long> {
        val raw = store.read(KEY) ?: return emptyMap()
        return try {
            storageJson.decodeFromString<Map<String, Long>>(raw).filterValues { it >= 0 }
        } catch (_: IllegalArgumentException) { emptyMap() }
    }

    companion object { const val KEY = "flipfocus.analytics.local" }
}
