package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.GoogleRamblerAccount
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerConnection
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerFailure
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerProject
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerProtocol
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerService
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal enum class GoogleRamblerProvisioningStage {
    CheckingProject, CreatingProject, CheckingBilling, EnablingServices,
    CreatingIdentity, CreatingKey, Saving,
}

internal class GoogleRamblerProvisioningException(val reason: GoogleRamblerFailure) : Exception()

internal fun googleRamblerCloudHttpClient(engine: HttpClientEngine = CIO.create()): HttpClient = HttpClient(engine) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
    // No cookies, logging, shared authorization, or automatic retry plugins.
}

/** Dedicated Google management client, never sharing the Hermes HTTP stack. */
internal class GoogleRamblerCloudClient(
    engine: HttpClientEngine = CIO.create(),
    private val pollDelayMillis: Long = 1_000,
    private val maxPollAttempts: Int = 90,
) : AutoCloseable {
    private val client = googleRamblerCloudHttpClient(engine)
    suspend fun account(accessToken: String): GoogleRamblerAccount {
        val response = request(accessToken, USERINFO)
        return GoogleRamblerProtocol.parseAccount(response.body) ?: invalidResponse()
    }

    suspend fun checkNoBilling(accessToken: String, project: GoogleRamblerProject) {
        validateProject(project)
        val response = request(accessToken, "$BILLING/v1/projects/${project.projectId}/billingInfo")
        GoogleRamblerProtocol.billingFailure(response.body, project.projectId)?.let {
            // Unknown billing state and affirmative linkage both fail closed,
            // but only affirmative evidence is described as a linked account.
            throw GoogleRamblerProvisioningException(it)
        }
    }

    internal suspend fun project(
        token: String,
        projectId: String,
        onCreate: () -> Unit,
    ): GoogleRamblerProject {
        if (!GoogleRamblerProtocol.isOwnedProjectId(projectId)) invalidResponse()
        val path = "$RESOURCE_MANAGER/v3/projects/$projectId"
        // Resource Manager intentionally may return either 403 or 404 for a
        // not-yet-created project. Only the persisted, app-generated ID is a
        // creation candidate; the final GET must still prove ownership.
        val existing = request(token, path, allowedStatuses = setOf(403, 404))
        if (existing.code !in setOf(403, 404)) {
            return GoogleRamblerProtocol.parseProject(existing.body, projectId) ?: invalidResponse()
        }
        onCreate()
        val created = request(
            token, "$RESOURCE_MANAGER/v3/projects", HttpMethod.Post,
            GoogleRamblerProtocol.projectCreateBody(projectId), setOf(409),
        )
        if (created.code != 409) awaitOperation(token, GoogleRamblerService.ResourceManager, created.body)
        val result = request(token, path)
        return GoogleRamblerProtocol.parseProject(result.body, projectId) ?: invalidResponse()
    }

    internal suspend fun enableServices(token: String, project: GoogleRamblerProject) {
        validateProject(project)
        val response = request(
            token, "$SERVICE_USAGE/v1/projects/${project.number}/services:batchEnable", HttpMethod.Post,
            GoogleRamblerProtocol.enableServicesBody(),
        )
        awaitOperation(token, GoogleRamblerService.ServiceUsage, response.body)
    }

    internal suspend fun ensureServiceAccount(token: String, project: GoogleRamblerProject): Boolean {
        validateProject(project)
        val email = GoogleRamblerProtocol.serviceAccountEmail(project.projectId)
        val path = "$IAM/v1/projects/${project.projectId}/serviceAccounts/$email"
        val existing = request(token, path, allowedStatuses = setOf(404))
        if (existing.code != 404) {
            if (!GoogleRamblerProtocol.parseServiceAccount(existing.body, project.projectId)) invalidResponse()
            return false
        }
        rejectPermanentPropagationFailure(existing)
        val created = request(
            token, "$IAM/v1/projects/${project.projectId}/serviceAccounts", HttpMethod.Post,
            GoogleRamblerProtocol.serviceAccountCreateBody(), setOf(409),
        )
        if (created.code != 409 && !GoogleRamblerProtocol.parseServiceAccount(created.body, project.projectId)) invalidResponse()
        // New service accounts can take 60 seconds or more to appear in reads.
        // Verify the exact canonical identity before asking another API to bind it.
        retryNewIdentityPropagation {
            val visible = request(token, path, allowedStatuses = PROPAGATION_STATUSES)
            if (visible.code in PROPAGATION_STATUSES) retryPropagationFailure(visible)
            if (!GoogleRamblerProtocol.parseServiceAccount(visible.body, project.projectId)) invalidResponse()
        }
        return true
    }

    internal suspend fun restrictedKey(
        token: String,
        project: GoogleRamblerProject,
        newlyCreatedIdentity: Boolean = false,
    ): String {
        validateProject(project)
        return if (newlyCreatedIdentity) {
            retryNewIdentityPropagation { restrictedKeyOnce(token, project, true) }
        } else restrictedKeyOnce(token, project, false)
    }

    private suspend fun restrictedKeyOnce(
        token: String,
        project: GoogleRamblerProject,
        waitForIdentity: Boolean,
    ): String {
        val name = "projects/${project.number}/locations/global/keys/${GoogleRamblerProtocol.KEY_ID}"
        val path = "$API_KEYS/v2/$name"
        val existing = request(token, path, allowedStatuses = if (waitForIdentity) PROPAGATION_STATUSES else setOf(404))
        if (existing.code == 503) retryPropagationFailure(existing)
        val metadata = if (existing.code == 404) {
            rejectPermanentPropagationFailure(existing)
            val created = request(
                token, "$API_KEYS/v2/projects/${project.number}/locations/global/keys?keyId=${GoogleRamblerProtocol.KEY_ID}",
                HttpMethod.Post, GoogleRamblerProtocol.authKeyCreateBody(project.projectId),
                if (waitForIdentity) PROPAGATION_STATUSES + 409 else setOf(409),
            )
            if (created.code in PROPAGATION_STATUSES) retryPropagationFailure(created)
            if (created.code != 409) awaitOperation(token, GoogleRamblerService.ApiKeys, created.body)
            val result = request(token, path, allowedStatuses = if (waitForIdentity) PROPAGATION_STATUSES else emptySet())
            if (result.code in PROPAGATION_STATUSES) retryPropagationFailure(result)
            result.body
        } else existing.body
        if (GoogleRamblerProtocol.parseAuthKeyName(metadata, project) != name) invalidResponse()
        // Retrieve the secret only after verifying its exact project, service
        // account binding and Gemini-only API restriction.
        val secret = request(token, "$path/keyString")
        return GoogleRamblerProtocol.parseKeyString(secret.body) ?: invalidResponse()
    }

    private class IdentityNotVisible : Exception()

    private suspend fun <T : Any> retryNewIdentityPropagation(block: suspend () -> T): T =
        withTimeoutOrNull(90_000) {
            var result: T? = null
            var backoffMillis = 1_000L
            while (result == null) {
                try {
                    result = block()
                } catch (_: IdentityNotVisible) {
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(8_000)
                }
            }
            result
        } ?: throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Unavailable)

    private fun rejectPermanentPropagationFailure(response: Response) {
        val reason = GoogleRamblerProtocol.failure(response.code, response.body)
        if (reason != GoogleRamblerFailure.Unavailable) throw GoogleRamblerProvisioningException(reason)
    }

    private fun retryPropagationFailure(response: Response): Nothing {
        // Only NOT_FOUND/UNAVAILABLE during the new-identity phase are candidates.
        // Structured authorization, permission, terms, billing and quota failures
        // take precedence over their HTTP status and never enter this retry loop.
        rejectPermanentPropagationFailure(response)
        throw IdentityNotVisible()
    }

    private suspend fun awaitOperation(token: String, service: GoogleRamblerService, first: String) {
        var current = first
        repeat(maxPollAttempts) {
            val operation = GoogleRamblerProtocol.parseOperation(current, service) ?: invalidResponse()
            operation.failure?.let { throw GoogleRamblerProvisioningException(it) }
            if (operation.done) return
            delay(pollDelayMillis)
            val prefix = when (service) {
                GoogleRamblerService.ResourceManager -> "$RESOURCE_MANAGER/v3"
                GoogleRamblerService.ServiceUsage -> "$SERVICE_USAGE/v1"
                GoogleRamblerService.ApiKeys -> "$API_KEYS/v2"
            }
            // The parsed name is a bounded relative operation token; the
            // provider can never select the host receiving the OAuth token.
            current = request(token, "$prefix/${operation.name}").body
        }
        throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Unavailable)
    }

    private data class Response(val code: Int, val body: String)

    private suspend fun request(
        token: String,
        endpoint: String,
        verb: HttpMethod = HttpMethod.Get,
        body: String? = null,
        allowedStatuses: Set<Int> = emptySet(),
    ): Response {
        if (token.isEmpty() || token.length > 16_384 || token.any { it.code !in 0x21..0x7e }) {
            throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Authorization)
        }
        val target = Url(endpoint)
        if (target.protocol != URLProtocol.HTTPS || target.host !in GOOGLE_HOSTS || !target.user.isNullOrEmpty() || !target.password.isNullOrEmpty()) {
            invalidResponse()
        }
        try {
            val response = client.request(endpoint) {
                method = verb
                bearerAuth(token)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val channel = response.bodyAsChannel()
            val text = try {
                val source = channel.readRemaining(GoogleRamblerProtocol.MAX_JSON_CHARS * 4L + 1)
                try {
                    val bytes = ByteArray(GoogleRamblerProtocol.MAX_JSON_CHARS * 4 + 1)
                    var count = 0
                    while (!source.exhausted()) {
                        val read = source.readAtMostTo(bytes, count, bytes.size)
                        if (read <= 0) break
                        count += read
                    }
                    if (count > GoogleRamblerProtocol.MAX_JSON_CHARS * 4) invalidResponse()
                    String(bytes, 0, count, Charsets.UTF_8).also {
                        if (it.length > GoogleRamblerProtocol.MAX_JSON_CHARS) invalidResponse()
                    }
                } finally { source.close() }
            } finally { channel.cancel(null) }
            if (response.status.value !in 200..299 && response.status.value !in allowedStatuses) {
                throw GoogleRamblerProvisioningException(GoogleRamblerProtocol.failure(response.status.value, text))
            }
            return Response(response.status.value, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: GoogleRamblerProvisioningException) {
            throw safe
        } catch (_: Exception) {
            throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Unavailable)
        }
    }

    private fun validateProject(project: GoogleRamblerProject) {
        if (!GoogleRamblerProtocol.isValidProject(project)) invalidResponse()
    }

    override fun close() { client.close() }

    private companion object {
        const val RESOURCE_MANAGER = "https://cloudresourcemanager.googleapis.com"
        const val SERVICE_USAGE = "https://serviceusage.googleapis.com"
        const val API_KEYS = "https://apikeys.googleapis.com"
        const val IAM = "https://iam.googleapis.com"
        const val BILLING = "https://cloudbilling.googleapis.com"
        const val USERINFO = "https://openidconnect.googleapis.com/v1/userinfo"
        val PROPAGATION_STATUSES = setOf(404, 503)
        val GOOGLE_HOSTS = setOf("cloudresourcemanager.googleapis.com", "serviceusage.googleapis.com", "apikeys.googleapis.com", "iam.googleapis.com", "cloudbilling.googleapis.com", "openidconnect.googleapis.com")
        fun invalidResponse(): Nothing = throw GoogleRamblerProvisioningException(GoogleRamblerFailure.InvalidResponse)
    }
}

