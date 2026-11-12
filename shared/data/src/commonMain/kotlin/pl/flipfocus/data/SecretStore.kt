package pl.flipfocus.data

/** Authentication tokens are separate from history, exports and ordinary preferences. */
interface SecretStore {
    suspend fun readSecret(key: String): String?
    /** A null value permanently removes the stored secret. */
    suspend fun writeSecret(key: String, value: String?)
}

/** In-memory credentials for tests and nonpersistent development sessions. */
class MemorySecretStore : SecretStore {
    private val secrets = mutableMapOf<String, String>()
    override suspend fun readSecret(key: String): String? = secrets[key]
    override suspend fun writeSecret(key: String, value: String?) {
        if (value == null) secrets.remove(key) else secrets[key] = value
    }
}
