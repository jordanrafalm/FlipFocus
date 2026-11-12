@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pl.flipfocus.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import pl.flipfocus.data.StringStore

/** Each document is replaced atomically inside the application sandbox. */
class IosStringStore : StringStore {
    private val files = NSFileManager.defaultManager
    private val directory: String by lazy {
        val support = requireNotNull(files.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).firstOrNull())
            as platform.Foundation.NSURL
        val path = requireNotNull(support.path) + "/FlipFocus"
        check(files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)) {
            "Nie udało się przygotować lokalnego magazynu danych."
        }
        path
    }

    private fun path(key: String): String {
        require(key.matches(Regex("[a-zA-Z0-9._-]+")))
        return "$directory/$key.json"
    }

    override suspend fun read(key: String): String? = withContext(Dispatchers.Default) {
        val path = path(key)
        if (!files.fileExistsAtPath(path)) return@withContext null
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.toString()
            ?: error("Nie udało się odczytać danych na urządzeniu.")
    }

    override suspend fun writeAtomically(key: String, value: String) = withContext(Dispatchers.Default) {
        check(NSString.create(string = value).writeToFile(path(key), true, NSUTF8StringEncoding, null)) {
            "Nie udało się zapisać danych na urządzeniu."
        }
    }
}
