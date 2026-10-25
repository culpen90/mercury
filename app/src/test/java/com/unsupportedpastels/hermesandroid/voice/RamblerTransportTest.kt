package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.RamblerFailure
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(io.ktor.utils.io.InternalAPI::class)
private class ClosedRamblerSocket(code: Int, reason: String, explicitFrame: Boolean) : DefaultWebSocketSession {
    override val coroutineContext: CoroutineContext = Job()
    override var masking = false
    override var maxFrameSize = Long.MAX_VALUE
    override var pingIntervalMillis = 0L
    override var timeoutMillis = 0L
    override val closeReason = CompletableDeferred<CloseReason?>()
    override val incoming = Channel<Frame>(Channel.UNLIMITED)
    override val outgoing = Channel<Frame>(Channel.UNLIMITED)
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    init {
        val close = CloseReason(code.toShort(), reason)
        closeReason.complete(close)
        if (explicitFrame) incoming.trySend(Frame.Close(close))
        incoming.close()
    }
    override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
    override suspend fun flush() = Unit
    @Suppress("DEPRECATION") override fun terminate() = Unit
}

class RamblerTransportTest {
    private suspend fun closeFailure(code: Int, reason: String, explicitFrame: Boolean): Throwable? {
        val socket = ClosedRamblerSocket(code, reason, explicitFrame)
        val transport = KtorRamblerTransport(
            client = ramblerHttpClient(MockEngine { respond("") }),
            openSession = { _, _ -> socket },
        )
        return try {
            transport.connect("test-secret")
            runCatching { transport.receive() }.exceptionOrNull()
        } finally { transport.close() }
    }

    @Test fun explicitCloseFrameDoesNotHideQuotaFailure() = runTest {
        val error = closeFailure(1008, "RESOURCE_EXHAUSTED", explicitFrame = true)
        assertTrue(error is RamblerTransportException)
        assertEquals(RamblerFailure.Quota, (error as RamblerTransportException).reason)
        assertNull(error.message)
        assertNull(error.cause)
    }

    @Test fun closedIncomingChannelStillClassifiesAuthenticationFailure() = runTest {
        val error = closeFailure(1008, "UNAUTHENTICATED", explicitFrame = false)
        assertTrue(error is RamblerTransportException)
        assertEquals(RamblerFailure.Authentication, (error as RamblerTransportException).reason)
    }

    @Test fun modelRejectedCloseIsNotReportedAsSuccessfulEnd() = runTest {
        val error = closeFailure(1008, "NOT_FOUND", explicitFrame = true)
        assertTrue(error is RamblerTransportException)
        assertEquals(RamblerFailure.ModelUnavailable, (error as RamblerTransportException).reason)
    }

    @Test fun normalServerCloseHasNoErrorCategory() = runTest {
        assertNull(closeFailure(1000, "", explicitFrame = false))
    }

    @Test fun keyUsesOnlyGoogleHeaderAndRedirectIsNeverFollowed() = runTest {
        var requests = 0
        val engine = object : MockEngine(MockEngineConfig().apply {
            addHandler { request ->
                requests++
                assertEquals("generativelanguage.googleapis.com", request.url.host)
                assertEquals("wss", request.url.protocol.name)
                assertEquals("test-secret", request.headers["x-goog-api-key"])
                assertFalse(request.url.toString().contains("test-secret"))
                assertTrue(request.url.parameters.isEmpty())
                assertNull(request.headers[HttpHeaders.Cookie])
                respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "wss://other.example/"))
            }
        }) {
            override val supportedCapabilities = super.supportedCapabilities + WebSocketCapability
        }
        val transport = KtorRamblerTransport(ramblerHttpClient(engine))
        try {
            runCatching { transport.connect("test-secret") }
            assertEquals(1, requests)
        } finally { transport.close() }
    }

    @Test fun headerControlCharactersAreRejectedBeforeAnyRequest() = runTest {
        var requests = 0
        val engine = MockEngine { requests++; respond("") }
        val transport = KtorRamblerTransport(ramblerHttpClient(engine))
        try {
            val error = runCatching { transport.connect("secret\nInjected: bad") }.exceptionOrNull()
            assertTrue(error is RamblerTransportException)
            assertEquals(RamblerFailure.Authentication, (error as RamblerTransportException).reason)
            assertEquals(0, requests)
            assertNull(error.message)
            assertNull(error.cause)
        } finally { transport.close() }
    }
}
