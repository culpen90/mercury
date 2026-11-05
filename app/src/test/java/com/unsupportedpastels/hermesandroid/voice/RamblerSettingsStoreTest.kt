package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RamblerSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferencesName = "rambler_settings_tests"
    private val preferences get() = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Before
    fun resetPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun encryptsTheKeyWithGoogleOriginAndLoadsItFromAnotherStoreInstance() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(
            context, preferencesName, StandardTestDispatcher(testScheduler), { aead },
        )
        store.saveApiKey("  example_google_test_key_123  ")

        val persisted = preferences.all.values.joinToString("|")
        assertFalse(persisted.contains("example_google_test_key_123"))
        assertTrue(persisted.isNotEmpty())
        assertEquals("https://generativelanguage.googleapis.com", aead.lastAssociatedData)
        assertEquals(
            "example_google_test_key_123",
            EncryptedRamblerSettingsStore(
                context, preferencesName, StandardTestDispatcher(testScheduler), { aead },
            ).loadApiKey(),
        )
    }

    @Test
    fun legacyManagedKeyIsNotSilentlyReusedAsAPersonalKey() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.saveApiKey("legacy_managed_key")
        preferences.edit()
            .putString("google_managed_connection", "legacy-encrypted-metadata")
            .putString("google_pending_projects", "legacy-encrypted-checkpoints")
            .commit()
        val before = preferences.all

        assertNull(store.loadApiKey())
        assertEquals(before, preferences.all)
        assertTrue(store.requiresPersonalApiKey())
    }

    @Test
    fun personalReplacementAtomicallyClearsLegacyMetadataAndCheckpoints() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.saveApiKey("legacy_managed_key")
        preferences.edit()
            .putString("google_managed_connection", "legacy-encrypted-metadata")
            .putString("google_pending_projects", "legacy-encrypted-checkpoints")
            .commit()

        store.saveApiKey("personal_replacement_key")

        assertEquals("personal_replacement_key", store.loadApiKey())
        assertFalse(store.requiresPersonalApiKey())
        assertEquals(setOf("google_api_key"), preferences.all.keys)
        assertFalse(preferences.all.values.joinToString().contains("personal_replacement_key"))
        val restored = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        assertEquals("personal_replacement_key", restored.loadApiKey())
    }

    @Test
    fun failedPersonalReplacementDoesNotUnlockOrDestroyTheLegacyCredential() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.saveApiKey("legacy_managed_key")
        preferences.edit()
            .putBoolean("google_managed_connection", true)
            .putString("google_pending_projects", "legacy-encrypted-checkpoints")
            .commit()
        val before = preferences.all
        aead.failOnEncrypt = aead.encryptions + 1

        assertTrue(runCatching { store.saveApiKey("personal_replacement_key") }.isFailure)

        assertEquals(before, preferences.all)
        assertNull(store.loadApiKey())
        assertTrue(store.requiresPersonalApiKey())
    }

    @Test
    fun legacyCredentialCanBeRemovedWithoutDecryptingIt() = runTest {
        preferences.edit()
            .putString("google_api_key", "legacy-ciphertext")
            .putString("google_managed_connection", "legacy-encrypted-metadata")
            .putString("google_pending_projects", "legacy-encrypted-checkpoints")
            .commit()
        var keystoreReads = 0
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), {
            keystoreReads++
            error("Unavailable keystore")
        })

        assertNull(store.loadApiKey())
        store.clearApiKey()

        assertTrue(preferences.all.isEmpty())
        assertFalse(store.requiresPersonalApiKey())
        assertEquals(0, keystoreReads)
    }

    @Test
    fun oldProjectCheckpointDoesNotBlockAnExistingPersonalKey() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.saveApiKey("existing_personal_key")
        preferences.edit().putString("google_pending_projects", "legacy-encrypted-checkpoints").commit()

        assertEquals("existing_personal_key", store.loadApiKey())
        assertFalse(store.requiresPersonalApiKey())
    }

    @Test
    fun initializationIsLazyAndMissingKeyDoesNotAccessTheKeystore() = runTest {
        var factoryCalls = 0
        val store = EncryptedRamblerSettingsStore(
            context, preferencesName, StandardTestDispatcher(testScheduler),
            aeadFactory = { factoryCalls++; recordingAead() },
        )
        assertEquals(0, factoryCalls)
        assertNull(store.loadApiKey())
        assertEquals(0, factoryCalls)
        store.saveApiKey("example_key")
        assertEquals(1, factoryCalls)
    }

    @Test
    fun clearingGoogleKeyDoesNotTouchHermesCredentials() = runTest {
        val nativePreferences = context.getSharedPreferences("native_token_store", Context.MODE_PRIVATE)
        nativePreferences.edit().putString("unrelated_record", "unrelated_ciphertext").commit()
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(
            context, preferencesName, StandardTestDispatcher(testScheduler), { aead },
        )
        store.saveApiKey("example_key")

        store.clearApiKey()

        assertNull(store.loadApiKey())
        assertTrue(preferences.all.isEmpty())
        assertEquals("unrelated_ciphertext", nativePreferences.getString("unrelated_record", null))
        nativePreferences.edit().remove("unrelated_record").commit()
    }

    @Test
    fun corruptOrOversizedCiphertextFailsClosed() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(
            context, preferencesName, StandardTestDispatcher(testScheduler), { aead },
        )
        store.saveApiKey("example_key")
        val key = preferences.all.keys.single()

        preferences.edit().putString(key, "not-a-ciphertext").commit()
        assertNull(store.loadApiKey())
        preferences.edit().putString(key, "a".repeat(32 * 1024)).commit()
        assertNull(store.loadApiKey())
    }

    @Test
    fun ciphertextForAHermesOriginCannotBecomeAGoogleKey() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(
            context, preferencesName, StandardTestDispatcher(testScheduler), { aead },
        )
        store.saveApiKey("example_key")
        val key = preferences.all.keys.single()
        val ciphertext = aead.encrypt(
            "foreign_example_key".toByteArray(),
            "https://hermes.example".toByteArray(),
        )
        preferences.edit().putString(key, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit()

        assertNull(store.loadApiKey())
    }

    @Test
    fun unsafeKeysAreRejectedWithoutReplacingTheSavedKey() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(
            context, preferencesName, StandardTestDispatcher(testScheduler), { aead },
        )
        store.saveApiKey("existing_example_key")
        listOf("", "   ", "example\nkey", "key\r", "key\t", "key\u0000", "é", "a".repeat(513))
            .forEach { unsafe ->
                val failure = runCatching { store.saveApiKey(unsafe) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertEquals("existing_example_key", store.loadApiKey())
                assertFalse(failure?.message.orEmpty().contains(unsafe.takeIf { it.isNotEmpty() } ?: "never"))
            }
    }

    @Test
    fun applicationBackupAndDeviceTransferExcludeCredentialPreferences() {
        val module = File(System.getProperty("user.dir"))
        val sourceRoot = if (File(module, "src/main/AndroidManifest.xml").isFile) module else File(module, "app")
        val manifest = File(sourceRoot, "src/main/AndroidManifest.xml").readText()
        val extraction = File(sourceRoot, "src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"false\""))
        assertEquals(2, Regex("<exclude domain=\"sharedpref\" path=\"[.]\" />").findAll(extraction).count())
    }

    private fun recordingAead(): RecordingAead {
        AeadConfig.register()
        return RecordingAead(KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM).getPrimitive(Aead::class.java))
    }

    private class RecordingAead(private val delegate: Aead) : Aead {
        var lastAssociatedData: String? = null
        var encryptions = 0
        var failOnEncrypt: Int? = null
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            encryptions++
            check(encryptions != failOnEncrypt) { "Test encryption failure" }
            lastAssociatedData = associatedData.toString(Charsets.UTF_8)
            return delegate.encrypt(plaintext, associatedData)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            lastAssociatedData = associatedData.toString(Charsets.UTF_8)
            return delegate.decrypt(ciphertext, associatedData)
        }
    }
}
