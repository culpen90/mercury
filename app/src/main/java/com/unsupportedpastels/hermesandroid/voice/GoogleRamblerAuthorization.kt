package com.unsupportedpastels.hermesandroid.voice

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerFailure
import com.unsupportedpastels.mercury.core.voice.GoogleRamblerProtocol
import com.unsupportedpastels.mercury.core.voice.RamblerBetaProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google Play services owns token refresh; Mercury never stores OAuth tokens. */
internal interface RamblerGoogleAuthorization {
    suspend fun token(accountEmail: String? = null, allowResolution: Boolean): String
}

internal fun ramblerGoogleAuthorizationRequest(identityOnly: Boolean, accountEmail: String? = null): AuthorizationRequest =
    AuthorizationRequest.builder()
        .setRequestedScopes((if (identityOnly) GoogleRamblerProtocol.IDENTITY_SCOPES else
            GoogleRamblerProtocol.IDENTITY_SCOPES + GoogleRamblerProtocol.CLOUD_SCOPE).map(::Scope))
        .setOptOutIncludingGrantedScopes(true)
        .apply { if (accountEmail != null) setAccount(Account(accountEmail, "com.google")) }
        .build()

internal class GoogleSetupCancelled : Exception()

private class PlayServicesRamblerAuthorization(
    context: Context,
    private val identityOnly: Boolean = false,
    private val resolve: suspend (PendingIntent) -> ActivityResult,
) : RamblerGoogleAuthorization {
    private val client by lazy(LazyThreadSafetyMode.NONE) { Identity.getAuthorizationClient(context) }
    private val scopes = if (identityOnly) GoogleRamblerProtocol.IDENTITY_SCOPES else
        (GoogleRamblerProtocol.IDENTITY_SCOPES + GoogleRamblerProtocol.CLOUD_SCOPE).distinct()

    override suspend fun token(accountEmail: String?, allowResolution: Boolean): String {
        val mode = if (identityOnly) "identity" else "cloud"
        var stage = "authorize"
        try {
            Log.i("RamblerAuth", "mode=$mode stage=$stage")
            val request = ramblerGoogleAuthorizationRequest(identityOnly, accountEmail)
            val initial = client.authorize(request).awaitGoogleTask()
            Log.i("RamblerAuth", "mode=$mode stage=initial resolution=${initial.hasResolution()}")
            val result = if (initial.hasResolution()) {
                if (!allowResolution) throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Authorization)
                val intent = initial.pendingIntent
                    ?: throw GoogleRamblerProvisioningException(GoogleRamblerFailure.InvalidResponse)
                stage = "resolution"
                val returned = resolve(intent)
                Log.i("RamblerAuth", "mode=$mode stage=$stage result=${returned.resultCode}")
                if (returned.resultCode != Activity.RESULT_OK) throw GoogleSetupCancelled()
                stage = "decode"
                client.getAuthorizationResultFromIntent(returned.data)
            } else initial
            stage = "scopes"
            val granted = result.grantedScopes
            val knownScopes = setOf("openid", "email", "https://www.googleapis.com/auth/userinfo.email",
                "profile", "https://www.googleapis.com/auth/userinfo.profile", GoogleRamblerProtocol.CLOUD_SCOPE)
            Log.i("RamblerAuth", "mode=$mode stage=$stage openid=${"openid" in granted}" +
                " emailUri=${"https://www.googleapis.com/auth/userinfo.email" in granted} emailShort=${"email" in granted}" +
                " profileUri=${"https://www.googleapis.com/auth/userinfo.profile" in granted} profileShort=${"profile" in granted}" +
                " cloud=${GoogleRamblerProtocol.CLOUD_SCOPE in granted} other=${granted.count { it !in knownScopes }}")
            if (!GoogleRamblerProtocol.hasRequiredScopes(result.grantedScopes, scopes) ||
                (identityOnly && !RamblerBetaProtocol.safeIdentityScopes(result.grantedScopes))) {
                throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Permission)
            }
            stage = "token"
            return result.accessToken?.takeIf { token ->
                token.isNotEmpty() && token.length <= 8_192 && token.all { it.code in 0x21..0x7e }
            } ?: throw GoogleRamblerProvisioningException(GoogleRamblerFailure.InvalidResponse)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: GoogleSetupCancelled) {
            throw cancelled
        } catch (safe: GoogleRamblerProvisioningException) {
            Log.i("RamblerAuth", "mode=$mode stage=$stage reason=${safe.reason}")
            throw safe
        } catch (error: ApiException) {
            Log.i("RamblerAuth", "mode=$mode stage=$stage status=${error.statusCode}")
            if (error.statusCode == 16) throw GoogleSetupCancelled()
            throw GoogleRamblerProvisioningException(
                GoogleRamblerProtocol.authorizationFailure(error.statusCode),
            )
        } catch (_: Exception) {
            throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Unavailable)
        }
    }
}

private suspend fun <T> Task<T>.awaitGoogleTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { failure -> if (continuation.isActive) continuation.resumeWithException(failure) }
    addOnCanceledListener { continuation.cancel() }
}

@Composable
internal fun rememberRamblerGoogleAuthorization(identityOnly: Boolean = false): RamblerGoogleAuthorization {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<CompletableDeferred<ActivityResult>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pending?.takeIf { it.isActive }?.complete(result)
    }
    DisposableEffect(Unit) {
        onDispose { pending?.cancel(); pending = null }
    }
    return remember(context, launcher, identityOnly) {
        PlayServicesRamblerAuthorization(context, identityOnly) { intent ->
            val request = CompletableDeferred<ActivityResult>()
            check(pending == null)
            pending = request
            try {
                launcher.launch(IntentSenderRequest.Builder(intent).build())
                request.await()
            } finally {
                if (pending === request) pending = null
            }
        }
    }
}

