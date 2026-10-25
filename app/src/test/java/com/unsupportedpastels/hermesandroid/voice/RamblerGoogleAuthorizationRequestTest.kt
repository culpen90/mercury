package com.unsupportedpastels.hermesandroid.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerProtocol
import com.unsupportedpastels.mercury.core.voice.RamblerBetaProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RamblerGoogleAuthorizationRequestTest {
    @Test fun signupRequestsIdentityOnlyAndExcludesPreviouslyGrantedCloudScopes() {
        val request = ramblerGoogleAuthorizationRequest(identityOnly = true)
        assertEquals(RamblerBetaProtocol.IDENTITY_SCOPES.toSet(), request.requestedScopes.map { it.scopeUri }.toSet())
        assertTrue(request.optOutIncludingGrantedScopes)
    }

    @Test fun cloudSetupStillRequestsItsOwnExplicitCloudGrant() {
        val request = ramblerGoogleAuthorizationRequest(identityOnly = false)
        assertEquals((GoogleRamblerProtocol.IDENTITY_SCOPES + GoogleRamblerProtocol.CLOUD_SCOPE).toSet(),
            request.requestedScopes.map { it.scopeUri }.toSet())
        assertTrue(request.optOutIncludingGrantedScopes)
    }
}
