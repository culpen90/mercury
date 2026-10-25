package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.GoogleRamblerAccount
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerConnection
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerFailure
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerProject
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GOOGLE_PROJECT_ID = "mercury-rambler-0123456789ab"
private const val GOOGLE_PROJECT_NUMBER = "123456789"
private const val GOOGLE_SA_EMAIL = "mercury-rambler@$GOOGLE_PROJECT_ID.iam.gserviceaccount.com"
private val googleAccount = GoogleRamblerAccount("account-subject", "person@example.com")
private val googleProject = GoogleRamblerProject(GOOGLE_PROJECT_ID, GOOGLE_PROJECT_NUMBER)

private class ProvisioningStore : RamblerSettingsStore {
    var key: String? = null
    var connection: GoogleRamblerConnection? = null
    val pending = mutableMapOf<String, String>()
    val checkpoints = mutableListOf<String>()
    override suspend fun loadApiKey(): String? = key
    override suspend fun saveApiKey(apiKey: String) { key = apiKey; connection = null }
    override suspend fun clearApiKey() { key = null; connection = null }
    override suspend fun loadGoogleConnection(): GoogleRamblerConnection? = connection
    override suspend fun saveGoogleKey(apiKey: String, connection: GoogleRamblerConnection) {
        key = apiKey; this.connection = connection
    }
    override suspend fun loadPendingGoogleProject(subject: String): String? = pending[subject]
    override suspend fun savePendingGoogleProject(subject: String, projectId: String) {
        pending[subject] = projectId; checkpoints += projectId
    }
}

