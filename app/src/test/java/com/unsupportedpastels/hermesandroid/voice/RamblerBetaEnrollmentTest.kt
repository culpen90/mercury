package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.mercury.core.voice.RamblerBetaResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
class RamblerBetaEnrollmentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun joinIsExplicitAndDescribesIdentitySharing() {
        var joins = 0
        rule.setContent {
            RamblerBetaEnrollmentSection(RamblerBetaEnrollmentUi(available = true, onJoin = { joins++ }))
        }
        rule.onNodeWithText("Join Rambler beta").assertIsDisplayed().performClick()
        rule.onNodeWithText("Joining shares your verified Google email address and account ID with Mercury's beta service, hosted by Cloudflare. No Cloud permissions or microphone audio are shared with this service.")
            .assertIsDisplayed()
        assertEquals(1, joins)
    }

    @Test fun missingConfigurationDoesNotOfferFakeSignup() {
        rule.setContent { RamblerBetaEnrollmentSection(RamblerBetaEnrollmentUi()) }
        rule.onNodeWithText("Automatic beta enrollment is temporarily unavailable for this build.").assertIsDisplayed()
        rule.onNodeWithText("Join Rambler beta").assertDoesNotExist()
        rule.onNodeWithText("Mercury beta enrollment confirmed.").assertDoesNotExist()
    }

    @Test fun googleReadinessFailureDoesNotBecomeApproval() {
        rule.setContent {
            RamblerBetaEnrollmentSection(RamblerBetaEnrollmentUi(available = true, result = RamblerBetaResult.OAuthNotReady))
        }
        rule.onNodeWithText("Google authorization for automatic enrollment is not ready yet. Try again later.").assertIsDisplayed()
        rule.onNodeWithText("Mercury beta enrollment confirmed.").assertDoesNotExist()
    }

    @Test fun confirmedEnrollmentHasStatusAndWithdrawalActions() {
        var checks = 0
        var withdrawals = 0
        rule.setContent {
            RamblerBetaEnrollmentSection(RamblerBetaEnrollmentUi(
                available = true, result = RamblerBetaResult.Enrolled,
                onRefresh = { checks++ }, onWithdraw = { withdrawals++ },
            ))
        }
        rule.onNodeWithText("Mercury beta enrollment confirmed.").assertIsDisplayed()
        rule.onNodeWithText("Check beta status").performClick()
        rule.onNodeWithText("Leave beta").performClick()
        assertEquals(1, checks)
        assertEquals(1, withdrawals)
    }

    @Test fun inProgressEnrollmentPreventsDuplicateActions() {
        rule.setContent {
            RamblerBetaEnrollmentSection(RamblerBetaEnrollmentUi(available = true, busy = true))
        }
        rule.onNodeWithText("Join Rambler beta").assertIsNotEnabled()
        rule.onNodeWithText("Check beta status").assertIsNotEnabled()
    }
}
