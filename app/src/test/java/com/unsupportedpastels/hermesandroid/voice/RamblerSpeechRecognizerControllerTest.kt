package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.RamblerFailure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRamblerTransport : RamblerTransport {
    val inbound = Channel<String?>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    var connections = 0
    var closed = false
    var connectFailure: RamblerFailure? = null
    override suspend fun connect(apiKey: String) {
        connections++
        connectFailure?.let { throw RamblerTransportException(it) }
    }
    override suspend fun send(message: String) { sent += message }
    override suspend fun receive(): String? = inbound.receive()
    override suspend fun close() { closed = true }
    fun server(message: String) { inbound.trySend(message).getOrThrow() }
}

private class FakeRamblerMicrophone : RamblerMicrophone {
    val chunks = Channel<ByteArray?>(Channel.UNLIMITED)
    var starts = 0
    var stopped = false
    override suspend fun start(): Boolean { starts++; return true }
    override suspend fun read(): ByteArray? = chunks.receive()
    override fun stop() { stopped = true }
}

private class RamblerHarness(private val scope: TestScope) {
    val transport = FakeRamblerTransport()
    val microphone = FakeRamblerMicrophone()
    var configured = true
    var key: String? = "test-api-key"
    val drafts = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val controller = RamblerSpeechRecognizerController(
        keyLoader = { key },
        keyConfigured = { configured },
        scope = scope,
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        transportFactory = { transport },
        microphoneFactory = { microphone },
        setupTimeoutMillis = 1_000,
        finalFlushTimeoutMillis = 500,
        maxSessionMillis = 5_000,
    )
    fun start(base: String = "existing"): Boolean =
        controller.start(base, drafts::add, errors::add)
    fun ready() { transport.server("{\"setupComplete\":{}}") }
    fun interim(text: String) {
        transport.server("{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"$text\"}}}")
    }
    fun final(text: String) {
        transport.server("{\"serverContent\":{\"inputTranscription\":{\"text\":\"$text\"}}}")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RamblerSpeechRecognizerControllerTest {
    @Test fun missingKeyNeverConnectsOrCaptures() = runTest {
        val h = RamblerHarness(this)
        h.configured = false
        assertFalse(h.start())
        runCurrent()
        assertEquals(0, h.transport.connections)
        assertEquals(0, h.microphone.starts)
        assertFalse(h.controller.isActive)
    }

    @Test fun keyRemovedAfterSettingsSnapshotNeverConnectsOrCaptures() = runTest {
        val h = RamblerHarness(this)
        h.key = null
        assertTrue(h.start())
        runCurrent()
        assertEquals(0, h.transport.connections)
        assertEquals(0, h.microphone.starts)
        assertEquals(1, h.errors.size)
        assertFalse(h.controller.isActive)
    }

    @Test fun microphoneWaitsForSetupAndSmartModelIsSentFirst() = runTest {
        val h = RamblerHarness(this)
        assertTrue(h.start())
        runCurrent()
        assertEquals(0, h.microphone.starts)
        assertEquals(1, h.transport.sent.size)
        assertTrue(h.transport.sent.single().contains("gemini-3.5-transcribe-live"))
        assertTrue(h.transport.sent.single().contains("SMART"))
        h.ready()
        runCurrent()
        assertEquals(1, h.microphone.starts)
        h.controller.cancel()
        runCurrent()
        assertTrue(h.microphone.stopped)
        assertTrue(h.transport.closed)
    }

    @Test fun interimCorrectionsReplacePreviewAndFinalsAccumulateOnlyInDraft() = runTest {
        val h = RamblerHarness(this)
        h.start("  Existing draft  ")
        h.ready()
        runCurrent()
        h.interim("wrong preview")
        h.interim("correct preview")
        h.final("Correct final.")
        h.interim(" Next preview")
        runCurrent()
        assertEquals("  Existing draft  Correct final. Next preview", h.drafts.last())
        assertFalse(h.drafts.last().contains("wrong"))
        assertTrue(h.errors.isEmpty())
        h.controller.cancel()
        runCurrent()
    }

    @Test fun finishStopsMicImmediatelyAndAcceptsOnlyBoundedFinalFlush() = runTest {
        val h = RamblerHarness(this)
        h.start()
        h.ready()
        runCurrent()
        h.interim("early")
        runCurrent()
        h.controller.finish()
        assertTrue(h.microphone.stopped)
        runCurrent()
        assertTrue(h.transport.sent.last().contains("audioStreamEnd"))
        h.final("polished final")
        runCurrent()
        assertEquals("existing polished final", h.drafts.last())
        advanceTimeBy(501)
        runCurrent()
        assertFalse(h.controller.isActive)
        assertTrue(h.transport.closed)
        h.final("stale")
        runCurrent()
        assertEquals("existing polished final", h.drafts.last())
    }

    @Test fun finishFlushTimesOutAndKeepsPreviewWithoutRestartingCapture() = runTest {
        val h = RamblerHarness(this)
        h.start()
        h.ready()
        runCurrent()
        h.interim("keep this")
        runCurrent()
        h.controller.finish()
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        assertEquals("existing keep this", h.drafts.last())
        assertFalse(h.controller.isActive)
        assertEquals(1, h.microphone.starts)
        assertTrue(h.transport.closed)
    }

    @Test fun quotaFailureDuringFinalFlushRemainsAnError() = runTest {
        val h = RamblerHarness(this)
        h.start()
        h.ready()
        runCurrent()
        h.interim("keep this")
        runCurrent()
        h.controller.finish()
        runCurrent()
        h.transport.server("{\"error\":{\"code\":429}}")
        runCurrent()
        assertEquals("existing keep this", h.drafts.last())
        assertEquals(1, h.errors.size)
        assertTrue(h.errors.single().contains("quota", ignoreCase = true))
        assertFalse(h.controller.isActive)
        assertTrue(h.transport.closed)
    }

    @Test fun normalServerCloseDuringFinalFlushCompletesWithoutError() = runTest {
        val h = RamblerHarness(this)
        h.start()
        h.ready()
        runCurrent()
        h.controller.finish()
        runCurrent()
        h.transport.inbound.trySend(null)
        runCurrent()
        assertTrue(h.errors.isEmpty())
        assertFalse(h.controller.isActive)
        assertTrue(h.transport.closed)
    }

    @Test fun cancelRestoresBaseAndDropsLateProviderEvents() = runTest {
        val h = RamblerHarness(this)
        h.start(" untouched ")
        h.ready()
        runCurrent()
        h.interim("preview")
        runCurrent()
        h.controller.cancel()
        h.final("late provider text")
        runCurrent()
        assertEquals(" untouched ", h.drafts.last())
        assertEquals(0, h.errors.size)
        assertTrue(h.transport.closed)
    }

    @Test fun lifecycleAbortPreservesPreviewAndDropsLateProviderEvents() = runTest {
        val h = RamblerHarness(this)
        h.start()
        h.ready()
        runCurrent()
        h.interim("keep this preview")
        runCurrent()
        h.controller.abort()
        assertTrue(h.microphone.stopped)
        h.final("late provider text")
        runCurrent()
        assertEquals("existing keep this preview", h.drafts.last())
        assertEquals(0, h.errors.size)
        assertTrue(h.transport.closed)
    }

    @Test fun turnCompleteBeforeFinalDoesNotDiscardLateSmartFormatting() = runTest {
        val h = RamblerHarness(this)
        h.start()
        h.ready()
        runCurrent()
        h.interim("unpolished preview")
        runCurrent()
        h.controller.finish()
        runCurrent()
        h.transport.server("{\"serverContent\":{\"turnComplete\":true}}")
        runCurrent()
        assertTrue(h.controller.isActive)
        advanceTimeBy(100)
        h.final("Polished final.")
        runCurrent()
        advanceTimeBy(401)
        runCurrent()
        assertEquals("existing Polished final.", h.drafts.last())
        assertFalse(h.controller.isActive)
    }

    @Test fun audioFlowsOnlyAfterReadyAndNeverAfterFinish() = runTest {
        val h = RamblerHarness(this)
        h.start()
        h.microphone.chunks.trySend(byteArrayOf(1, 0, 2, 0))
        runCurrent()
        assertEquals(1, h.transport.sent.size)
        h.ready()
        runCurrent()
        assertEquals(2, h.transport.sent.size)
        assertTrue(h.transport.sent[1].contains("audio/pcm;rate=16000"))
        h.controller.finish()
        runCurrent()
        val count = h.transport.sent.size
        h.microphone.chunks.trySend(byteArrayOf(3, 0))
        runCurrent()
        assertEquals(count, h.transport.sent.size)
        advanceTimeBy(501)
        runCurrent()
    }

    @Test fun finishDuringSetupDoesNotOpenTheMicrophoneLater() = runTest {
        val h = RamblerHarness(this)
        h.start()
        runCurrent()
        h.controller.finish()
        h.ready()
        runCurrent()
        assertEquals(0, h.microphone.starts)
        assertFalse(h.controller.isActive)
    }

    @Test fun quotaFailureIsTerminalAndDoesNotExposeProviderDetail() = runTest {
        val h = RamblerHarness(this)
        h.transport.connectFailure = RamblerFailure.Quota
        h.start()
        runCurrent()
        assertEquals(1, h.errors.size)
        assertTrue(h.errors.single().contains("quota", ignoreCase = true))
        assertFalse(h.errors.single().contains("test-api-key"))
        assertEquals(0, h.microphone.starts)
        assertFalse(h.controller.isActive)
        assertTrue(h.transport.closed)
    }

    @Test fun setupTimeoutNeverCapturesOrSilentlyFallsBack() = runTest {
        val h = RamblerHarness(this)
        h.start()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(0, h.microphone.starts)
        assertEquals(1, h.transport.connections)
        assertEquals(1, h.errors.size)
        assertFalse(h.controller.isActive)
    }

    @Test fun sessionLimitStopsCaptureAndOffersNewExplicitStart() = runTest {
        val h = RamblerHarness(this)
        h.start()
        h.ready()
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(h.microphone.stopped)
        assertEquals(1, h.errors.size)
        assertFalse(h.controller.isActive)
        assertTrue(h.transport.closed)
    }
}
