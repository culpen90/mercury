package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.RamblerBetaOperation
import com.unsupportedpastels.mercury.core.voice.RamblerBetaProtocol
import com.unsupportedpastels.mercury.core.voice.RamblerBetaResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RamblerBetaClientTest {
    @Test fun missingEndpointStopsBeforeGoogleAuthorizationOrClientCreation() = runTest {
        var authorizations = 0
        val result = performRamblerBetaEnrollment(
            endpoint = "", operation = RamblerBetaOperation.Signup,
            authorizeIdentity = { authorizations++; "unused-test-token" },
            clientFactory = { error("No client should be created") },
        )
        assertEquals(RamblerBetaResult.Unavailable, result)
        assertEquals(0, authorizations)
    }

    @Test fun signupUsesOnlyConfiguredOriginIdentityHeaderAndConsent() = runTest {
        var calls = 0
        val client = RamblerBetaClient("https://beta.example", MockEngine { request ->
            calls++
            assertEquals("beta.example", request.url.host)
            assertEquals("https", request.url.protocol.name)
            assertEquals("/v1/signup", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.parameters.isEmpty())
            assertEquals("Bearer identity-only-test-token", request.headers[HttpHeaders.Authorization])
            assertEquals("MercuryAndroid/0.3.0", request.headers[HttpHeaders.UserAgent])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers["x-goog-api-key"])
            assertEquals(RamblerBetaProtocol.signupBody(), (request.body as TextContent).text)
            respond("""{"status":"enrolled"}""")
        })
        try {
            assertEquals(RamblerBetaResult.Enrolled, client.request(RamblerBetaOperation.Signup, "identity-only-test-token"))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun notReadyNeverConfirmsEnrollment() = runTest {
        val client = RamblerBetaClient("https://beta.example", MockEngine {
            respond("""{"error":"oauth_not_ready"}""", HttpStatusCode.ServiceUnavailable)
        })
        try {
            assertEquals(RamblerBetaResult.OAuthNotReady, client.request(RamblerBetaOperation.Signup, "identity-test-token"))
        } finally { client.close() }
    }

    @Test fun redirectDoesNotReceiveIdentityToken() = runTest {
        var calls = 0
        val client = RamblerBetaClient("https://beta.example", MockEngine {
            calls++
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://elsewhere.example/"))
        })
        try {
            assertEquals(RamblerBetaResult.InvalidResponse, client.request(RamblerBetaOperation.Status, "identity-test-token"))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun unsafeEndpointAndHeaderNeverMakeARequest() = runTest {
        var calls = 0
        val badEndpoint = RamblerBetaClient("https://owner@beta.example", MockEngine { calls++; respond("") })
        val badToken = RamblerBetaClient("https://beta.example", MockEngine { calls++; respond("") })
        try {
            assertEquals(RamblerBetaResult.Unavailable, badEndpoint.request(RamblerBetaOperation.Signup, "test-token"))
            assertEquals(RamblerBetaResult.Authentication, badToken.request(RamblerBetaOperation.Signup, "test\nInjected: token"))
            assertEquals(0, calls)
        } finally { badEndpoint.close(); badToken.close() }
    }

    @Test fun withdrawalRequiresExactNoContentResponse() = runTest {
        val client = RamblerBetaClient("https://beta.example", MockEngine { request ->
            assertEquals("/v1/withdraw", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("{}", (request.body as TextContent).text)
            respond("", HttpStatusCode.NoContent)
        })
        try {
            assertEquals(RamblerBetaResult.Withdrawn, client.request(RamblerBetaOperation.Withdraw, "identity-test-token"))
        } finally { client.close() }
    }

    @Test fun oversizedResponseIsRejectedBeforeStateChanges() = runTest {
        val client = RamblerBetaClient("https://beta.example", MockEngine {
            respond("x".repeat(RamblerBetaProtocol.MAX_RESPONSE_CHARS * 4 + 100))
        })
        try {
            assertEquals(RamblerBetaResult.InvalidResponse, client.request(RamblerBetaOperation.Status, "identity-test-token"))
        } finally { client.close() }
    }
}
