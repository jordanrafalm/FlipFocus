package pl.flipfocus.data

/** Platform storage must replace each key atomically and throw if the write fails. */
interface StringStore {
    suspend fun read(key: String): String?
    suspend fun writeAtomically(key: String, value: String)
}

/** Useful for previews, browser sessions and deterministic tests. */
class MemoryStringStore : StringStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun read(key: String): String? = values[key]
    override suspend fun writeAtomically(key: String, value: String) { values[key] = value }
}
