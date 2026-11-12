package pl.flipfocus.platform

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.flipfocus.data.StringStore

/** SharedPreferences commit is an atomic disk replacement and reports failed writes. */
class AndroidStringStore(context: Context) : StringStore {
    private val preferences = context.applicationContext.getSharedPreferences("flipfocus_data", Context.MODE_PRIVATE)

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        preferences.getString(key, null)
    }

    override suspend fun writeAtomically(key: String, value: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(key, value).commit()) { "Nie udało się zapisać danych na urządzeniu." }
    }
}
