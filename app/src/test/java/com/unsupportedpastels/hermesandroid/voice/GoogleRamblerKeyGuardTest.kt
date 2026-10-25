package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.GoogleRamblerAccount
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerConnection
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerFailure
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerProject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRamblerKeyGuardTest {
    private val account = GoogleRamblerAccount("subject-1", "test@example.com")
    private val connection = GoogleRamblerConnection(account, GoogleRamblerProject("mercury-rambler-012345abcdef", "123456"))
    private class Store(private val connection: GoogleRamblerConnection?, private val calls: MutableList<String>) : RamblerSettingsStore {
        override suspend fun loadApiKey(): String { calls += "key"; return "synthetic-key" }
        override suspend fun saveApiKey(apiKey: String) = Unit
        override suspend fun clearApiKey() = Unit
        override suspend fun loadGoogleConnection(): GoogleRamblerConnection? { calls += "connection"; return connection }
        override suspend fun loadCredentialSnapshot(): RamblerCredentialSnapshot {
            calls += "snapshot"
            return RamblerCredentialSnapshot("synthetic-key", connection)
        }
    }

    @Test fun credentialReplacementDuringBillingCannotSubstituteAnUncheckedKey() = runTest {
        val replacement = GoogleRamblerConnection(
            GoogleRamblerAccount("subject-2", "other@example.com"),
            GoogleRamblerProject("mercury-rambler-fedcba654321", "987654"),
        )
        for (replacementConnection in listOf(replacement, null)) {
            var stored = RamblerCredentialSnapshot("checked-key", connection)
            val store = object : RamblerSettingsStore {
                override suspend fun loadApiKey(): String? = stored.apiKey
                override suspend fun loadGoogleConnection(): GoogleRamblerConnection? = stored.connection
                override suspend fun loadCredentialSnapshot(): RamblerCredentialSnapshot = stored
                override suspend fun saveApiKey(apiKey: String) = Unit
                override suspend fun clearApiKey() = Unit
            }
            val key = checkedRamblerApiKey(store,
                authorize = { assertEquals(account.email, it); "synthetic-token" },
                accountForToken = { account },
                checkNoBilling = { _, project ->
                    assertEquals(connection.project, project)
                    stored = RamblerCredentialSnapshot("unchecked-replacement", replacementConnection)
                })

            assertEquals("checked-key", key)
            assertEquals("unchecked-replacement", store.loadApiKey())
        }
    }

    @Test fun credentialSnapshotDoesNotExposeItsKeyInDiagnostics() {
        val snapshot = RamblerCredentialSnapshot("private-synthetic-key", connection)
        assertFalse(snapshot.toString().contains("private-synthetic-key"))
        assertFalse(snapshot.toString().contains(account.email))
    }

    @Test fun manualKeyDoesNotRequireCloudAccountAccess() = runTest {
        val calls = mutableListOf<String>()
        val key = checkedRamblerApiKey(Store(null, calls),
            authorize = { error("manual key must not authorize Cloud access") },
            accountForToken = { error("manual key must not fetch account") },
            checkNoBilling = { _, _ -> error("manual key must not fetch billing") })
        assertEquals("synthetic-key", key)
        assertEquals(listOf("snapshot"), calls)
    }

    @Test fun managedKeyChecksTheCapturedAccountAndBillingBeforeReturningItsKey() = runTest {
        val calls = mutableListOf<String>()
        checkedRamblerApiKey(Store(connection, calls),
            authorize = { email -> assertEquals(account.email, email); calls += "authorize"; "synthetic-token" },
            accountForToken = { calls += "identity"; account },
            checkNoBilling = { _, project -> assertEquals(connection.project, project); calls += "billing" })
        assertEquals(listOf("snapshot", "authorize", "identity", "billing"), calls)
    }

    @Test fun aDifferentGoogleAccountCannotUseTheSavedManagedKey() = runTest {
        val calls = mutableListOf<String>()
        val failure = runCatching {
            checkedRamblerApiKey(Store(connection, calls), authorize = { "synthetic-token" },
                accountForToken = { GoogleRamblerAccount("different", account.email) },
                checkNoBilling = { _, _ -> calls += "billing" })
        }.exceptionOrNull()
        assertTrue(failure is GoogleRamblerProvisioningException)
        assertEquals(GoogleRamblerFailure.Authorization, (failure as GoogleRamblerProvisioningException).reason)
        assertEquals(listOf("snapshot"), calls)
    }

    @Test fun billingOrRevokedConsentStopsBeforeReturningTheCapturedKey() = runTest {
        for (reason in listOf(GoogleRamblerFailure.BillingLinked, GoogleRamblerFailure.Authorization)) {
            val calls = mutableListOf<String>()
            val failure = runCatching {
                checkedRamblerApiKey(Store(connection, calls),
                    authorize = {
                        if (reason == GoogleRamblerFailure.Authorization) throw GoogleRamblerProvisioningException(reason)
                        "synthetic-token"
                    },
                    accountForToken = { account },
                    checkNoBilling = { _, _ -> throw GoogleRamblerProvisioningException(reason) })
            }.exceptionOrNull()
            assertTrue(failure is GoogleRamblerProvisioningException)
            assertEquals(reason, (failure as GoogleRamblerProvisioningException).reason)
            assertEquals(listOf("snapshot"), calls)
        }
    }

    @Test fun anAdapterWithoutAnAtomicCredentialReadFailsClosed() = runTest {
        val store = object : RamblerSettingsStore {
            override suspend fun loadApiKey(): String = error("must not read an unbound key")
            override suspend fun saveApiKey(apiKey: String) = Unit
            override suspend fun clearApiKey() = Unit
        }
        val failure = runCatching {
            checkedRamblerApiKey(store,
                authorize = { error("must not authorize") },
                accountForToken = { error("must not fetch account") },
                checkNoBilling = { _, _ -> error("must not fetch billing") })
        }.exceptionOrNull()
        assertEquals(GoogleRamblerFailure.InvalidResponse, (failure as GoogleRamblerProvisioningException).reason)
    }

    @Test fun aMissingCapturedKeyNeverRequestsCloudAccess() = runTest {
        val store = object : RamblerSettingsStore {
            override suspend fun loadCredentialSnapshot() = RamblerCredentialSnapshot(null, connection)
            override suspend fun loadApiKey(): String = error("must not reread key")
            override suspend fun saveApiKey(apiKey: String) = Unit
            override suspend fun clearApiKey() = Unit
        }
        assertEquals(null, checkedRamblerApiKey(store,
            authorize = { error("must not authorize") },
            accountForToken = { error("must not fetch account") },
            checkNoBilling = { _, _ -> error("must not fetch billing") }))
    }
}
