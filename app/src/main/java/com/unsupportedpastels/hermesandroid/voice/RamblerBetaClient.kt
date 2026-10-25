package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.RamblerBetaOperation
import com.unsupportedpastels.mercury.core.voice.RamblerBetaProtocol
import com.unsupportedpastels.mercury.core.voice.RamblerBetaResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException

/** Enrollment uses an identity-only grant and never shares Hermes or Cloud credentials. */
internal class RamblerBetaClient(
    endpoint: String,
    engine: HttpClientEngine = CIO.create(),
) : AutoCloseable {
    private val origin = RamblerBetaProtocol.endpointOrigin(endpoint)
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        // No shared auth, cookies, logging, redirects, or automatic retries.
    }

    suspend fun request(operation: RamblerBetaOperation, identityToken: String): RamblerBetaResult {
        val target = origin ?: return RamblerBetaResult.Unavailable
        if (identityToken.isEmpty() || identityToken.length > 8_192 || identityToken.any { it.code !in 0x21..0x7e }) {
            return RamblerBetaResult.Authentication
        }
        return try {
            val response = client.request(target + operation.path) {
                method = if (operation == RamblerBetaOperation.Status) HttpMethod.Get else HttpMethod.Post
                headers.append(HttpHeaders.UserAgent, "MercuryAndroid/0.3.0")
                bearerAuth(identityToken)
                if (method == HttpMethod.Post) {
                    contentType(ContentType.Application.Json)
                    setBody(if (operation == RamblerBetaOperation.Signup) RamblerBetaProtocol.signupBody() else "{}")
                }
            }
            val channel = response.bodyAsChannel()
            val body = try {
                val limit = RamblerBetaProtocol.MAX_RESPONSE_CHARS * 4
                val source = channel.readRemaining(limit + 1L)
                try {
                    val bytes = ByteArray(limit + 1)
                    var count = 0
                    while (!source.exhausted()) {
                        val read = source.readAtMostTo(bytes, count, bytes.size)
                        if (read <= 0) break
                        count += read
                    }
                    if (count > limit) return RamblerBetaResult.InvalidResponse
                    String(bytes, 0, count, Charsets.UTF_8)
                } finally { source.close() }
            } finally { channel.cancel(null) }
            RamblerBetaProtocol.response(operation, response.status.value, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RamblerBetaResult.Unavailable
        }
    }

    override fun close() { client.close() }
}
