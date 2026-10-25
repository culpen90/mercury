package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.unsupportedpastels.mercury.core.voice.RamblerProtocol
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerConnection
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerFailure
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerProtocol
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One credential read; its secret must never appear in diagnostic output. */
class RamblerCredentialSnapshot(val apiKey: String?, val connection: GoogleRamblerConnection?) {
    override fun toString(): String = "RamblerCredentialSnapshot(redacted)"
}

/** Credentials for Google's official API, independent of every Hermes connection. */
interface RamblerSettingsStore {
    suspend fun loadApiKey(): String?
    /** Mutable adapters must capture the key and its metadata together. */
    suspend fun loadCredentialSnapshot(): RamblerCredentialSnapshot =
        throw GoogleRamblerProvisioningException(GoogleRamblerFailure.InvalidResponse)
    suspend fun saveApiKey(apiKey: String)
    suspend fun clearApiKey()
    suspend fun loadGoogleConnection(): GoogleRamblerConnection? = null
    suspend fun saveGoogleKey(apiKey: String, connection: GoogleRamblerConnection) { saveApiKey(apiKey) }
    suspend fun loadPendingGoogleProject(subject: String): String? = null
    suspend fun savePendingGoogleProject(subject: String, projectId: String) = Unit
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
        decryptKey(preferences.getString(KEY_PREFERENCE, null))
    }

    override suspend fun loadCredentialSnapshot(): RamblerCredentialSnapshot = withContext(ioDispatcher) {
        // SharedPreferences returns a copy captured under its own lock. Read
        // both ciphertexts from that copy even if another store instance
        // commits replacement credentials while either decryption is running.
        val records = preferences.all
        RamblerCredentialSnapshot(
            apiKey = decryptKey(records[KEY_PREFERENCE] as? String),
            connection = connectionFrom(records),
        )
    }

    @Suppress("UseKtx") // Report a failed credential write instead of silently accepting it.
    override suspend fun saveApiKey(apiKey: String) = withContext(ioDispatcher) {
        mutationMutex.withLock {
            val ciphertext = encryptedKey(apiKey)
            check(
                preferences.edit()
                    .putString(KEY_PREFERENCE, ciphertext)
                    .remove(CONNECTION_PREFERENCE)
                    .commit(),
            ) { "Could not persist Gemini API key" }
        }
    }

    @Suppress("UseKtx") // Report a failed removal instead of claiming the key was removed.
    override suspend fun clearApiKey() = withContext(ioDispatcher) {
        mutationMutex.withLock {
            check(preferences.edit().remove(KEY_PREFERENCE).remove(CONNECTION_PREFERENCE).commit()) {
                "Could not clear Gemini API key"
            }
        }
    }

    override suspend fun loadGoogleConnection(): GoogleRamblerConnection? = withContext(ioDispatcher) {
        connectionFrom(preferences.all)
    }

    @Suppress("UseKtx")
    override suspend fun saveGoogleKey(apiKey: String, connection: GoogleRamblerConnection) = withContext(ioDispatcher) {
        mutationMutex.withLock {
            val metadata = GoogleRamblerProtocol.encodeConnection(connection)
            // Complete both encryptions before changing preferences. A single
            // commit prevents a new key from inheriting old billing metadata.
            val keyCiphertext = encryptedKey(apiKey)
            val connectionCiphertext = encryptMetadata(metadata, CONNECTION_AAD, GoogleRamblerProtocol.MAX_CONNECTION_CHARS)
            check(preferences.edit()
                .putString(KEY_PREFERENCE, keyCiphertext)
                .putString(CONNECTION_PREFERENCE, connectionCiphertext)
                .commit()) { "Could not persist Google connection" }
        }
    }

    override suspend fun loadPendingGoogleProject(subject: String): String? = withContext(ioDispatcher) {
        if (!GoogleRamblerProtocol.isValidSubject(subject)) invalidMetadata()
        pendingProjects()[subject]
    }

    @Suppress("UseKtx")
    override suspend fun savePendingGoogleProject(subject: String, projectId: String) = withContext(ioDispatcher) {
        if (!GoogleRamblerProtocol.isValidSubject(subject) || !GoogleRamblerProtocol.isOwnedProjectId(projectId)) {
            invalidMetadata()
        }
        mutationMutex.withLock {
            val projects = pendingProjects().toMutableMap()
            val existing = projects[subject]
            // Concurrent retries must not replace a checkpoint and orphan its
            // already-created project. Resume the original project instead.
            if (existing != null && existing != projectId) invalidMetadata()
            if (existing == null && projects.size >= MAX_PENDING_PROJECTS) invalidMetadata()
            projects[subject] = projectId
            val plaintext = buildJsonObject {
                put("version", 1)
                put("projects", JsonObject(projects.mapValues { JsonPrimitive(it.value) }))
            }.toString()
            val ciphertext = encryptMetadata(plaintext, PENDING_AAD, MAX_PENDING_CHARS)
            check(preferences.edit().putString(PENDING_PREFERENCE, ciphertext).commit()) {
                "Could not persist Google setup progress"
            }
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

    private fun connectionFrom(records: Map<String, *>): GoogleRamblerConnection? {
        if (!records.containsKey(CONNECTION_PREFERENCE)) return null
        val encoded = records[CONNECTION_PREFERENCE] as? String ?: invalidMetadata()
        val plaintext = decryptMetadata(encoded, CONNECTION_AAD, GoogleRamblerProtocol.MAX_CONNECTION_CHARS)
        return GoogleRamblerProtocol.parseConnection(plaintext) ?: invalidMetadata()
    }

    private fun pendingProjects(): Map<String, String> {
        if (!preferences.contains(PENDING_PREFERENCE)) return emptyMap()
        val encoded = runCatching { preferences.getString(PENDING_PREFERENCE, null) }
            .getOrElse { invalidMetadata() } ?: invalidMetadata()
        val plaintext = decryptMetadata(encoded, PENDING_AAD, MAX_PENDING_CHARS)
        return runCatching {
            val root = Json.parseToJsonElement(plaintext) as? JsonObject ?: invalidMetadata()
            val version = root["version"] as? JsonPrimitive ?: invalidMetadata()
            if (version.isString || version.intOrNull != 1) invalidMetadata()
            val entries = root["projects"] as? JsonObject ?: invalidMetadata()
            if (entries.size > MAX_PENDING_PROJECTS) invalidMetadata()
            entries.mapValues { (subject, value) ->
                val id = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidMetadata()
                if (!GoogleRamblerProtocol.isValidSubject(subject) || !GoogleRamblerProtocol.isOwnedProjectId(id)) {
                    invalidMetadata()
                }
                id
            }
        }.getOrElse { invalidMetadata() }
    }

    private fun encryptMetadata(value: String, purpose: String, maxChars: Int): String {
        if (value.length > maxChars) invalidMetadata()
        val ciphertext = aead.encrypt(value.toByteArray(StandardCharsets.UTF_8), purpose.toByteArray(StandardCharsets.UTF_8))
        if (ciphertext.size > maxChars * 4 + 1_024) invalidMetadata()
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decryptMetadata(value: String, purpose: String, maxChars: Int): String = runCatching {
        val maxBytes = maxChars * 4 + 1_024
        if (value.length > maxBytes * 2) invalidMetadata()
        val ciphertext = Base64.decode(value, Base64.DEFAULT)
        if (ciphertext.size > maxBytes) invalidMetadata()
        val plaintext = aead.decrypt(ciphertext, purpose.toByteArray(StandardCharsets.UTF_8))
        if (plaintext.size > maxChars * 4) invalidMetadata()
        plaintext.toString(StandardCharsets.UTF_8).also { if (it.length > maxChars) invalidMetadata() }
    }.getOrElse { invalidMetadata() }

    private fun invalidMetadata(): Nothing =
        throw GoogleRamblerProvisioningException(GoogleRamblerFailure.InvalidResponse)

    private fun associatedData(): ByteArray = GOOGLE_API_ORIGIN.toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "rambler_settings"
        const val GOOGLE_API_ORIGIN = "https://generativelanguage.googleapis.com"
        const val KEY_PREFERENCE = "google_api_key"
        const val CONNECTION_PREFERENCE = "google_managed_connection"
        const val PENDING_PREFERENCE = "google_pending_projects"
        const val CONNECTION_AAD = "$GOOGLE_API_ORIGIN/managed-connection:v1"
        const val PENDING_AAD = "$GOOGLE_API_ORIGIN/pending-projects:v1"
        const val MAX_PENDING_PROJECTS = 16
        const val MAX_PENDING_CHARS = 8_192
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
