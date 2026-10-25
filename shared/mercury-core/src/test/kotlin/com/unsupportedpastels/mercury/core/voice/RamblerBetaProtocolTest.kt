package com.unsupportedpastels.mercury.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RamblerBetaProtocolTest {
    @Test fun endpointMustBeAnExplicitHttpsOrigin() {
        assertEquals("https://beta.example", RamblerBetaProtocol.endpointOrigin("https://BETA.example/"))
        for (bad in listOf("", "http://beta.example", "https://owner@beta.example", "https://beta.example/v1",
            "https://beta.example?key=secret", "https://beta.example#fragment", "https://beta.example/../",
            "https://beta.example\n", "https://beta.example/%2f", "javascript:alert(1)")) {
            assertNull(RamblerBetaProtocol.endpointOrigin(bad), bad)
        }
    }

    @Test fun identityGrantMustExcludeEveryCloudPermission() {
        assertTrue(RamblerBetaProtocol.safeIdentityScopes(RamblerBetaProtocol.IDENTITY_SCOPES))
        assertFalse(RamblerBetaProtocol.safeIdentityScopes(emptyList()))
        assertFalse(RamblerBetaProtocol.safeIdentityScopes(RamblerBetaProtocol.IDENTITY_SCOPES + GoogleRamblerProtocol.CLOUD_SCOPE))
        assertFalse(RamblerBetaProtocol.safeIdentityScopes(RamblerBetaProtocol.IDENTITY_SCOPES + "https://www.googleapis.com/auth/drive"))
    }

    @Test fun signupRequiresExactVersionedConsent() {
        assertEquals("{\"consentVersion\":\"rambler-beta-v1\"}", RamblerBetaProtocol.signupBody())
    }

    @Test fun onlyExactSuccessConfirmsEnrollment() {
        for (operation in listOf(RamblerBetaOperation.Signup, RamblerBetaOperation.Status)) {
            assertEquals(RamblerBetaResult.Enrolled, RamblerBetaProtocol.response(operation, 200, """{"status":"enrolled"}"""))
            for (body in listOf("", "{}", "null", "{\"status\":true}", "{\"status\":\"pending\"}")) {
                assertEquals(RamblerBetaResult.InvalidResponse, RamblerBetaProtocol.response(operation, 200, body))
            }
        }
    }

    @Test fun readinessCannotBeReportedAsEnrollment() {
        for (operation in listOf(RamblerBetaOperation.Signup, RamblerBetaOperation.Status)) {
            assertEquals(RamblerBetaResult.OAuthNotReady, RamblerBetaProtocol.response(operation, 503, """{"error":"oauth_not_ready"}"""))
        }
    }

    @Test fun notRegisteredIsOnlyAStatusResult() {
        assertEquals(RamblerBetaResult.NotRegistered, RamblerBetaProtocol.response(RamblerBetaOperation.Status, 404, """{"error":"not_registered"}"""))
        assertEquals(RamblerBetaResult.InvalidResponse, RamblerBetaProtocol.response(RamblerBetaOperation.Signup, 404, """{"error":"not_registered"}"""))
    }

    @Test fun withdrawalRequiresNoContentAcknowledgment() {
        assertEquals(RamblerBetaResult.Withdrawn, RamblerBetaProtocol.response(RamblerBetaOperation.Withdraw, 204, ""))
        assertEquals(RamblerBetaResult.InvalidResponse, RamblerBetaProtocol.response(RamblerBetaOperation.Signup, 204, ""))
        assertEquals(RamblerBetaResult.InvalidResponse, RamblerBetaProtocol.response(RamblerBetaOperation.Withdraw, 200, """{"status":"enrolled"}"""))
    }

    @Test fun safeErrorsDoNotExposeRemoteText() {
        assertEquals(RamblerBetaResult.Authentication, RamblerBetaProtocol.response(RamblerBetaOperation.Signup, 401, """{"error":"invalid_identity"}"""))
        assertEquals(RamblerBetaResult.Capacity, RamblerBetaProtocol.response(RamblerBetaOperation.Signup, 503, """{"error":"capacity_reached"}"""))
        assertEquals(RamblerBetaResult.ConsentRequired, RamblerBetaProtocol.response(RamblerBetaOperation.Signup, 409, """{"error":"consent_required"}"""))
        assertEquals(RamblerBetaResult.Unavailable, RamblerBetaProtocol.response(RamblerBetaOperation.Signup, 503, """{"error":"auth_unavailable"}"""))
    }

    @Test fun malformedAndOversizedResponsesStayBounded() {
        for (body in listOf("<html>private account detail</html>", "{broken", "x".repeat(RamblerBetaProtocol.MAX_RESPONSE_CHARS + 1))) {
            assertEquals(RamblerBetaResult.InvalidResponse, RamblerBetaProtocol.response(RamblerBetaOperation.Status, 200, body))
        }
    }
}