@Composable
internal fun rememberRamblerGoogleSetup(
    store: RamblerSettingsStore,
    onConfigured: () -> Unit,
): RamblerGoogleSetupUi {
    val scope = rememberCoroutineScope()
    val authorization = rememberRamblerGoogleAuthorization()
    val configured by rememberUpdatedState(onConfigured)
    var busy by remember(store) { mutableStateOf(false) }
    var status by remember(store) { mutableStateOf<String?>(null) }
    var error by remember(store) { mutableStateOf<String?>(null) }
    return RamblerGoogleSetupUi(busy, status, error) {
        if (!busy) {
            busy = true
            error = null
            status = "Connecting to Google…"
            scope.launch {
                var diagnosticStep = "Authorization"
                try {
                    val token = authorization.token(allowResolution = true)
                    val client = GoogleRamblerCloudClient()
                    try {
                        diagnosticStep = "Identity"
                        val account = client.account(token)
                        GoogleRamblerProvisioner(store, client).provision(token, account) { stage ->
                            diagnosticStep = stage.name
                            status = googleProvisioningStatus(stage)
                        }
                    } finally {
                        client.close()
                    }
                    configured()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: GoogleSetupCancelled) {
                    // Closing Google's consent UI leaves configuration untouched.
                } catch (safe: GoogleRamblerProvisioningException) {
                    // Only fixed local step/reason names; never provider text or exceptions.
                    Log.i("RamblerSetup", "step=$diagnosticStep reason=${safe.reason.name}")
                    error = googleRamblerNotice(safe.reason)
                } catch (_: Exception) {
                    Log.i("RamblerSetup", "step=$diagnosticStep reason=Unavailable")
                    error = googleRamblerNotice(GoogleRamblerFailure.Unavailable)
                } finally {
                    busy = false
                    status = null
                }
            }
        }
    }
}

internal fun googleProvisioningStatus(stage: GoogleRamblerProvisioningStage): String = when (stage) {
    GoogleRamblerProvisioningStage.CheckingProject -> "Checking Google setup…"
    GoogleRamblerProvisioningStage.CreatingProject -> "Creating your dictation project…"
    GoogleRamblerProvisioningStage.CheckingBilling -> "Checking that billing is disabled…"
    GoogleRamblerProvisioningStage.EnablingServices -> "Enabling Gemini dictation…"
    GoogleRamblerProvisioningStage.CreatingIdentity -> "Securing Google access…"
    GoogleRamblerProvisioningStage.CreatingKey -> "Creating your Gemini key…"
    GoogleRamblerProvisioningStage.Saving -> "Saving dictation settings…"
}

internal fun googleRamblerNotice(reason: GoogleRamblerFailure): String = when (reason) {
    GoogleRamblerFailure.Authorization -> "Reconnect Google in Voice settings to continue setting up free dictation"
    GoogleRamblerFailure.Permission -> "Google didn't grant the permissions needed to set up dictation"
    GoogleRamblerFailure.Terms -> "Review Google's terms in Google AI Studio, then try Google setup again"
    GoogleRamblerFailure.Quota -> "Google's project or API quota was reached. Try again later"
    GoogleRamblerFailure.BillingRequired -> "Google requires billing for automatic setup on this account. Mercury hasn't enabled it. You can use a free-tier key from Google AI Studio instead"
    GoogleRamblerFailure.BillingLinked -> "This Google project has a billing account. Mercury stopped to keep dictation free"
    GoogleRamblerFailure.DeveloperConfiguration -> "Google account setup isn't configured for this app or project yet"
    GoogleRamblerFailure.InvalidResponse, GoogleRamblerFailure.Unavailable -> "Google setup is unavailable. Check your connection and try again"
}

/** Rechecks the user's unbilled project before any microphone or Google Live socket opens. */
internal suspend fun loadCheckedRamblerApiKey(context: Context, store: RamblerSettingsStore): String? {
    val authorization = PlayServicesRamblerAuthorization(context.applicationContext) {
        throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Authorization)
    }
    val client = GoogleRamblerCloudClient()
    return try {
        checkedRamblerApiKey(
            store,
            authorize = { email -> authorization.token(email, allowResolution = false) },
            accountForToken = client::account,
            checkNoBilling = client::checkNoBilling,
        )
    } finally {
        client.close()
    }
}

internal suspend fun checkedRamblerApiKey(
    store: RamblerSettingsStore,
    authorize: suspend (String) -> String,
    accountForToken: suspend (String) -> com.unsupportedpastels.mercury.core.voice.GoogleRamblerAccount,
    checkNoBilling: suspend (String, com.unsupportedpastels.mercury.core.voice.GoogleRamblerProject) -> Unit,
): String? {
    val credential = store.loadCredentialSnapshot()
    val key = credential.apiKey ?: return null
    val connection = credential.connection ?: return key
    val token = authorize(connection.account.email)
    val account = accountForToken(token)
    if (account.subject != connection.account.subject) {
        throw GoogleRamblerProvisioningException(GoogleRamblerFailure.Authorization)
    }
    checkNoBilling(token, connection.project)
    // Return exactly the credential whose project was checked, even if
    // settings were replaced during the suspended authorization or API calls.
    return key
}
