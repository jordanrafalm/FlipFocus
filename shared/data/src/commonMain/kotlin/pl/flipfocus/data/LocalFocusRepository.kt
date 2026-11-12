package pl.flipfocus.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import pl.flipfocus.domain.FocusRepository
import pl.flipfocus.domain.FocusSnapshot

class FocusStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Stores an entire transaction (active session, settings and history) in one atomic value.
 * A separately checksummed previous generation survives a damaged primary snapshot.
 * Deletion tombstones are applied to both generations, including after interrupted writes.
 */
class LocalFocusRepository(private val store: StringStore) : FocusRepository {
    private val mutex = Mutex()

    override suspend fun load(): FocusSnapshot = mutex.withLock { readUnlocked().snapshot }

    override suspend fun save(snapshot: FocusSnapshot) = mutex.withLock {
        val current = readUnlocked()
        val tombstones = readTombstones()
        val normalized = snapshot.activeSession?.let(snapshot::withSession) ?: snapshot
        val sanitized = sanitize(normalized, tombstones)
        val encoded = SnapshotCodec.encode(sanitized, current.revision + 1)
        // Never put an invalid primary into the backup slot.
        store.writeAtomically(BACKUP_KEY, SnapshotCodec.encode(current.snapshot, current.revision))
        store.writeAtomically(PRIMARY_KEY, encoded)
    }

    override suspend fun clearHistory(): FocusSnapshot = mutex.withLock {
        val current = readUnlocked()
        val removedIds = readTombstones() + current.snapshot.history.map { it.id } +
            listOfNotNull(current.snapshot.activeSession?.takeIf { it.isTerminal }?.id)
        // Commit the deletion barrier first: an older backup can never resurrect these IDs.
        store.writeAtomically(DELETIONS_KEY, storageJson.encodeToString(DeletionRecord(ids = removedIds.toList())))
        val sanitized = sanitize(current.snapshot.copy(history = emptyList()), removedIds)
        val encoded = SnapshotCodec.encode(sanitized, current.revision + 1)
        store.writeAtomically(PRIMARY_KEY, encoded)
        store.writeAtomically(BACKUP_KEY, encoded)
        sanitized
    }

    private suspend fun readUnlocked(): DecodedSnapshot {
        val primaryRaw = store.read(PRIMARY_KEY)
        val backupRaw = store.read(BACKUP_KEY)
        val tombstones = readTombstones()
        if (primaryRaw == null && backupRaw == null) return DecodedSnapshot(FocusSnapshot(), 0)
        val snapshots = listOfNotNull(primaryRaw, backupRaw).mapNotNull { raw ->
            try {
                SnapshotCodec.decode(raw)
            } catch (unsupported: UnsupportedSnapshotVersion) {
                // Do not overwrite data written by a newer app version.
                throw FocusStorageException("Dane pochodzą z nowszej wersji aplikacji.", unsupported)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }
        val newest = snapshots.maxByOrNull { it.revision }
            ?: throw FocusStorageException("Nie można odczytać zapisanych danych. Zachowano oryginalne pliki.")
        return newest.copy(snapshot = sanitize(newest.snapshot, tombstones))
    }

    private suspend fun readTombstones(): Set<String> {
        val raw = store.read(DELETIONS_KEY) ?: return emptySet()
        return try {
            val record = storageJson.decodeFromString<DeletionRecord>(raw)
            require(record.version == 1)
            record.ids.toSet()
        } catch (error: IllegalArgumentException) {
            // A damaged privacy barrier must not reveal deleted history from an older snapshot.
            throw FocusStorageException("Nie można zweryfikować usunięcia historii. Dane pozostają ukryte.", error)
        }
    }

    private fun sanitize(snapshot: FocusSnapshot, removedIds: Set<String>): FocusSnapshot = snapshot.copy(
        history = snapshot.history.filterNot { it.id in removedIds }.distinctBy { it.id },
        activeSession = snapshot.activeSession?.takeUnless { it.isTerminal && it.id in removedIds },
    )

    companion object {
        const val PRIMARY_KEY = "flipfocus.snapshot"
        const val BACKUP_KEY = "flipfocus.snapshot.backup"
        const val DELETIONS_KEY = "flipfocus.history.deletions"
    }
}

@Serializable
private data class DeletionRecord(val version: Int = 1, val ids: List<String>)