private class ProvisioningCloud(
    val store: ProvisioningStore,
    dispatcher: CoroutineDispatcher,
    private val now: () -> Long = { 0L },
) {
    val requests = mutableListOf<HttpRequestData>()
    var projectExists = false
    var identityExists = false
    var keyExists = false
    var billingEnabled = false
    var billingLinked = false
    var unsafeKey = false
    var deniedOnEnable = false
    var maliciousProjectOperation = false
    var missingProjectReturnsForbidden = false
    var projectCreateConflict = false
    var billingChecks = 0
    var billOnSecondCheck = false
    var identityReadFailures = 0
    var identityReadFailureStatus = HttpStatusCode.NotFound
    var identityCreateConflict = false
    var keyCreateFailures = 0
    var keyCreateFailureStatus = HttpStatusCode.NotFound
    var keyCreateFailureBody: String? = null
    var enableServicesDelayMillis = 0L
    val identityReadTimes = mutableListOf<Long>()
    val keyCreateTimes = mutableListOf<Long>()
    val engine = MockEngine(MockEngineConfig().apply {
      this.dispatcher = dispatcher
      addHandler { request ->
        requests += request
        assertEquals("Bearer oauth-test-token", request.headers[HttpHeaders.Authorization])
        assertNull(request.headers[HttpHeaders.Cookie])
        assertFalse(request.url.toString().contains("oauth-test-token"))
        var status = HttpStatusCode.OK
        val path = request.url.encodedPath
        val body = when {
            request.url.host == "openidconnect.googleapis.com" ->
                """{"sub":"account-subject","email":"person@example.com","email_verified":true}"""
            path == "/v3/projects/$GOOGLE_PROJECT_ID" -> if (projectExists) projectJson() else {
                status = if (missingProjectReturnsForbidden) HttpStatusCode.Forbidden else HttpStatusCode.NotFound
                "{}"
            }
            path == "/v3/projects" -> {
                assertEquals(GOOGLE_PROJECT_ID, store.pending[googleAccount.subject])
                assertTrue((request.body as TextContent).text.contains("mercury-feature"))
                if (projectCreateConflict) { status = HttpStatusCode.Conflict; "{}" }
                else {
                    projectExists = true
                    if (maliciousProjectOperation) """{"name":"https://attacker.example/operation"}"""
                    else """{"name":"operations/create-project","done":true,"response":${projectJson()}}"""
                }
            }
            request.url.host == "cloudbilling.googleapis.com" -> {
                billingChecks++
                val billed = billingEnabled || (billOnSecondCheck && billingChecks > 1)
                """{"billingEnabled":$billed,"billingAccountName":"${if (billingLinked) "billingAccounts/linked" else ""}"}"""
            }
            path.endsWith("/services:batchEnable") -> {
                delay(enableServicesDelayMillis)
                if (deniedOnEnable) { status = HttpStatusCode.Forbidden; """{"error":{"code":403}}""" }
                else """{"name":"operations/enable-services","done":true,"response":{}}"""
            }
            request.url.host == "iam.googleapis.com" && request.method.value == "GET" -> {
                if (!identityExists) { status = HttpStatusCode.NotFound; "{}" }
                else {
                    identityReadTimes += now()
                    if (identityReadFailures > 0) {
                        identityReadFailures--
                        status = identityReadFailureStatus
                        errorJson(status)
                    } else accountJson()
                }
            }
            request.url.host == "iam.googleapis.com" -> {
                identityExists = true
                if (identityCreateConflict) { status = HttpStatusCode.Conflict; "{}" } else accountJson()
            }
            path.endsWith("/keyString") -> """{"keyString":"generated_google_key"}"""
            request.url.host == "apikeys.googleapis.com" && request.method.value == "GET" ->
                if (keyExists) keyJson() else { status = HttpStatusCode.NotFound; "{}" }
            request.url.host == "apikeys.googleapis.com" -> {
                keyCreateTimes += now()
                assertEquals("mercury-rambler", request.url.parameters["keyId"])
                val keyBody = (request.body as TextContent).text
                assertTrue(keyBody.contains(GOOGLE_SA_EMAIL))
                assertTrue(keyBody.contains("generativelanguage.googleapis.com"))
                if (keyCreateFailures > 0) {
                    keyCreateFailures--
                    status = keyCreateFailureStatus
                    keyCreateFailureBody ?: errorJson(status)
                } else {
                    keyExists = true
                    """{"name":"operations/create-key","done":true,"response":${keyJson()}}"""
                }
            }
            else -> error("Unexpected fixed API request")
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
      }
    })
    fun cloud() = GoogleRamblerCloudClient(engine)
    fun projectJson(): String = """{"name":"projects/$GOOGLE_PROJECT_NUMBER","projectId":"$GOOGLE_PROJECT_ID","state":"ACTIVE","labels":{"mercury-feature":"rambler"}}"""
    fun accountJson(): String = """{"name":"projects/$GOOGLE_PROJECT_ID/serviceAccounts/$GOOGLE_SA_EMAIL","email":"$GOOGLE_SA_EMAIL","projectId":"$GOOGLE_PROJECT_ID"}"""
    fun keyJson(): String = """{"name":"projects/$GOOGLE_PROJECT_NUMBER/locations/global/keys/mercury-rambler","serviceAccountEmail":"$GOOGLE_SA_EMAIL","restrictions":{"apiTargets":[{"service":"${if (unsafeKey) "other.googleapis.com" else "generativelanguage.googleapis.com"}"}]}}"""
    fun errorJson(status: HttpStatusCode): String {
        val name = when (status) {
            HttpStatusCode.NotFound -> "NOT_FOUND"
            HttpStatusCode.ServiceUnavailable -> "UNAVAILABLE"
            HttpStatusCode.Forbidden -> "PERMISSION_DENIED"
            else -> "UNKNOWN"
        }
        return """{"error":{"code":${status.value},"status":"$name"}}"""
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleRamblerProvisionerTest {
    @Test fun newlyCreatedIdentityMustBecomeVisibleBeforeBindingTheKey() = runTest {
        for (conflictingCreate in listOf(false, true)) {
            val started = testScheduler.currentTime
            val store = ProvisioningStore()
            val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }.apply {
                identityReadFailures = 3
                identityCreateConflict = conflictingCreate
            }

            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                .provision("oauth-test-token", googleAccount)

            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), cloud.identityReadTimes.map { it - started })
            assertEquals(listOf(7_000L), cloud.keyCreateTimes.map { it - started })
            assertEquals(1, cloud.requests.count { it.url.host == "iam.googleapis.com" && it.method.value == "POST" })
            assertEquals("generated_google_key", store.key)
        }
    }

    @Test fun newlyCreatedIdentityBindingRetriesOnlyTransientStatusesWithTheSameKeyId() = runTest {
        for (transientStatus in listOf(HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)) {
            val started = testScheduler.currentTime
            val store = ProvisioningStore()
            val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }.apply {
                keyCreateFailures = 2
                keyCreateFailureStatus = transientStatus
            }

            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                .provision("oauth-test-token", googleAccount)

            assertEquals(listOf(0L, 1_000L, 3_000L), cloud.keyCreateTimes.map { it - started })
            val creates = cloud.requests.filter { it.url.host == "apikeys.googleapis.com" && it.method.value == "POST" }
            assertEquals(3, creates.size)
            assertTrue(creates.all { it.url.parameters["keyId"] == "mercury-rambler" })
            assertTrue(cloud.requests.none { it.method.value in listOf("PATCH", "PUT", "DELETE") })
            assertEquals("generated_google_key", store.key)
        }
    }

    @Test fun cancellingIdentityVisibilityWaitStopsRetriesWithoutSavingAKey() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }.apply {
            identityReadFailures = Int.MAX_VALUE
        }
        val job = launch {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                .provision("oauth-test-token", googleAccount)
        }
        runCurrent()
        assertFalse(job.isCompleted)

        job.cancelAndJoin()
        val requestCount = cloud.requests.size
        advanceTimeBy(100_000)
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(requestCount, cloud.requests.size)
        assertFalse(cloud.requests.any { it.url.host == "apikeys.googleapis.com" })
        assertNull(store.key)
    }

    @Test fun invisibleIdentityHasABoundedRetryBudget() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }.apply {
            identityReadFailures = Int.MAX_VALUE
        }

        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                .provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()

        assertEquals(GoogleRamblerFailure.Unavailable, (failure as? GoogleRamblerProvisioningException)?.reason)
        assertEquals(90_000L, testScheduler.currentTime)
        assertTrue(cloud.identityReadTimes.size in 2..16)
        assertFalse(cloud.requests.any { it.url.host == "apikeys.googleapis.com" })
        assertNull(store.key)
    }

    @Test fun unavailableNewIdentityBindingHasABoundedRetryBudget() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }.apply {
            keyCreateFailures = Int.MAX_VALUE
            keyCreateFailureStatus = HttpStatusCode.ServiceUnavailable
        }

        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                .provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()

        assertEquals(GoogleRamblerFailure.Unavailable, (failure as? GoogleRamblerProvisioningException)?.reason)
        assertEquals(90_000L, testScheduler.currentTime)
        assertTrue(cloud.keyCreateTimes.size in 2..16)
        assertFalse(cloud.requests.any { it.url.encodedPath.endsWith("/keyString") })
        assertNull(store.key)
    }

    @Test fun propagationRetriesStillRespectTheWholeProvisioningBudget() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }.apply {
            enableServicesDelayMillis = 10_000
            identityReadFailures = 13
            keyCreateFailures = Int.MAX_VALUE
            keyCreateFailureStatus = HttpStatusCode.ServiceUnavailable
        }

        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                .provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()

        assertEquals(GoogleRamblerFailure.Unavailable, (failure as? GoogleRamblerProvisioningException)?.reason)
        assertEquals(180_000L, testScheduler.currentTime)
        assertTrue(cloud.keyCreateTimes.size in 2..16)
        assertNull(store.key)
    }

    @Test fun permanentPermissionFailuresAreNeverRetriedAsIdentityVisibility() = runTest {
        for (httpStatus in listOf(HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)) {
            val started = testScheduler.currentTime
            val store = ProvisioningStore()
            val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }.apply {
                keyCreateFailures = Int.MAX_VALUE
                keyCreateFailureStatus = httpStatus
                keyCreateFailureBody = """{"error":{"code":${httpStatus.value},"status":"PERMISSION_DENIED"}}"""
            }

            val failure = runCatching {
                GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                    .provision("oauth-test-token", googleAccount)
            }.exceptionOrNull()

            assertEquals(GoogleRamblerFailure.Permission, (failure as? GoogleRamblerProvisioningException)?.reason)
            assertEquals(listOf(started), cloud.keyCreateTimes)
            assertEquals(started, testScheduler.currentTime)
            assertNull(store.key)
        }
    }

    @Test fun identityReadPermissionFailureStopsBeforeAnyKeyCreation() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply {
            identityReadFailures = Int.MAX_VALUE
            identityReadFailureStatus = HttpStatusCode.Forbidden
        }

        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                .provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()

        assertEquals(GoogleRamblerFailure.Permission, (failure as? GoogleRamblerProvisioningException)?.reason)
        assertFalse(cloud.requests.any { it.url.host == "apikeys.googleapis.com" })
        assertEquals(0L, testScheduler.currentTime)
        assertNull(store.key)
    }

    @Test fun existingIdentityDoesNotEnableBindingRetries() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply {
            projectExists = true
            identityExists = true
            keyCreateFailures = Int.MAX_VALUE
        }

        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
                .provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()

        assertEquals(GoogleRamblerFailure.Unavailable, (failure as? GoogleRamblerProvisioningException)?.reason)
        assertEquals(1, cloud.keyCreateTimes.size)
        assertEquals(0L, testScheduler.currentTime)
        assertNull(store.key)
    }

    @Test fun nonexistentProjectLookupMayReturnForbiddenBeforeCreation() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply {
            missingProjectReturnsForbidden = true
        }
        val connection = GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
            .provision("oauth-test-token", googleAccount)
        assertEquals(googleProject, connection.project)
        assertEquals(1, cloud.requests.count { it.url.encodedPath == "/v3/projects" })
        assertEquals("generated_google_key", store.key)
    }

    @Test fun forbiddenLookupAndConflictingCreateStillRequireAuthorizedOwnedProjectRead() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply {
            missingProjectReturnsForbidden = true
            projectCreateConflict = true
        }
        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID }).provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()
        assertEquals(1, cloud.requests.count { it.url.encodedPath == "/v3/projects" })
        assertEquals(GoogleRamblerFailure.Permission, (failure as GoogleRamblerProvisioningException).reason)
        assertFalse(cloud.requests.any { it.url.host == "apikeys.googleapis.com" })
        assertNull(store.key)
    }

    @Test fun createsOnlyOwnedUnbilledProjectAndRestrictedBoundKey() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler))
        val connection = GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID })
            .provision("oauth-test-token", googleAccount)

        assertEquals(GoogleRamblerConnection(googleAccount, googleProject), connection)
        assertEquals(connection, store.connection)
        assertEquals("generated_google_key", store.key)
        assertEquals(2, cloud.billingChecks)
        assertEquals(listOf(GOOGLE_PROJECT_ID), store.checkpoints)
        assertTrue(cloud.requests.none { it.method.value in listOf("PATCH", "PUT", "DELETE") })
        assertTrue(cloud.requests.none { it.url.encodedPath.contains("setIamPolicy") })
        assertTrue(cloud.requests.filter { it.url.host == "cloudbilling.googleapis.com" }.all { it.method.value == "GET" })
    }

    @Test fun billedProjectIsRejectedBeforeEnablingAnyService() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply { projectExists = true; billingEnabled = true }
        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID }).provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()
        assertEquals(GoogleRamblerFailure.BillingLinked, (failure as GoogleRamblerProvisioningException).reason)
        assertFalse(cloud.requests.any { it.url.host == "serviceusage.googleapis.com" })
        assertNull(store.key)
    }

    @Test fun disabledButLinkedBillingAccountIsStillRejected() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply { projectExists = true; billingLinked = true }
        val failure = runCatching {
            cloud.cloud().checkNoBilling("oauth-test-token", googleProject)
        }.exceptionOrNull()
        assertTrue(failure is GoogleRamblerProvisioningException)
        assertEquals(GoogleRamblerFailure.BillingLinked, (failure as GoogleRamblerProvisioningException).reason)
    }

    @Test fun resumedSetupReusesCheckpointAndExistingIdentityAndKey() = runTest {
        val store = ProvisioningStore().apply { pending[googleAccount.subject] = GOOGLE_PROJECT_ID }
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply { projectExists = true; identityExists = true; keyExists = true }
        GoogleRamblerProvisioner(store, cloud.cloud(), { error("Must resume checkpoint") })
            .provision("oauth-test-token", googleAccount)
        assertFalse(cloud.requests.any { it.url.encodedPath == "/v3/projects" })
        assertFalse(cloud.requests.any { it.url.host == "iam.googleapis.com" && it.method.value == "POST" })
        assertFalse(cloud.requests.any { it.url.host == "apikeys.googleapis.com" && it.method.value == "POST" })
        assertEquals("generated_google_key", store.key)
    }

    @Test fun keyBindingOrRestrictionsAreVerifiedBeforeReadingOrSavingSecret() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply { projectExists = true; keyExists = true; unsafeKey = true }
        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID }).provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()
        assertTrue(failure is GoogleRamblerProvisioningException)
        assertEquals(GoogleRamblerFailure.InvalidResponse, (failure as GoogleRamblerProvisioningException).reason)
        assertFalse(cloud.requests.any { it.url.encodedPath.endsWith("/keyString") })
        assertNull(store.key)
    }

    @Test fun billingChangedDuringSetupPreventsPersistingTheCredential() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply { projectExists = true; billOnSecondCheck = true }
        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID }).provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()
        assertEquals(GoogleRamblerFailure.BillingLinked, (failure as GoogleRamblerProvisioningException).reason)
        assertNull(store.key)
        assertNull(store.connection)
    }

    @Test fun operationNamesCannotRedirectAccessTokenToAnotherHost() = runTest {
        val store = ProvisioningStore()
        val cloud = ProvisioningCloud(store, StandardTestDispatcher(testScheduler)).apply { maliciousProjectOperation = true }
        val failure = runCatching {
            GoogleRamblerProvisioner(store, cloud.cloud(), { GOOGLE_PROJECT_ID }).provision("oauth-test-token", googleAccount)
        }.exceptionOrNull()
        assertTrue(failure is GoogleRamblerProvisioningException)
        assertEquals(2, cloud.requests.size)
        assertTrue(cloud.requests.all { it.url.host == "cloudresourcemanager.googleapis.com" })
        assertNull(failure?.message)
        assertNull(failure?.cause)
    }

    @Test fun fixedGoogleUserinfoEndpointRequiresVerifiedAccount() = runTest {
        val cloud = ProvisioningCloud(ProvisioningStore(), StandardTestDispatcher(testScheduler))
        assertEquals(googleAccount, cloud.cloud().account("oauth-test-token"))
        assertEquals("openidconnect.googleapis.com", cloud.requests.single().url.host)
        assertEquals("/v1/userinfo", cloud.requests.single().url.encodedPath)
    }
}