internal class GoogleRamblerProvisioner(
    private val store: RamblerSettingsStore,
    private val client: GoogleRamblerCloudClient,
    private val projectIdFactory: () -> String = {
        "mercury-rambler-" + UUID.randomUUID().toString().replace("-", "").take(12)
    },
) {
    suspend fun provision(
        accessToken: String,
        account: GoogleRamblerAccount,
        onProgress: (GoogleRamblerProvisioningStage) -> Unit = {},
    ): GoogleRamblerConnection {
        try {
            return withTimeout(180_000) {
                if (!GoogleRamblerProtocol.isValidSubject(account.subject)) invalidResponse()
                onProgress(GoogleRamblerProvisioningStage.CheckingProject)
                val checkpoint = store.loadPendingGoogleProject(account.subject)
                val previous = store.loadGoogleConnection()?.takeIf { it.account.subject == account.subject }
                val projectId = checkpoint ?: previous?.project?.projectId ?: projectIdFactory()
                if (!GoogleRamblerProtocol.isOwnedProjectId(projectId)) invalidResponse()
                // Validate the whole account locally before doing any management
                // operation, without ever persisting its short-lived OAuth token.
                GoogleRamblerProtocol.encodeConnection(GoogleRamblerConnection(account, GoogleRamblerProject(projectId, "1")))
                if (checkpoint == null) store.savePendingGoogleProject(account.subject, projectId)
                val project = client.project(accessToken, projectId) {
                    onProgress(GoogleRamblerProvisioningStage.CreatingProject)
                }
                onProgress(GoogleRamblerProvisioningStage.CheckingBilling)
                client.checkNoBilling(accessToken, project)
                onProgress(GoogleRamblerProvisioningStage.EnablingServices)
                client.enableServices(accessToken, project)
                onProgress(GoogleRamblerProvisioningStage.CreatingIdentity)
                val newIdentity = client.ensureServiceAccount(accessToken, project)
                onProgress(GoogleRamblerProvisioningStage.CreatingKey)
                val key = client.restrictedKey(accessToken, project, newIdentity)
                onProgress(GoogleRamblerProvisioningStage.CheckingBilling)
                client.checkNoBilling(accessToken, project)
                onProgress(GoogleRamblerProvisioningStage.Saving)
                val connection = GoogleRamblerConnection(account, project)
                store.saveGoogleKey(key, connection)
                connection
            }
        } catch (_: TimeoutCancellationException) {
            throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Unavailable)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: GoogleRamblerProvisioningException) {
            throw safe
        } catch (_: Exception) {
            throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Unavailable)
        } finally {
            client.close()
        }
    }

    private fun invalidResponse(): Nothing =
        throw GoogleRamblerProvisioningException(GoogleRamblerFailure.InvalidResponse)
}
