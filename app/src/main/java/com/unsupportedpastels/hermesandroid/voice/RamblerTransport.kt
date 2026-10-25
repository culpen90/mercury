package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.RamblerFailure
import com.unsupportedpastels.mercury.core.voice.RamblerProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.readReason
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets

/** Dedicated Gemini session; never shares Hermes credentials, cookies, or clients. */
internal interface RamblerTransport {
    suspend fun connect(apiKey: String)
    suspend fun send(message: String)
    suspend fun receive(): String?
    suspend fun close()
}

/** Only the category survives transport failure; provider responses are never retained. */
internal class RamblerTransportException(val reason: RamblerFailure) : Exception()

/** Fixed official endpoint. The API key is sent in a header and never a URL. */
internal const val RAMBLER_WEBSOCKET_URL =
    "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

internal fun ramblerHttpClient(engine: HttpClientEngine = CIO.create()): HttpClient = HttpClient(engine) {
    followRedirects = false
    expectSuccess = true
    install(WebSockets) {
        maxFrameSize = 256 * 1_024L
        pingIntervalMillis = 15_000
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    // Deliberately no logging, cookies, retry, or shared authentication plugins.
}

internal class KtorRamblerTransport(
    private val client: HttpClient = ramblerHttpClient(),
    private val openSession: suspend (HttpClient, String) -> DefaultWebSocketSession = { dedicatedClient, key ->
        dedicatedClient.webSocketSession {
            url(RAMBLER_WEBSOCKET_URL)
            header("x-goog-api-key", key)
        }
    },
) : RamblerTransport {
    private var session: DefaultWebSocketSession? = null

    override suspend fun connect(apiKey: String) {
        val key = RamblerProtocol.normalizeApiKey(apiKey)
            ?: throw RamblerTransportException(RamblerFailure.Authentication)
        try {
            session = openSession(client, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ResponseException) {
            throw RamblerTransportException(RamblerProtocol.failureForCode(error.response.status.value))
        } catch (_: Exception) {
            throw RamblerTransportException(RamblerFailure.Unavailable)
        }
    }

    override suspend fun send(message: String) {
        val active = session ?: throw RamblerTransportException(RamblerFailure.Unavailable)
        try {
            active.send(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw RamblerTransportException(RamblerFailure.Unavailable)
        }
    }

    override suspend fun receive(): String? {
        val active = session ?: return null
        try {
            while (true) {
                val frame = active.incoming.receiveCatching().getOrNull()
                    ?: return classifyClose(active)
                val bytes = when (frame) {
                    is Frame.Text, is Frame.Binary -> frame.data
                    is Frame.Close -> return classifyClose(active, frame.readReason())
                    else -> continue
                }
                // Official Live sockets may send UTF-8 JSON in either frame type.
                if (bytes.size > RamblerProtocol.MAX_MESSAGE_CHARS * 4) {
                    throw RamblerTransportException(RamblerFailure.TranscriptLimit)
                }
                return String(bytes, StandardCharsets.UTF_8)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (bounded: RamblerTransportException) {
            throw bounded
        } catch (_: Exception) {
            throw RamblerTransportException(RamblerFailure.Unavailable)
        }
    }

    private suspend fun classifyClose(
        active: DefaultWebSocketSession,
        frameReason: CloseReason? = null,
    ): String? {
        // Default Ktor sessions consume close frames internally. Read their
        // closeReason as well as explicit frames; otherwise provider quota or
        // authorization rejection could look like a successful final flush.
        val reason = frameReason ?: withTimeoutOrNull(1_000) { active.closeReason.await() }
        val failure = RamblerProtocol.failureForClose(reason?.code?.toInt(), reason?.message)
        if (failure != null) throw RamblerTransportException(failure)
        return null
    }

    override suspend fun close() {
        session?.cancel()
        session = null
        client.close()
    }
}
