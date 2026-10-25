package com.unsupportedpastels.mercury.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Safe local categories; remote error messages, details and credentials are never retained. */
enum class RamblerFailure {
    Authentication, Quota, ModelUnavailable, Unavailable, SessionExpired,
    InvalidRequest, MalformedResponse, TranscriptLimit, Remote,
}

sealed interface RamblerEvent {
    data object Ready : RamblerEvent
    data class Interim(val text: String) : RamblerEvent
    data class Final(val text: String) : RamblerEvent
    data object TurnComplete : RamblerEvent
    data class Failure(val reason: RamblerFailure) : RamblerEvent
}

object RamblerProtocol {
    const val MODEL = "gemini-3.5-transcribe-live"
    const val AUDIO_MIME_TYPE = "audio/pcm;rate=16000"
    const val MAX_MESSAGE_CHARS = 65_536
    const val MAX_SEGMENT_CHARS = 4_096
    const val MAX_TRANSCRIPT_CHARS = 131_072
    const val MAX_API_KEY_CHARS = 512
    const val MAX_CLOSE_REASON_CHARS = 2_048

    private val closeStatus = Regex(
        "(?<![A-Z0-9_])(?:RESOURCE_EXHAUSTED|UNAUTHENTICATED|PERMISSION_DENIED|API_KEY_INVALID|" +
            "NOT_FOUND|INVALID_ARGUMENT|DEADLINE_EXCEEDED|SESSION_EXPIRED|UNAVAILABLE)(?![A-Z0-9_])",
    )

