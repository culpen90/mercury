package com.unsupportedpastels.mercury.core.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class ProfileModelSetResult(
    val ok: Boolean,
    val confirmationRequired: Boolean,
    val confirmationMessage: String?,
)

/** Decoding shared by native clients of the official profile model endpoint. */
object ProfileModelPolicy {
    /** Missing flags never imply acceptance; malformed explicit values are rejected. */
    fun setResult(resultJson: String): ProfileModelSetResult? {
        val result = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull()
            ?: return null
        fun flag(key: String): Boolean? {
            if (!result.containsKey(key)) return false
            return (result[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        }
        val ok = flag("ok") ?: return null
        val confirmationRequired = flag("confirm_required") ?: return null
        val rawMessage = result["confirm_message"]
        val message = when {
            rawMessage == null || rawMessage == JsonNull -> null
            rawMessage is JsonPrimitive && rawMessage.isString -> rawMessage.content.take(1_000)
            else -> return null
        }
        return ProfileModelSetResult(ok, confirmationRequired, message)
    }
}
