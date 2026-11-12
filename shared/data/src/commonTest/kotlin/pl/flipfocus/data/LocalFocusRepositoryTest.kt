package pl.flipfocus.data

import kotlinx.coroutines.test.runTest
import pl.flipfocus.domain.*
import kotlin.test.*

class LocalFocusRepositoryTest {
    @Test fun settingsAndActiveSessionSurviveRestart() = runTest {
        val store = MemoryStringStore()
        val session = FocusEngine.phoneDown(FocusEngine.ready("active", FocusDuration.ofMinutes(25)), TimeReading(100_000, 1_000))
        val original = FocusSnapshot(settings = FocusSettings(soundEnabled = true, graceSeconds = 15),
            activeSession = session, selectedDurationMinutes = 45)
        LocalFocusRepository(store).save(original)
        val restored = LocalFocusRepository(store).load()
        assertEquals(original.copy(schemaVersion = 2), restored)
    }

    @Test fun terminalSessionAndHistoryCommitTogetherAndDeduplicate() = runTest {
        val store = MemoryStringStore()
        val repository = LocalFocusRepository(store)
        val completed = completedSession("once")
        val snapshot = FocusSnapshot(activeSession = completed)
        repository.save(snapshot)
        repository.save(snapshot)
        val loaded = LocalFocusRepository(store).load()
        assertEquals(completed, loaded.activeSession)
        assertEquals(listOf("once"), loaded.history.map { it.id })
    }

    @Test fun corruptionRestoresThePreviousGoodGeneration() = runTest {
        val store = MemoryStringStore()
        val repository = LocalFocusRepository(store)
        repository.save(FocusSnapshot(selectedDurationMinutes = 15))
        repository.save(FocusSnapshot(selectedDurationMinutes = 45))
        store.writeAtomically(LocalFocusRepository.PRIMARY_KEY, "{incomplete")
        assertEquals(15, LocalFocusRepository(store).load().selectedDurationMinutes)
    }

    @Test fun failedWritePreservesThePreviousGoodGeneration() = runTest {
        val store = FailingStore()
        val repository = LocalFocusRepository(store)
        repository.save(FocusSnapshot(selectedDurationMinutes = 15))
        store.failKey = LocalFocusRepository.PRIMARY_KEY
        assertFailsWith<IllegalStateException> { repository.save(FocusSnapshot(selectedDurationMinutes = 45)) }
        assertEquals(15, repository.load().selectedDurationMinutes)
    }

    @Test fun migrationKeepsHistoryAndSettings() = runTest {
        val store = MemoryStringStore()
        store.writeAtomically(LocalFocusRepository.PRIMARY_KEY, """
            {"schemaVersion":1,"settings":{"soundEnabled":true},"selectedDurationMinutes":45,
             "history":[{"id":"legacy","durationMinutes":15,"focusedMillis":900000,
             "startedAtEpochMillis":1000,"endedAtEpochMillis":901000,"outcome":"COMPLETED","endReason":"TARGET_REACHED"}]}
        """.trimIndent())
        val repository = LocalFocusRepository(store)
        val migrated = repository.load()
        assertTrue(migrated.settings.soundEnabled)
        assertEquals("legacy", migrated.history.single().id)
        assertEquals(2, migrated.schemaVersion)
        repository.save(migrated)
        assertEquals(migrated, LocalFocusRepository(store).load())
        assertTrue(store.read(LocalFocusRepository.PRIMARY_KEY)!!.contains("checksum"))
    }

    @Test fun deletingHistoryPreventsResurrectionFromAnOldBackup() = runTest {
        val store = MemoryStringStore()
        val repository = LocalFocusRepository(store)
        repository.save(FocusSnapshot(activeSession = completedSession("deleted")))
        val old = store.read(LocalFocusRepository.PRIMARY_KEY)!!
        repository.clearHistory()
        store.writeAtomically(LocalFocusRepository.PRIMARY_KEY, "broken")
        store.writeAtomically(LocalFocusRepository.BACKUP_KEY, old)
        val loaded = LocalFocusRepository(store).load()
        assertTrue(loaded.history.isEmpty())
        assertNull(loaded.activeSession)
    }

    @Test fun interruptedDeletionStillHidesDeletedHistory() = runTest {
        val store = FailingStore()
        val repository = LocalFocusRepository(store)
        repository.save(FocusSnapshot(activeSession = completedSession("deleted")))
        store.failKey = LocalFocusRepository.PRIMARY_KEY
        assertFailsWith<IllegalStateException> { repository.clearHistory() }
        assertTrue(LocalFocusRepository(store).load().history.isEmpty())
    }

    @Test fun fullyCorruptOrFutureDataIsNotSilentlyOverwritten() = runTest {
        val store = MemoryStringStore()
        store.writeAtomically(LocalFocusRepository.PRIMARY_KEY, "{}")
        assertFailsWith<FocusStorageException> { LocalFocusRepository(store).load() }
        store.writeAtomically(LocalFocusRepository.PRIMARY_KEY, "{\"version\":99}")
        assertFailsWith<FocusStorageException> { LocalFocusRepository(store).save(FocusSnapshot()) }
        assertEquals("{\"version\":99}", store.read(LocalFocusRepository.PRIMARY_KEY))
    }

    @Test fun analyticsAreOnlyLocalAggregateCountersAndCanBeErased() = runTest {
        val store = MemoryStringStore()
        val recorder = LocalAnalyticsRecorder(store)
        recorder.record(AnalyticsEvent.SESSION_STARTED)
        recorder.record(AnalyticsEvent.SESSION_STARTED)
        assertEquals(mapOf(AnalyticsEvent.SESSION_STARTED to 2L), recorder.counts())
        assertEquals("{\"SESSION_STARTED\":2}", store.read(LocalAnalyticsRecorder.KEY))
        recorder.clear()
        assertTrue(recorder.counts().isEmpty())
    }

    private fun completedSession(id: String): FocusSession = FocusSession(id, FocusDuration.ofMinutes(15),
        state = FocusState.COMPLETED, startedAtEpochMillis = 1_000, focusedMillis = 900_000,
        endedAtEpochMillis = 901_000, endReason = FocusEndReason.TARGET_REACHED)

    private class FailingStore : StringStore {
        private val delegate = MemoryStringStore()
        var failKey: String? = null
        override suspend fun read(key: String): String? = delegate.read(key)
        override suspend fun writeAtomically(key: String, value: String) {
            check(key != failKey) { "Disk full" }
            delegate.writeAtomically(key, value)
        }
    }
}
