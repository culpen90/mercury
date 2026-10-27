package com.unsupportedpastels.mercury.core.voice

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class RamblerBetaOperation(val path: String) { Signup("/v1/signup"), Status("/v1/status"), Withdraw("/v1/withdraw") }

/** No server messages or account details are retained in enrollment state. */
enum class RamblerBetaResult {
    Enrolled, NotRegistered, Withdrawn, OAuthNotReady, Authentication,
    Capacity, ConsentRequired, Unavailable, InvalidResponse,
}

object RamblerBetaProtocol {
    const val CONSENT_VERSION = "rambler-beta-v1"
    const val MAX_RESPONSE_CHARS = 1_024
    val IDENTITY_SCOPES = GoogleRamblerProtocol.IDENTITY_SCOPES
    fun endpointOrigin(value: String): String? = runCatching {
        if (value.isEmpty() || value.length > 2_048 || value.any { it.code !in 0x21..0x7e }) return null
        val uri = URI(value)
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.isOpaque || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/") ||
            uri.port !in -1..65_535 || uri.port == 0) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        "https://$host" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    }.getOrNull()

    fun safeIdentityScopes(scopes: Collection<String>): Boolean =
        GoogleRamblerProtocol.hasRequiredScopes(scopes, IDENTITY_SCOPES) &&
            GoogleRamblerProtocol.hasRequiredScopes(IDENTITY_SCOPES, scopes)

    fun signupBody(): String = buildJsonObject { put("consentVersion", CONSENT_VERSION) }.toString()

    fun response(operation: RamblerBetaOperation, status: Int, body: String): RamblerBetaResult {
        if (body.length > MAX_RESPONSE_CHARS) return RamblerBetaResult.InvalidResponse
        if (operation == RamblerBetaOperation.Withdraw && status == 204 && body.isBlank()) {
            return RamblerBetaResult.Withdrawn
        }
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return RamblerBetaResult.InvalidResponse
        fun value(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (status == 200 && operation != RamblerBetaOperation.Withdraw &&
            root.keys == setOf("status") && value("status") == "enrolled") return RamblerBetaResult.Enrolled
        if (root.keys != setOf("error")) return RamblerBetaResult.InvalidResponse
        return when (status to value("error")) {
            404 to "not_registered" -> if (operation == RamblerBetaOperation.Status) {
                RamblerBetaResult.NotRegistered
            } else RamblerBetaResult.InvalidResponse
            503 to "oauth_not_ready" -> RamblerBetaResult.OAuthNotReady
            401 to "auth_required", 401 to "invalid_identity" -> RamblerBetaResult.Authentication
            503 to "capacity_reached" -> RamblerBetaResult.Capacity
            409 to "consent_required" -> RamblerBetaResult.ConsentRequired
            503 to "auth_unavailable", 503 to "unavailable", 404 to "not_found" -> RamblerBetaResult.Unavailable
            else -> RamblerBetaResult.InvalidResponse
        }
    }
}
