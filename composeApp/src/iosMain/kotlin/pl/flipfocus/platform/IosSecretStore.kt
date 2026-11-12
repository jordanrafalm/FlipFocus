@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pl.flipfocus.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreFoundation.*
import platform.Security.*
import pl.flipfocus.data.SecretStore

/** Device-only Keychain items are never synced to iCloud or included in a transferable backup. */
class IosSecretStore : SecretStore {
    private val mutex = Mutex()

    override suspend fun readSecret(key: String): String? = withContext(Dispatchers.Default) {
        mutex.withLock {
            withQuery(key) { query ->
                CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
                CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    result.value = null
                    val status = SecItemCopyMatching(query, result.ptr)
                    if (status == errSecItemNotFound) return@memScoped null
                    check(status == errSecSuccess) { "Nie można odczytać danych logowania ($status)." }
                    val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
                    try {
                        CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).toInt())?.decodeToString()
                    } finally { CFRelease(data) }
                }
            }
        }
    }

    override suspend fun writeSecret(key: String, value: String?) = withContext(Dispatchers.Default) {
        mutex.withLock {
            withQuery(key) { query ->
                if (value == null) {
                    val status = SecItemDelete(query)
                    check(status == errSecSuccess || status == errSecItemNotFound) { "Nie można usunąć danych logowania ($status)." }
                } else {
                    val bytes = value.encodeToByteArray()
                    val data = (if (bytes.isEmpty()) CFDataCreate(null, null, 0)
                    else bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) })
                        ?: error("Nie można przygotować danych logowania.")
                    val attributes = dictionary()
                    try {
                        CFDictionarySetValue(attributes, kSecValueData, data)
                        CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                        var status = SecItemUpdate(query, attributes)
                        if (status == errSecItemNotFound) {
                            CFDictionarySetValue(query, kSecValueData, data)
                            CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                            status = SecItemAdd(query, null)
                        }
                        check(status == errSecSuccess) { "Nie można zapisać danych logowania ($status)." }
                    } finally {
                        CFRelease(attributes)
                        CFRelease(data)
                    }
                }
            }
        }
    }

    private fun dictionary(): CFMutableDictionaryRef = requireNotNull(CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ))

    private fun <T> withQuery(key: String, block: (CFMutableDictionaryRef) -> T): T {
        val query = dictionary()
        val account = requireNotNull(CFStringCreateWithCString(null, key, kCFStringEncodingUTF8))
        val service = requireNotNull(CFStringCreateWithCString(null, "pl.flipfocus.auth", kCFStringEncodingUTF8))
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(account)
            CFRelease(service)
            CFRelease(query)
        }
    }
}
