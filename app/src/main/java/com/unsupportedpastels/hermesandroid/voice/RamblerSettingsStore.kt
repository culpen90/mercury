package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.unsupportedpastels.mercury.core.voice.RamblerProtocol
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Personal credentials for Google's official API, independent of Mercury and Hermes accounts. */
interface RamblerSettingsStore {
    suspend fun loadApiKey(): String?
    /** Old automatically provisioned credentials require an explicit personal-key replacement. */
    suspend fun requiresPersonalApiKey(): Boolean = false
    suspend fun saveApiKey(apiKey: String)
    suspend fun clearApiKey()
}

class EncryptedRamblerSettingsStore(
    context: Context,
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    aeadFactory: () -> Aead = { createAead(context.applicationContext, preferencesName) },
) : RamblerSettingsStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val aead by lazy(LazyThreadSafetyMode.SYNCHRONIZED, aeadFactory)
    private val mutationMutex = Mutex()

    override suspend fun loadApiKey(): String? = withContext(ioDispatcher) {
        val records = preferences.all
        // Removing Google OAuth must not silently discard the billing checks
        // promised for old automatically provisioned keys. Preserve their
        // ciphertext, but require the user to enter a personal key before use.
        // Even malformed legacy metadata remains a migration marker.
        if (records.containsKey(CONNECTION_PREFERENCE)) null
        else decryptKey(records[KEY_PREFERENCE] as? String)
    }

    override suspend fun requiresPersonalApiKey(): Boolean = withContext(ioDispatcher) {
        preferences.contains(CONNECTION_PREFERENCE)
    }

    @Suppress("UseKtx") // Report a failed credential write instead of silently accepting it.
    override suspend fun saveApiKey(apiKey: String) = withContext(ioDispatcher) {
        mutationMutex.withLock {
            // Encrypt completely before replacing the credential or clearing
            // legacy metadata; failed encryption leaves the old record intact.
            val ciphertext = encryptedKey(apiKey)
            check(
                preferences.edit()
                    .putString(KEY_PREFERENCE, ciphertext)
                    .remove(CONNECTION_PREFERENCE)
                    .remove(PENDING_PREFERENCE)
                    .commit(),
            ) { "Could not persist Gemini API key" }
        }
    }

    @Suppress("UseKtx") // Report a failed removal instead of claiming the key was removed.
    override suspend fun clearApiKey() = withContext(ioDispatcher) {
        mutationMutex.withLock {
            check(preferences.edit()
                .remove(KEY_PREFERENCE)
                .remove(CONNECTION_PREFERENCE)
                .remove(PENDING_PREFERENCE)
                .commit()) { "Could not clear Gemini API key" }
        }
    }

    private fun encryptedKey(apiKey: String): String {
        val normalized = requireNotNull(RamblerProtocol.normalizeApiKey(apiKey)) { "Invalid Gemini API key" }
        val ciphertext = aead.encrypt(normalized.toByteArray(StandardCharsets.UTF_8), associatedData())
        require(ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "Encrypted Gemini API key is too large" }
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decryptKey(encoded: String?): String? {
        if (encoded == null || encoded.length > MAX_ENCODED_CHARS) return null
        return runCatching {
            val ciphertext = Base64.decode(encoded, Base64.DEFAULT)
            if (ciphertext.size > MAX_CIPHERTEXT_BYTES) return@runCatching null
            val plaintext = aead.decrypt(ciphertext, associatedData())
            if (plaintext.size > RamblerProtocol.MAX_API_KEY_CHARS) return@runCatching null
            RamblerProtocol.normalizeApiKey(plaintext.toString(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun associatedData(): ByteArray = GOOGLE_API_ORIGIN.toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "rambler_settings"
        const val GOOGLE_API_ORIGIN = "https://generativelanguage.googleapis.com"
        const val KEY_PREFERENCE = "google_api_key"
        const val CONNECTION_PREFERENCE = "google_managed_connection"
        const val PENDING_PREFERENCE = "google_pending_projects"
        const val MASTER_KEY_URI = "android-keystore://rambler_settings_master"
        const val MAX_CIPHERTEXT_BYTES = RamblerProtocol.MAX_API_KEY_CHARS + 1024
        const val MAX_ENCODED_CHARS = MAX_CIPHERTEXT_BYTES * 2

        fun createAead(context: Context, preferencesName: String): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, "rambler_api_key:$preferencesName", "$preferencesName.keyset")
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        }
    }
}
