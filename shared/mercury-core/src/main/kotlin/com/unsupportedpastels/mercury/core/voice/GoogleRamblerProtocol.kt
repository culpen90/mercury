package com.unsupportedpastels.mercury.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class GoogleRamblerAccount(val subject: String, val email: String)
data class GoogleRamblerProject(val projectId: String, val number: String)
data class GoogleRamblerConnection(val account: GoogleRamblerAccount, val project: GoogleRamblerProject)
data class GoogleRamblerOperation(val name: String, val done: Boolean, val failure: GoogleRamblerFailure?)

enum class GoogleRamblerFailure {
    Authorization, Permission, Terms, Quota, BillingRequired, BillingLinked,
    Unavailable, InvalidResponse, DeveloperConfiguration,
}

enum class GoogleRamblerService { ResourceManager, ServiceUsage, ApiKeys }

/**
 * Deterministic guards for Google's public Cloud provisioning APIs; never enables billing.
 *
 * Project fields: https://docs.cloud.google.com/resource-manager/reference/rest/v3/projects
 * Auth keys: https://docs.cloud.google.com/docs/authentication/api-keys#rest
 * Billing: https://docs.cloud.google.com/billing/docs/reference/rest/v1/ProjectBillingInfo
 * Operations: https://docs.cloud.google.com/service-usage/docs/reference/rest/v1/Operation
 */
object GoogleRamblerProtocol {
    const val CLOUD_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
    val IDENTITY_SCOPES: List<String> = listOf("openid", "https://www.googleapis.com/auth/userinfo.email")
    const val GEMINI_SERVICE = "generativelanguage.googleapis.com"
    const val KEY_ID = "mercury-rambler"
    const val MAX_JSON_CHARS = 65_536
    const val MAX_CONNECTION_CHARS = 8_192
    const val MAX_SUBJECT_CHARS = 255
    const val MAX_EMAIL_CHARS = 254
    const val MAX_OPERATION_NAME_CHARS = 256

    private val randomSuffix = Regex("[0-9a-f]{12}")
    private val ownedProjectId = Regex("mercury-rambler-[0-9a-f]{12}")
    private val projectNumber = Regex("[1-9][0-9]{0,18}")
    private val localOperation = Regex("operations/[A-Za-z0-9][A-Za-z0-9._-]*")
    private val billingAccountName = Regex("billingAccounts/[A-Za-z0-9-]{1,128}")

    fun newProjectId(randomHex12: String): String {
        require(randomSuffix.matches(randomHex12))
        return "mercury-rambler-$randomHex12"
    }

    fun isOwnedProjectId(projectId: String): Boolean = ownedProjectId.matches(projectId)

    fun isValidProject(project: GoogleRamblerProject): Boolean = validProject(project)

    fun isValidSubject(subject: String): Boolean =
        subject.length in 1..MAX_SUBJECT_CHARS && subject.all { it.code in 0x21..0x7e }

    fun projectCreateBody(id: String): String {
        require(isOwnedProjectId(id))
        return buildJsonObject {
            put("projectId", id)
            put("displayName", "Mercury Rambler")
            putJsonObject("labels") { put("mercury-feature", "rambler") }
        }.toString()
    }

    fun enableServicesBody(): String = buildJsonObject {
        putJsonArray("serviceIds") {
            add(JsonPrimitive(GEMINI_SERVICE))
            add(JsonPrimitive("apikeys.googleapis.com"))
            add(JsonPrimitive("iam.googleapis.com"))
        }
    }.toString()

    /** OpenID Connect userinfo, not an unverified email returned by an arbitrary sign-in UI. */
    fun parseAccount(json: String): GoogleRamblerAccount? {
        val root = objectFrom(json) ?: return null
        if (root.boolean("email_verified") != true) return null
        val subject = root.string("sub") ?: return null
        val email = root.string("email") ?: return null
        return GoogleRamblerAccount(subject, email).takeIf(::validAccount)
    }

