package com.unsupportedpastels.mercury.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RamblerProtocolTest {
    @Test
    fun setupSelectsTheOfficialDedicatedModelAndSmartTranscription() {
        assertEquals(
            Json.parseToJsonElement(
                """{"setup":{"model":"models/gemini-3.5-transcribe-live","generationConfig":{"responseModalities":["TEXT"]},"inputAudioTranscription":{"mode":"SMART"}}}""",
            ),
            Json.parseToJsonElement(RamblerProtocol.setupMessage()),
        )
    }

    @Test
    fun audioUsesRealtimePcmAndAnExplicitStreamEnd() {
        assertEquals(
            Json.parseToJsonElement("""{"realtimeInput":{"audio":{"data":"AAECAw==","mimeType":"audio/pcm;rate=16000"}}}"""),
            Json.parseToJsonElement(RamblerProtocol.audioMessage("AAECAw==")),
        )
        assertEquals(
            Json.parseToJsonElement("""{"realtimeInput":{"audioStreamEnd":true}}"""),
            Json.parseToJsonElement(RamblerProtocol.audioStreamEndMessage()),
        )
    }

    @Test
    fun setupAcknowledgementAllowsAdditiveFields() {
        assertEquals(
            listOf(RamblerEvent.Ready),
            RamblerProtocol.decode("""{"setupComplete":{},"usageMetadata":{"future":17}}"""),
        )
    }

    @Test
    fun finalTranscriptionFollowsInterimAndPrecedesTurnCompletion() {
        assertEquals(
            listOf(RamblerEvent.Interim("um, Tues—"), RamblerEvent.Final("Wednesday.\n"), RamblerEvent.TurnComplete),
            RamblerProtocol.decode(
                """{"serverContent":{"interimInputTranscription":{"text":"um, Tues—"},"inputTranscription":{"text":"Wednesday.\n","languageCode":"en"},"turnComplete":true,"future":[]}}""",
            ),
        )
    }

    @Test
    fun unknownContentAndMistypedOptionalFieldsNeverBecomeTranscripts() {
        for (message in listOf(
            "{}",
            """{"future":{"text":"not a transcript"}}""",
            """{"setupComplete":true,"serverContent":{"inputTranscription":{"text":23},"interimInputTranscription":[],"turnComplete":"true"}}""",
            """{"serverContent":{"modelTurn":{"parts":[{"text":"not dictation"}]},"outputTranscription":{"text":"not input"}}}""",
        )) {
            assertEquals(emptyList(), RamblerProtocol.decode(message))
        }
    }

    @Test
    fun malformedAndOversizedMessagesFailWithoutRetainingTheirContents() {
        for (message in listOf("", "{", "[]", "null", "42", "\"secret\"")) {
            assertEquals(listOf(RamblerEvent.Failure(RamblerFailure.MalformedResponse)), RamblerProtocol.decode(message))
        }
        assertEquals(
            listOf(RamblerEvent.Failure(RamblerFailure.TranscriptLimit)),
            RamblerProtocol.decode(" ".repeat(RamblerProtocol.MAX_MESSAGE_CHARS + 1)),
        )
    }

    @Test
    fun segmentLimitRejectsTheWholeSegmentWithoutSplittingUnicode() {
        val text = "🙂".repeat(RamblerProtocol.MAX_SEGMENT_CHARS / 2) + "🙂"
        val message = buildJsonObject {
            putJsonObject("serverContent") {
                putJsonObject("inputTranscription") { put("text", text) }
            }
        }.toString()
        assertEquals(listOf(RamblerEvent.Failure(RamblerFailure.TranscriptLimit)), RamblerProtocol.decode(message))
    }

    @Test
    fun remoteErrorsExposeOnlyBoundedCategories() {
        val reasons = mapOf(
            400 to RamblerFailure.InvalidRequest,
            401 to RamblerFailure.Authentication,
            403 to RamblerFailure.Authentication,
            404 to RamblerFailure.ModelUnavailable,
            429 to RamblerFailure.Quota,
            500 to RamblerFailure.Unavailable,
            503 to RamblerFailure.Unavailable,
            999 to RamblerFailure.Remote,
        )
        for ((code, reason) in reasons) {
            assertEquals(reason, RamblerProtocol.failureForCode(code))
            assertEquals(
                listOf(RamblerEvent.Failure(reason)),
                RamblerProtocol.decode("""{"error":{"code":$code,"message":"never retain a key or transcript","details":["sensitive"]}}"""),
            )
        }
        assertEquals(RamblerFailure.Remote, RamblerProtocol.failureForCode(null))
    }

    @Test
    fun errorsTakePrecedenceOverTranscriptionInTheSameMessage() {
        assertEquals(
            listOf(RamblerEvent.Failure(RamblerFailure.Quota)),
            RamblerProtocol.decode("""{"error":{"code":429},"serverContent":{"inputTranscription":{"text":"discard"}}}"""),
        )
    }

    @Test
    fun goAwayProducesClearSessionLimitGuidanceWithoutRetainingServerText() {
        assertEquals(
            listOf(RamblerEvent.Failure(RamblerFailure.SessionExpired)),
            RamblerProtocol.decode("""{"goAway":{"timeLeft":"60s","future":"sensitive"}}"""),
        )
    }

    @Test
    fun normalWebSocketClosuresAreGracefulAndAbnormalCodesDoNotInventAuthenticationErrors() {
        assertNull(RamblerProtocol.failureForClose(1000, null))
        assertNull(RamblerProtocol.failureForClose(1001, "Going away"))
        assertEquals(RamblerFailure.Unavailable, RamblerProtocol.failureForClose(1008, null))
        assertEquals(RamblerFailure.Unavailable, RamblerProtocol.failureForClose(1011, "Internal error"))
        assertEquals(RamblerFailure.Unavailable, RamblerProtocol.failureForClose(null, null))
    }

    @Test
    fun providerCloseStatusesBecomeOnlySafeFailureCategories() {
        val statuses = mapOf(
            "RESOURCE_EXHAUSTED" to RamblerFailure.Quota,
            "UNAUTHENTICATED" to RamblerFailure.Authentication,
            "PERMISSION_DENIED" to RamblerFailure.Authentication,
            "API_KEY_INVALID" to RamblerFailure.Authentication,
            "NOT_FOUND" to RamblerFailure.ModelUnavailable,
            "INVALID_ARGUMENT" to RamblerFailure.InvalidRequest,
            "DEADLINE_EXCEEDED" to RamblerFailure.SessionExpired,
            "SESSION_EXPIRED" to RamblerFailure.SessionExpired,
            "UNAVAILABLE" to RamblerFailure.Unavailable,
        )
        for ((status, expected) in statuses) {
            assertEquals(expected, RamblerProtocol.failureForClose(1008, "Provider status: $status. Sensitive details are discarded."))
        }
    }

    @Test
    fun ordinaryGoogleCloseReasonsIdentifyQuotaKeyModelAndSessionProblems() {
        for ((reason, expected) in mapOf(
            "You exceeded your current quota, please check your plan and billing details." to RamblerFailure.Quota,
            "API key not valid. Please pass a valid API key." to RamblerFailure.Authentication,
            "Model models/gemini-3.5-transcribe-live was not found." to RamblerFailure.ModelUnavailable,
            "Maximum session duration exceeded." to RamblerFailure.SessionExpired,
        )) {
            assertEquals(expected, RamblerProtocol.failureForClose(1011, reason))
        }
    }

    @Test
    fun closeClassificationIsBoundedAndDoesNotMatchLargerUnknownStatusNames() {
        assertEquals(
            RamblerFailure.Unavailable,
            RamblerProtocol.failureForClose(1008, "UNKNOWN_RESOURCE_EXHAUSTED_FUTURE"),
        )
        assertEquals(
            RamblerFailure.Unavailable,
            RamblerProtocol.failureForClose(1008, "x".repeat(RamblerProtocol.MAX_CLOSE_REASON_CHARS) + " RESOURCE_EXHAUSTED"),
        )
    }

    @Test
    fun interimUpdatesReplaceThePreviewAndFinalTextReplacesSpeculation() {
        val accumulator = RamblerTranscriptAccumulator("Existing draft")
        assertEquals("Existing draft", accumulator.text)
        assertEquals("Existing draft Meet on Tues", accumulator.accept(RamblerEvent.Interim("Meet on Tues")))
        assertEquals("Existing draft Meet on Tuesday, no, Wed", accumulator.accept(RamblerEvent.Interim("Meet on Tuesday, no, Wed")))
        assertEquals("Existing draft Meet on Wednesday.", accumulator.accept(RamblerEvent.Final("Meet on Wednesday.")))
        assertEquals("Meet on Wednesday.", accumulator.finalizedText)
        assertEquals("", accumulator.interimText)
    }

    @Test
    fun finalizedSegmentsAndStructuredUnicodeFormattingRemainExact() {
        val accumulator = RamblerTranscriptAccumulator("Draft  \n")
        accumulator.accept(RamblerEvent.Final("\n• Café 👩🏽‍💻\n"))
        accumulator.accept(RamblerEvent.Final("• 你好"))
        accumulator.accept(RamblerEvent.Final("世界\n"))
        assertEquals("Draft  \n\n• Café 👩🏽‍💻\n• 你好世界\n", accumulator.text)
        assertEquals("\n• Café 👩🏽‍💻\n• 你好世界\n", accumulator.finalizedText)
    }

    @Test
    fun existingAndIncomingWhitespaceControlTheDraftBoundary() {
        for ((base, incoming, expected) in listOf(
            Triple("", "hello", "hello"),
            Triple("Draft ", "hello", "Draft hello"),
            Triple("Draft", " hello", "Draft hello"),
            Triple("Draft", "\nhello", "Draft\nhello"),
            Triple("Draft", "hello", "Draft hello"),
        )) {
            assertEquals(expected, RamblerTranscriptAccumulator(base).accept(RamblerEvent.Final(incoming)))
        }
    }

    @Test
    fun completionDiscardsUnfinalizedPreviewAndKeepsFinalSegments() {
        val accumulator = RamblerTranscriptAccumulator("Base")
        accumulator.accept(RamblerEvent.Final("Final. "))
        accumulator.accept(RamblerEvent.Interim("unconfirmed"))
        assertEquals("Base Final. ", accumulator.accept(RamblerEvent.TurnComplete))
        assertEquals("Final. ", accumulator.finalizedText)
    }

    @Test
    fun finalInputCanArriveAfterTurnCompleteWithoutLosingTheDraft() {
        val accumulator = RamblerTranscriptAccumulator("Base")
        accumulator.accept(RamblerEvent.Interim("preview"))
        accumulator.accept(RamblerEvent.TurnComplete)
        assertEquals("Base Corrected final.", accumulator.accept(RamblerEvent.Final("Corrected final.")))
    }

    @Test
    fun remoteFailureKeepsOnlyAuthoritativeText() {
        val accumulator = RamblerTranscriptAccumulator("Base")
        accumulator.accept(RamblerEvent.Final("Final. "))
        accumulator.accept(RamblerEvent.Interim("not confirmed"))
        assertEquals("Base Final. ", accumulator.accept(RamblerEvent.Failure(RamblerFailure.Unavailable)))
        assertFalse(accumulator.limitReached)
    }

    @Test
    fun accumulatorBoundsTheWholeDictationAndRetainsTheLastSafeFinalText() {
        val accumulator = RamblerTranscriptAccumulator("Base")
        val segment = "a".repeat(RamblerProtocol.MAX_SEGMENT_CHARS)
        repeat(RamblerProtocol.MAX_TRANSCRIPT_CHARS / segment.length) { accumulator.accept(RamblerEvent.Final(segment)) }
        assertFalse(accumulator.limitReached)
        val safeText = accumulator.text
        assertEquals(safeText, accumulator.accept(RamblerEvent.Interim("🙂")))
        assertTrue(accumulator.limitReached)
        assertEquals(safeText, accumulator.accept(RamblerEvent.Final("overflow")))
    }

    @Test
    fun accumulatorAlsoRejectsOversizedEventsFromNonJsonCallers() {
        val accumulator = RamblerTranscriptAccumulator("Existing 🙂")
        assertEquals("Existing 🙂", accumulator.accept(RamblerEvent.Final("x".repeat(RamblerProtocol.MAX_SEGMENT_CHARS + 1))))
        assertTrue(accumulator.limitReached)
    }

    @Test
    fun apiKeyNormalizationTrimsOnlyOuterSpacesAndRejectsUnsafeValues() {
        assertEquals("Ab_01-key", RamblerProtocol.normalizeApiKey("  Ab_01-key  "))
        assertEquals("a", RamblerProtocol.normalizeApiKey("a"))
        assertEquals("a".repeat(512), RamblerProtocol.normalizeApiKey("a".repeat(512)))
        for (value in listOf("", "  ", "a b", "a\tb", "a\nb", "a\rb", "key\n", "\tkey", "ékey", "key+", "key=", "a".repeat(513))) {
            assertNull(RamblerProtocol.normalizeApiKey(value))
        }
    }

    @Test
    fun dottedAuthKeysRemainOpaqueWithoutPrefixOrSegmentAssumptions() {
        for (key in listOf("AQ.segment_one.segment-two", "opaque.key.value", "AQ." + "x".repeat(509))) {
            assertEquals(key, RamblerProtocol.normalizeApiKey("  $key  "))
        }
        for (key in listOf("AQ.segment\n", "AQ.segment\r", "AQ.\tsegment", "AQ.seg ment", "AQ.é", "AQ." + "x".repeat(510))) {
            assertNull(RamblerProtocol.normalizeApiKey(key))
        }
    }
}
