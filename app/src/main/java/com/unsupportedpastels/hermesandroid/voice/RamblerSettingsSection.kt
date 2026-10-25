package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.unsupportedpastels.mercury.core.voice.RamblerProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val GEMINI_KEY_URL = "https://aistudio.google.com/api-keys"
private const val RAMBLER_EXPLANATION = "Gemini 3.5 Transcribe powers Rambler. Audio is sent to Google."
private const val GOOGLE_SETUP_EXPLANATION = "Continue with Google to authorize a separate Google project " +
    "and Gemini API key for free-tier use. Mercury does not attach billing. " +
    "Google's terms, permissions, and quotas apply."

@Composable
internal fun RamblerSettingsSection(
    store: RamblerSettingsStore,
    onChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
    googleSetup: RamblerGoogleSetupFactory? = null,
    showGoogleSetup: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Rambler dictation", style = MaterialTheme.typography.titleMedium)
        RamblerKeyEditor(
            store,
            onSaved = onChanged,
            onRemoved = onChanged,
            googleSetup = googleSetup,
            showGoogleSetup = showGoogleSetup,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RamblerSetupDialog(
    store: RamblerSettingsStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    googleSetup: RamblerGoogleSetupFactory? = null,
    showGoogleSetup: Boolean = false,
) {
    var busy by remember(store) { mutableStateOf(false) }
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val dialogMaxHeight = (windowHeight - 48.dp).coerceAtLeast(48.dp)
    BasicAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp).heightIn(max = dialogMaxHeight),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = dialogMaxHeight),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            // Bound the whole dialog before allocating the remaining height to
            // its scroll body; title and dismiss action stay outside that body.
            Column(Modifier.padding(24.dp)) {
                Text(
                    "Set up Rambler dictation",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                        RamblerKeyEditor(
                            store,
                            onSaved = onSaved,
                            googleSetup = googleSetup,
                            showGoogleSetup = showGoogleSetup,
                            onBusyChanged = { busy = it },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                }
            }
        }
    }
}

/** Key entry is deliberately never saveable or populated from the persisted credential. */
@Composable
private fun RamblerKeyEditor(
    store: RamblerSettingsStore,
    onSaved: () -> Unit,
    onRemoved: (() -> Unit)? = null,
    googleSetup: RamblerGoogleSetupFactory? = null,
    showGoogleSetup: Boolean = false,
    onBusyChanged: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    var configured by remember(store) { mutableStateOf<Boolean?>(null) }
    var keyEntry by remember(store) { mutableStateOf("") }
    var pending by remember(store) { mutableStateOf(false) }
    var error by remember(store) { mutableStateOf<String?>(null) }
    val onConfigured = {
        configured = true
        keyEntry = ""
        error = null
        onSaved()
    }
    val google = when {
        !showGoogleSetup -> null
        googleSetup != null -> googleSetup(onConfigured)
        LocalInspectionMode.current -> RamblerGoogleSetupUi(onContinue = {})
        else -> rememberRamblerGoogleSetup(store, onConfigured)
    }
    val busy = pending || google?.busy == true
    LaunchedEffect(busy) { onBusyChanged(busy) }

    LaunchedEffect(store) {
        try {
            configured = store.loadApiKey() != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            configured = false
            error = "Couldn't load the Gemini API key"
        }
    }

    val normalizedKey = RamblerProtocol.normalizeApiKey(keyEntry)
    val invalidEntry = keyEntry.isNotBlank() && normalizedKey == null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(RAMBLER_EXPLANATION, style = MaterialTheme.typography.bodySmall)
        Text(
            when (configured) {
                true -> "Configured"
                false -> "Not configured"
                null -> "Checking configuration…"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (error != null) {
            Text(checkNotNull(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (onRemoved != null && configured == true) {
            OutlinedButton(
                onClick = {
                    pending = true
                    error = null
                    scope.launch {
                        try {
                            store.clearApiKey()
                            keyEntry = ""
                            configured = false
                            onRemoved()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "Couldn't remove the Gemini API key"
                        } finally {
                            pending = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Remove key") }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Use a Google AI Studio project without billing to stay on the free tier.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    try {
                        uriHandler.openUri(GEMINI_KEY_URL)
                    } catch (_: Exception) {
                        error = "Couldn't open Google AI Studio"
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Get a Gemini API key") }
            OutlinedTextField(
                value = keyEntry,
                onValueChange = { keyEntry = it; error = null },
                label = { Text(if (configured == true) "Replace Gemini API key" else "Gemini API key") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                isError = invalidEntry,
                supportingText = if (invalidEntry) {
                    { Text("Enter a valid Gemini API key") }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Gemini API key" },
            )
            Button(
                onClick = {
                    val submitted = normalizedKey ?: return@Button
                    pending = true
                    error = null
                    scope.launch {
                        try {
                            store.saveApiKey(submitted)
                            keyEntry = ""
                            configured = true
                            onSaved()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "Couldn't save the Gemini API key"
                        } finally {
                            pending = false
                        }
                    }
                },
                enabled = configured != null && !busy && normalizedKey != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save key") }
        }
        if (google != null) {
            Text(GOOGLE_SETUP_EXPLANATION, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    error = null
                    keyEntry = ""
                    focusManager.clearFocus()
                    google.onContinue()
                },
                enabled = configured != null && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue with Google") }
            google.status?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            google.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