    fun parseProject(json: String, expectedId: String): GoogleRamblerProject? {
        if (!isOwnedProjectId(expectedId)) return null
        val root = objectFrom(json) ?: return null
        if (root.string("projectId") != expectedId || root.string("state") != "ACTIVE") return null
        if ((root["labels"] as? JsonObject)?.string("mercury-feature") != "rambler") return null
        val name = root.string("name") ?: return null
        if (!name.startsWith("projects/")) return null
        val number = name.removePrefix("projects/")
        return GoogleRamblerProject(expectedId, number).takeIf(::validProject)
    }

    /** Default false is accepted only with both exact resource identifiers; linkage fails closed. */
    fun billingDisabled(json: String, expectedProjectId: String? = null): Boolean =
        billingState(json, expectedProjectId) == null

    /** Unknown state blocks use without incorrectly claiming that an account is linked. */
    fun billingFailure(json: String, expectedProjectId: String): GoogleRamblerFailure? =
        billingState(json, expectedProjectId)

    private fun billingState(json: String, expectedProjectId: String?): GoogleRamblerFailure? {
        if (expectedProjectId != null && !isOwnedProjectId(expectedProjectId)) return GoogleRamblerFailure.InvalidResponse
        val root = objectFrom(json) ?: return GoogleRamblerFailure.InvalidResponse
        if (expectedProjectId != null) {
            if (root.containsKey("projectId") && root.string("projectId") != expectedProjectId) return GoogleRamblerFailure.InvalidResponse
            if (root.containsKey("name") && root.string("name") != "projects/$expectedProjectId/billingInfo") return GoogleRamblerFailure.InvalidResponse
        }
        val enabled = if (root.containsKey("billingEnabled")) {
            root.boolean("billingEnabled") ?: return GoogleRamblerFailure.InvalidResponse
        } else null
        val accountName = if (root.containsKey("billingAccountName")) {
            root.string("billingAccountName") ?: return GoogleRamblerFailure.InvalidResponse
        } else ""
        if (accountName.isNotEmpty() && !billingAccountName.matches(accountName)) return GoogleRamblerFailure.InvalidResponse
        if (enabled == true || accountName.isNotEmpty()) return GoogleRamblerFailure.BillingLinked
        // ProjectBillingInfo.billing_enabled is a proto3 bool with implicit
        // presence, so valid JSON can omit its default false value. Require
        // both expected output identifiers before accepting that default.
        // https://protobuf.dev/programming-guides/json/#presence-and-default-values
        val scopedDefault = enabled == null && expectedProjectId != null &&
            root.string("projectId") == expectedProjectId &&
            root.string("name") == "projects/$expectedProjectId/billingInfo"
        return if (enabled == false || scopedDefault) null else GoogleRamblerFailure.InvalidResponse
    }

    fun serviceAccountEmail(projectId: String): String {
        require(isOwnedProjectId(projectId))
        return "$KEY_ID@$projectId.iam.gserviceaccount.com"
    }

    fun serviceAccountCreateBody(): String = buildJsonObject {
        put("accountId", KEY_ID)
        putJsonObject("serviceAccount") { put("displayName", "Mercury Rambler") }
    }.toString()

    fun parseServiceAccount(json: String, expectedProjectId: String): Boolean {
        if (!isOwnedProjectId(expectedProjectId)) return false
        val root = objectFrom(json) ?: return false
        val email = serviceAccountEmail(expectedProjectId)
        if (root.string("email") != email || root.string("projectId") != expectedProjectId) return false
        if (root.containsKey("disabled") && root.boolean("disabled") != false) return false
        if (root.containsKey("name") && root.string("name") != "projects/$expectedProjectId/serviceAccounts/$email") return false
        return true
    }

