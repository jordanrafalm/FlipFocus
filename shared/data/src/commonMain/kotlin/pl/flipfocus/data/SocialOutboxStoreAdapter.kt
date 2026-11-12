package pl.flipfocus.data

import pl.flipfocus.domain.SocialOutboxStore

/** Persists the pending company-completion outbox using the same atomic string storage as the local focus history. */
class StringStoreSocialOutboxStore(
    private val store: StringStore,
    private val key: String = "social_company_outbox",
) : SocialOutboxStore {
    override suspend fun read(): String? = store.read(key)
    override suspend fun write(value: String) = store.writeAtomically(key, value)
}