    /** Official Live transcription setup; SMART performs Rambler's formatting and corrections. */
    fun setupMessage(): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$MODEL")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("TEXT")) }
            }
            putJsonObject("inputAudioTranscription") { put("mode", "SMART") }
        }
    }.toString()

    /** Caller supplies base64-encoded, little-endian PCM16, mono at 16 kHz. */
    fun audioMessage(base64Pcm: String): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("data", base64Pcm)
                put("mimeType", AUDIO_MIME_TYPE)
            }
        }
    }.toString()

    fun audioStreamEndMessage(): String = buildJsonObject {
        putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
    }.toString()

    /**
     * Decode only input transcription events from the official Live API.
     * Unknown additive fields are ignored. Frame and text limits reject a whole value so
     * Unicode sequences and SMART formatting cannot be silently cut in half.
     */
    fun decode(message: String): List<RamblerEvent> {
        if (message.length > MAX_MESSAGE_CHARS) return failure(RamblerFailure.TranscriptLimit)
        val root = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull()
            ?: return failure(RamblerFailure.MalformedResponse)
        if (root.containsKey("error")) {
            val error = root["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val reason = if (code != null) failureForCode(code) else when (error?.string("status")) {
                "UNAUTHENTICATED", "PERMISSION_DENIED" -> RamblerFailure.Authentication
                "RESOURCE_EXHAUSTED" -> RamblerFailure.Quota
                "NOT_FOUND" -> RamblerFailure.ModelUnavailable
                "INVALID_ARGUMENT", "FAILED_PRECONDITION" -> RamblerFailure.InvalidRequest
                "DEADLINE_EXCEEDED" -> RamblerFailure.SessionExpired
                "INTERNAL", "UNAVAILABLE" -> RamblerFailure.Unavailable
                else -> RamblerFailure.Remote
            }
            return failure(reason)
        }
        if (root["goAway"] is JsonObject) return failure(RamblerFailure.SessionExpired)

        val content = root["serverContent"] as? JsonObject
        val interim = (content?.get("interimInputTranscription") as? JsonObject)?.string("text")
        val final = (content?.get("inputTranscription") as? JsonObject)?.string("text")
        if ((interim?.length ?: 0) > MAX_SEGMENT_CHARS || (final?.length ?: 0) > MAX_SEGMENT_CHARS) {
            return failure(RamblerFailure.TranscriptLimit)
        }
        return buildList {
            if (root["setupComplete"] is JsonObject) add(RamblerEvent.Ready)
            if (interim != null) add(RamblerEvent.Interim(interim))
            if (final != null) add(RamblerEvent.Final(final))
            val complete = (content?.get("turnComplete") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (complete == true) add(RamblerEvent.TurnComplete)
        }
    }

    /** Handles HTTP error codes and the corresponding numeric google.rpc.Code values. */
    fun failureForCode(code: Int?): RamblerFailure = when (code) {
        3, 9, 400 -> RamblerFailure.InvalidRequest
        7, 16, 401, 403 -> RamblerFailure.Authentication
        5, 404 -> RamblerFailure.ModelUnavailable
        8, 429 -> RamblerFailure.Quota
        4 -> RamblerFailure.SessionExpired
        13, 14, 500, 502, 503, 504 -> RamblerFailure.Unavailable
        else -> RamblerFailure.Remote
    }

    /**
     * Classify a provider close reason transiently; never return or retain its text.
     * A WebSocket policy-violation code alone cannot distinguish quota, credentials,
     * and model access. Oversized or unrecognized reasons receive a generic category.
     */
    fun failureForClose(code: Int?, reason: String?): RamblerFailure? {
        if (reason != null && reason.length <= MAX_CLOSE_REASON_CHARS) {
            val status = closeStatus.find(reason.uppercase())?.value
            when (status) {
                "RESOURCE_EXHAUSTED" -> return RamblerFailure.Quota
                "UNAUTHENTICATED", "PERMISSION_DENIED", "API_KEY_INVALID" -> return RamblerFailure.Authentication
                "NOT_FOUND" -> return RamblerFailure.ModelUnavailable
                "INVALID_ARGUMENT" -> return RamblerFailure.InvalidRequest
                "DEADLINE_EXCEEDED", "SESSION_EXPIRED" -> return RamblerFailure.SessionExpired
                "UNAVAILABLE" -> return RamblerFailure.Unavailable
            }
            val normalized = reason.lowercase()
            if ("you exceeded your current quota" in normalized || "quota exceeded" in normalized) {
                return RamblerFailure.Quota
            }
            if ("api key not valid" in normalized || "api key is invalid" in normalized) {
                return RamblerFailure.Authentication
            }
            if ("model" in normalized && "not found" in normalized) {
                return RamblerFailure.ModelUnavailable
            }
            if ("maximum session duration exceeded" in normalized || "session expired" in normalized) {
                return RamblerFailure.SessionExpired
            }
        }
        return if (code == 1000 || code == 1001) null else RamblerFailure.Unavailable
    }

    /** Accept ordinary surrounding spaces, but reject controls and non-ASCII before trimming. */
    fun normalizeApiKey(value: String): String? {
        if (value.any { it.code !in 0x20..0x7e }) return null
        val normalized = value.trim(' ')
        if (normalized.isEmpty() || normalized.length > MAX_API_KEY_CHARS) return null
        return normalized.takeIf { key ->
            key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }
        }
    }

    private fun failure(reason: RamblerFailure): List<RamblerEvent> = listOf(RamblerEvent.Failure(reason))

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * Interim hypotheses replace one preview; authoritative final segments append exactly as sent.
 * Existing draft text stays intact, including its whitespace. Completion never commits speculation.
 */
class RamblerTranscriptAccumulator(private val baseDraft: String) {
    var finalizedText: String = ""
        private set
    var interimText: String = ""
        private set
    var limitReached: Boolean = false
        private set

    val text: String
        get() {
            val dictation = finalizedText + interimText
            if (dictation.isEmpty()) return baseDraft
            val separator = if (baseDraft.isNotEmpty() && !baseDraft.last().isWhitespace() && !dictation.first().isWhitespace()) " " else ""
            return baseDraft + separator + dictation
        }

    fun accept(event: RamblerEvent): String {
        when (event) {
            is RamblerEvent.Interim -> {
                if (canAccept(event.text)) interimText = event.text
            }
            is RamblerEvent.Final -> {
                if (canAccept(event.text)) {
                    finalizedText += event.text
                    interimText = ""
                }
            }
            is RamblerEvent.Failure -> {
                interimText = ""
                if (event.reason == RamblerFailure.TranscriptLimit) limitReached = true
            }
            RamblerEvent.TurnComplete -> interimText = ""
            RamblerEvent.Ready -> Unit
        }
        return text
    }

    private fun canAccept(segment: String): Boolean {
        if (limitReached) return false
        if (segment.length > RamblerProtocol.MAX_SEGMENT_CHARS ||
            finalizedText.length + segment.length > RamblerProtocol.MAX_TRANSCRIPT_CHARS
        ) {
            interimText = ""
            limitReached = true
            return false
        }
        return true
    }
}