    fun authKeyCreateBody(projectId: String): String = buildJsonObject {
        put("displayName", "Mercury Rambler")
        put("serviceAccountEmail", serviceAccountEmail(projectId))
        putJsonObject("restrictions") {
            putJsonArray("apiTargets") {
                add(buildJsonObject { put("service", GEMINI_SERVICE) })
            }
        }
    }.toString()

    fun parseAuthKeyName(json: String, project: GoogleRamblerProject): String? {
        if (!validProject(project)) return null
        val root = objectFrom(json) ?: return null
        val expectedName = "projects/${project.number}/locations/global/keys/$KEY_ID"
        if (root.string("name") != expectedName || root.string("serviceAccountEmail") != serviceAccountEmail(project.projectId)) return null
        val restrictions = root["restrictions"] as? JsonObject ?: return null
        val targets = restrictions["apiTargets"] as? JsonArray ?: return null
        if (targets.size != 1) return null
        val target = targets.single() as? JsonObject ?: return null
        if (target.string("service") != GEMINI_SERVICE) return null
        if (target.containsKey("methods") && (target["methods"] as? JsonArray)?.isEmpty() != true) return null
        if (root.containsKey("deleteTime")) return null
        return expectedName
    }

    fun parseKeyString(json: String): String? = objectFrom(json)?.string("keyString")?.let(RamblerProtocol::normalizeApiKey)

    /** The adapter chooses a fixed Google host; server-controlled names can never supply a URL. */
    fun isAllowedOperationName(service: GoogleRamblerService, name: String): Boolean {
        if (name.length > MAX_OPERATION_NAME_CHARS) return false
        return when (service) {
            GoogleRamblerService.ResourceManager,
            GoogleRamblerService.ServiceUsage,
            GoogleRamblerService.ApiKeys -> localOperation.matches(name)
        }
    }

    /** Pending protobuf operations may omit the default false done field. */
    fun parseOperation(json: String, service: GoogleRamblerService): GoogleRamblerOperation? {
        val root = objectFrom(json) ?: return null
        val name = root.string("name")?.takeIf { isAllowedOperationName(service, it) } ?: return null
        val done = if (root.containsKey("done")) root.boolean("done") ?: return null else false
        val error = if (root.containsKey("error")) root["error"] as? JsonObject ?: return null else null
        if (error != null && (!done || root.containsKey("response"))) return null
        if (!done && root.containsKey("response")) return null
        val reason = error?.let {
            val code = it.integer("code") ?: return null
            if (code == 0) return null
            errorInfoFailure(it) ?: when (code) {
                16 -> GoogleRamblerFailure.Authorization
                7 -> GoogleRamblerFailure.Permission
                8 -> GoogleRamblerFailure.Quota
                3 -> GoogleRamblerFailure.DeveloperConfiguration
                else -> GoogleRamblerFailure.Unavailable
            }
        }
        return GoogleRamblerOperation(name, done, reason)
    }

    /** Error messages and metadata are inspected neither for display nor for persistence. */
    fun failure(httpCode: Int, body: String?): GoogleRamblerFailure {
        val error = body?.let { objectFrom(it) }?.get("error") as? JsonObject
        if (error != null) {
            errorInfoFailure(error)?.let { return it }
            when (error.string("status")) {
                "UNAUTHENTICATED" -> return GoogleRamblerFailure.Authorization
                "PERMISSION_DENIED" -> return GoogleRamblerFailure.Permission
                "RESOURCE_EXHAUSTED" -> return GoogleRamblerFailure.Quota
                "INVALID_ARGUMENT" -> return GoogleRamblerFailure.DeveloperConfiguration
                "INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED" -> return GoogleRamblerFailure.Unavailable
            }
        }
        return when (httpCode) {
            401 -> GoogleRamblerFailure.Authorization
            403 -> GoogleRamblerFailure.Permission
            429 -> GoogleRamblerFailure.Quota
            400 -> GoogleRamblerFailure.DeveloperConfiguration
            in 200..299 -> GoogleRamblerFailure.InvalidResponse
            else -> GoogleRamblerFailure.Unavailable
        }
    }

