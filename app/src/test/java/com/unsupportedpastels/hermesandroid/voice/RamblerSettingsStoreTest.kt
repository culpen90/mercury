package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerAccount
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerConnection
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerProject
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
    private val managedConnection = GoogleRamblerConnection(
        GoogleRamblerAccount("private-subject", "private-person@example.com"),
        GoogleRamblerProject("mercury-rambler-0123456789ab", "123456789"),
    )

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
    fun managedCredentialAndConnectionAreEncryptedAndReloadTogether() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.saveGoogleKey("managed_google_key", managedConnection)
        val restored = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })

        assertEquals("managed_google_key", restored.loadApiKey())
        assertEquals(managedConnection, restored.loadGoogleConnection())
        val credential = restored.loadCredentialSnapshot()
        assertEquals("managed_google_key", credential.apiKey)
        assertEquals(managedConnection, credential.connection)
        val persisted = preferences.all.toString()
        listOf("managed_google_key", "private-subject", "private-person@example.com", "mercury-rambler-0123456789ab")
            .forEach { assertFalse(persisted.contains(it)) }
    }

    @Test
    fun credentialSnapshotDoesNotMixRecordsWhenAnotherStoreCommitsDuringDecryption() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        val replacementConnection = GoogleRamblerConnection(
            GoogleRamblerAccount("replacement-subject", "replacement@example.com"),
            GoogleRamblerProject("mercury-rambler-fedcba987654", "987654321"),
        )
        store.saveGoogleKey("replacement_key", replacementConnection)
        val replacementRecords = preferences.all.mapValues { it.value as String }
        store.saveGoogleKey("original_key", managedConnection)
        aead.beforeDecrypt = {
            aead.beforeDecrypt = null
            val editor = preferences.edit().clear()
            replacementRecords.forEach { (name, value) -> editor.putString(name, value) }
            check(editor.commit())
        }

        val snapshot = store.loadCredentialSnapshot()

        assertEquals("original_key", snapshot.apiKey)
        assertEquals(managedConnection, snapshot.connection)
        assertEquals("replacement_key", store.loadApiKey())
        assertEquals(replacementConnection, store.loadGoogleConnection())
    }

    @Test
    fun failedManagedMetadataEncryptionDoesNotReplaceTheExistingCredential() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.saveApiKey("existing_manual_key")
        aead.failOnEncrypt = aead.encryptions + 2
        assertTrue(runCatching { store.saveGoogleKey("new_managed_key", managedConnection) }.isFailure)
        assertEquals("existing_manual_key", store.loadApiKey())
        assertNull(store.loadGoogleConnection())
    }

    @Test
    fun manualReplacementAndRemovalClearManagedConnectionMetadata() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.saveGoogleKey("managed_google_key", managedConnection)
        assertEquals(managedConnection, store.loadGoogleConnection())
        store.saveApiKey("manual_replacement")
        assertNull(store.loadGoogleConnection())
        assertEquals("manual_replacement", store.loadApiKey())
        store.saveGoogleKey("managed_google_key", managedConnection)
        store.clearApiKey()
        assertNull(store.loadGoogleConnection())
        assertNull(store.loadApiKey())
    }

    @Test
    fun setupCheckpointsAreEncryptedResumableAndScopedBySubject() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.savePendingGoogleProject("first-private-subject", "mercury-rambler-0123456789ab")
        store.savePendingGoogleProject("second-private-subject", "mercury-rambler-fedcba987654")
        val restored = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        assertEquals("mercury-rambler-0123456789ab", restored.loadPendingGoogleProject("first-private-subject"))
        assertEquals("mercury-rambler-fedcba987654", restored.loadPendingGoogleProject("second-private-subject"))
        assertNull(restored.loadPendingGoogleProject("third-subject"))
        val persisted = preferences.all.toString()
        assertFalse(persisted.contains("first-private-subject"))
        assertFalse(persisted.contains("mercury-rambler-0123456789ab"))
        store.clearApiKey()
        assertEquals("mercury-rambler-0123456789ab", restored.loadPendingGoogleProject("first-private-subject"))
    }

    @Test
    fun corruptManagedConnectionCannotTurnItsKeyIntoAnUncheckedManualKey() = runTest {
        val aead = recordingAead()
        val store = EncryptedRamblerSettingsStore(context, preferencesName, StandardTestDispatcher(testScheduler), { aead })
        store.saveGoogleKey("managed_google_key", managedConnection)
        val keyPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val metadataKey = keyPreferences.all.keys.single { it != "google_api_key" }
        keyPreferences.edit().putString(metadataKey, "corrupted-managed-record").commit()

        val failure = runCatching { store.loadGoogleConnection() }.exceptionOrNull()
        assertTrue(failure is GoogleRamblerProvisioningException)
        assertEquals(
            com.unsupportedpastels.mercury.core.voice.GoogleRamblerFailure.InvalidResponse,
            (failure as GoogleRamblerProvisioningException).reason,
        )
        assertEquals("managed_google_key", store.loadApiKey())
        val snapshotFailure = runCatching { store.loadCredentialSnapshot() }.exceptionOrNull()
        assertEquals(
            com.unsupportedpastels.mercury.core.voice.GoogleRamblerFailure.InvalidResponse,
            (snapshotFailure as GoogleRamblerProvisioningException).reason,
        )
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
        assertNull(store.loadCredentialSnapshot().apiKey)
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
        var beforeDecrypt: (() -> Unit)? = null
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            encryptions++
            check(encryptions != failOnEncrypt) { "Test encryption failure" }
            lastAssociatedData = associatedData.toString(Charsets.UTF_8)
            return delegate.encrypt(plaintext, associatedData)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            beforeDecrypt?.invoke()
            lastAssociatedData = associatedData.toString(Charsets.UTF_8)
            return delegate.decrypt(ciphertext, associatedData)
        }
    }
}
