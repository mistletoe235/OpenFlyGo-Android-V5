package edu.playground.djivln.reconstruction

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the bearer access code encrypted by a non-exportable Android Keystore key. */
class V86SecureTokenStore(context: Context, namespace: String = "v86_secure") {
    private val preferences = context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)

    fun save(token: String) {
        if (token.isBlank()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? = runCatching {
        val iv = Base64.decode(preferences.getString(KEY_IV, null) ?: return null, Base64.NO_WRAP)
        val encrypted = Base64.decode(preferences.getString(KEY_VALUE, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()?.takeIf(String::isNotBlank)

    fun clear() {
        preferences.edit().remove(KEY_IV).remove(KEY_VALUE).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "v86_secure"
        const val KEY_IV = "access_code_iv"
        const val KEY_VALUE = "access_code_ciphertext"
        const val ALIAS = "openfly_v86_access_code"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