    fun encodeConnection(connection: GoogleRamblerConnection): String {
        require(validAccount(connection.account) && validProject(connection.project))
        return buildJsonObject {
            put("version", 1)
            putJsonObject("account") {
                put("subject", connection.account.subject)
                put("email", connection.account.email)
            }
            putJsonObject("project") {
                put("projectId", connection.project.projectId)
                put("number", connection.project.number)
            }
        }.toString()
    }

    fun parseConnection(json: String): GoogleRamblerConnection? {
        val root = objectFrom(json, MAX_CONNECTION_CHARS) ?: return null
        if (root.integer("version") != 1) return null
        val account = root["account"] as? JsonObject ?: return null
        val project = root["project"] as? JsonObject ?: return null
        val connection = GoogleRamblerConnection(
            GoogleRamblerAccount(account.string("subject") ?: return null, account.string("email") ?: return null),
            GoogleRamblerProject(project.string("projectId") ?: return null, project.string("number") ?: return null),
        )
        return connection.takeIf { validAccount(it.account) && validProject(it.project) }
    }

    private fun validProject(project: GoogleRamblerProject): Boolean =
        isOwnedProjectId(project.projectId) && projectNumber.matches(project.number) && project.number.toLongOrNull() != null

    private fun validAccount(account: GoogleRamblerAccount): Boolean {
        if (!isValidSubject(account.subject) || account.email.length !in 3..MAX_EMAIL_CHARS) return false
        if (account.email.any { it.code !in 0x21..0x7e }) return false
        val parts = account.email.split('@')
        if (parts.size != 2 || parts[0].isEmpty() || parts[0].length > 64 || parts[1].isEmpty()) return false
        if (parts[0].any { !(it.isLetterOrDigit() || it in ".!#$%&'*+/=?^_`{|}~-") }) return false
        return parts[1].split('.').all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private fun errorInfoFailure(error: JsonObject): GoogleRamblerFailure? {
        val details = error["details"] as? JsonArray ?: return null
        val reasons = details.mapNotNull { detail ->
            (detail as? JsonObject)?.takeIf { it.string("@type") == "type.googleapis.com/google.rpc.ErrorInfo" }?.string("reason")
        }.toSet()
        return when {
            reasons.any { it in setOf("BILLING_DISABLED", "BILLING_REQUIRED", "PROJECT_BILLING_DISABLED", "BILLING_NOT_ACTIVE", "BILLING_ACCOUNT_DISABLED", "BILLING_ACCOUNT_CLOSED") } -> GoogleRamblerFailure.BillingRequired
            reasons.any { it in setOf("TOS_NOT_ACCEPTED", "TERMS_OF_SERVICE_NOT_ACCEPTED", "TERMS_NOT_ACCEPTED", "TERMS_OF_SERVICE_REQUIRED") } -> GoogleRamblerFailure.Terms
            reasons.any { it in setOf("ACCESS_TOKEN_SCOPE_INSUFFICIENT", "ACCESS_TOKEN_EXPIRED", "CREDENTIALS_MISSING", "CREDENTIALS_INVALID", "AUTHENTICATION_ERROR") } -> GoogleRamblerFailure.Authorization
            reasons.any { it in setOf("RATE_LIMIT_EXCEEDED", "QUOTA_EXCEEDED", "RESOURCE_EXHAUSTED") } -> GoogleRamblerFailure.Quota
            reasons.any { it in setOf("SERVICE_DISABLED", "CLIENT_ID_INVALID", "OAUTH_CLIENT_INVALID") } -> GoogleRamblerFailure.DeveloperConfiguration
            else -> null
        }
    }

    private fun objectFrom(value: String, limit: Int = MAX_JSON_CHARS): JsonObject? {
        if (value.length > limit) return null
        return runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonObject.integer(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
