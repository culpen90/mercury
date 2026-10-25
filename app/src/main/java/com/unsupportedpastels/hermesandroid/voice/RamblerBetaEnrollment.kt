package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.R
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerFailure
import com.unsupportedpastels.mercury.core.voice.RamblerBetaOperation
import com.unsupportedpastels.mercury.core.voice.RamblerBetaProtocol
import com.unsupportedpastels.mercury.core.voice.RamblerBetaResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class RamblerBetaEnrollmentUi(
    val available: Boolean = false,
    val busy: Boolean = false,
    val result: RamblerBetaResult? = null,
    val onJoin: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onWithdraw: () -> Unit = {},
)

internal typealias RamblerBetaEnrollmentFactory = @Composable () -> RamblerBetaEnrollmentUi

/** Missing configuration stops before Google authorization is initialized or requested. */
internal suspend fun performRamblerBetaEnrollment(
    endpoint: String,
    operation: RamblerBetaOperation,
    authorizeIdentity: suspend () -> String,
    clientFactory: (String) -> RamblerBetaClient = { RamblerBetaClient(it) },
): RamblerBetaResult {
    val origin = RamblerBetaProtocol.endpointOrigin(endpoint) ?: return RamblerBetaResult.Unavailable
    val token = authorizeIdentity()
    val client = clientFactory(origin)
    return try { client.request(operation, token) } finally { client.close() }
}

@Composable
private fun rememberRamblerBetaEnrollment(): RamblerBetaEnrollmentUi {
    val inspection = LocalInspectionMode.current
    val configuredEndpoint = if (inspection) "" else stringResource(R.string.rambler_beta_endpoint)
    val endpoint = remember(configuredEndpoint) {
        RamblerBetaProtocol.endpointOrigin(configuredEndpoint)
    } ?: return RamblerBetaEnrollmentUi()
    val authorization = rememberRamblerGoogleAuthorization(identityOnly = true)
    val scope = rememberCoroutineScope()
    var busy by remember(endpoint) { mutableStateOf(false) }
    var result by remember(endpoint) { mutableStateOf<RamblerBetaResult?>(null) }
    fun run(operation: RamblerBetaOperation) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                result = performRamblerBetaEnrollment(endpoint, operation,
                    authorizeIdentity = { authorization.token(allowResolution = true) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GoogleSetupCancelled) {
                // A dismissed account/consent prompt leaves enrollment unchanged.
            } catch (safe: GoogleRamblerProvisioningException) {
                result = if (safe.reason in setOf(GoogleRamblerFailure.Authorization, GoogleRamblerFailure.Permission)) {
                    RamblerBetaResult.Authentication
                } else RamblerBetaResult.Unavailable
            } catch (_: Exception) {
                result = RamblerBetaResult.Unavailable
            } finally {
                busy = false
            }
        }
    }
    return RamblerBetaEnrollmentUi(
        available = true, busy = busy, result = result,
        onJoin = { run(RamblerBetaOperation.Signup) },
        onRefresh = { run(RamblerBetaOperation.Status) },
        onWithdraw = { run(RamblerBetaOperation.Withdraw) },
    )
}

@Composable
internal fun RamblerBetaEnrollmentSection(
    factory: RamblerBetaEnrollmentFactory? = null,
    onWithdrawn: () -> Unit = {},
) {
    val ui = factory?.invoke() ?: rememberRamblerBetaEnrollment()
    val withdrawn by rememberUpdatedState(onWithdrawn)
    LaunchedEffect(ui.result) {
        if (ui.result == RamblerBetaResult.Withdrawn) withdrawn()
    }
    RamblerBetaEnrollmentSection(ui)
}

@Composable
internal fun RamblerBetaEnrollmentSection(ui: RamblerBetaEnrollmentUi) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ui.available) {
            Text("Automatic beta enrollment is temporarily unavailable for this build.")
        } else {
            Text(
                "Joining shares your verified Google email address and account ID with Mercury's beta service, hosted by Cloudflare. No Cloud permissions or microphone audio are shared with this service.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Status checks and leaving the beta use the same Google identity permission.",
                style = MaterialTheme.typography.bodySmall)
            ui.result?.let {
                Text(betaEnrollmentNotice(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (ui.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking beta enrollment…")
            }
            if (ui.result != RamblerBetaResult.Enrolled) {
                Button(onClick = ui.onJoin, enabled = !ui.busy) { Text("Join Rambler beta") }
            }
            TextButton(onClick = ui.onRefresh, enabled = !ui.busy) { Text("Check beta status") }
            if (ui.result == RamblerBetaResult.Enrolled) {
                Text("Leaves the hosted beta and turns off Google account setup on this device. Saved Gemini setup and dictation remain.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = ui.onWithdraw, enabled = !ui.busy) { Text("Leave beta") }
            }
        }
        Text(
            "Beta enrollment is separate from Google's permission to set up Gemini. Accounts already allowed by Google can continue with Google setup below.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun betaEnrollmentNotice(result: RamblerBetaResult): String = when (result) {
    RamblerBetaResult.Enrolled -> "Mercury beta enrollment confirmed."
    RamblerBetaResult.NotRegistered -> "This Google account has not joined the Mercury beta yet."
    RamblerBetaResult.Withdrawn -> "Hosted beta enrollment removed. Saved Gemini setup remains on this device."
    RamblerBetaResult.OAuthNotReady -> "Google authorization for automatic enrollment is not ready yet. Try again later."
    RamblerBetaResult.Authentication -> "Google identity could not be verified. Try joining with Google again."
    RamblerBetaResult.Capacity -> "The beta is full. Try again later."
    RamblerBetaResult.ConsentRequired -> "Review beta signup and join again to confirm consent."
    RamblerBetaResult.Unavailable, RamblerBetaResult.InvalidResponse -> "Beta enrollment is temporarily unavailable. Check your connection and try again."
}
