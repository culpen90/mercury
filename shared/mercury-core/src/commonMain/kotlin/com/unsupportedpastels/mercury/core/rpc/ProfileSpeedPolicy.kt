package com.unsupportedpastels.mercury.core.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Profile-default speed choices shared by the native settings surfaces. */
object ProfileSpeedPolicy {
    val speeds: List<String> = listOf("normal", "fast")

    /** Normal can always clear a prior tier; Fast requires advertised support. */
    fun isSelectable(speed: String, fastSupported: Boolean): Boolean = when (speed) {
        "normal" -> true
        "fast" -> fastSupported
        else -> false
    }

    /**
     * Read the bare config object returned by the official GET /api/config.
     * Other Hermes modes stay unselected so opening settings cannot misrepresent
     * or replace an existing auto, cold, ultrafast, or future preference.
     */
    fun selectedSpeed(configJson: String): String? {
        val config = runCatching { Json.parseToJsonElement(configJson) as? JsonObject }.getOrNull()
            ?: return null
        val rawAgent = config["agent"] ?: return "normal"
        val agent = rawAgent as? JsonObject ?: return null
        val rawTier = agent["service_tier"]
        if (rawTier == null || rawTier == JsonNull) return "normal"
        val tier = (rawTier as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return when (tier.trim().lowercase()) {
            "", "normal", "default", "standard", "off", "none" -> "normal"
            "fast", "priority", "on" -> "fast"
            else -> null
        }
    }

    /** Minimal merge payload for the official PUT /api/config. */
    fun configUpdate(speed: String): String? {
        if (speed !in speeds) return null
        return buildJsonObject {
            put("config", buildJsonObject {
                put("agent", buildJsonObject { put("service_tier", speed) })
            })
        }.toString()
    }
}
