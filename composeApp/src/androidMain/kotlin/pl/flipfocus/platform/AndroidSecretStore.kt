package pl.flipfocus.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.flipfocus.data.SecretStore

/** Tokens are authenticated ciphertext; the AES key never leaves Android Keystore. */
class AndroidSecretStore(context: Context) : SecretStore {
    private val preferences = context.applicationContext.getSharedPreferences("flipfocus_credentials", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun readSecret(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val encrypted = preferences.getString(key, null) ?: return@withLock null
            val parts = encrypted.split(':')
            check(parts.size == 3 && parts[0] == "1") { "Nie można odczytać danych logowania." }
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            check(iv.size == 12) { "Nie można odczytać danych logowania." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(key.toByteArray(StandardCharsets.UTF_8))
            String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), StandardCharsets.UTF_8)
        }
    }

    override suspend fun writeSecret(key: String, value: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val editor = preferences.edit()
            if (value == null) editor.remove(key) else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                cipher.updateAAD(key.toByteArray(StandardCharsets.UTF_8))
                val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
                editor.putString(key, "1:$iv:$encrypted")
            }
            check(editor.commit()) { "Nie udało się zapisać danych logowania." }
        }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object { const val KEY_ALIAS = "pl.flipfocus.credentials.v1" }
}
