package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RamblerBetaSettingsSection(
    store: RamblerSettingsStore,
    betaSettings: RamblerBetaSettings,
    googleSetup: RamblerGoogleSetupFactory? = null,
    enrollment: RamblerBetaEnrollmentFactory? = null,
) {
    val enabled by betaSettings.enabled.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var saving by remember(betaSettings) { mutableStateOf(false) }
    var error by remember(betaSettings) { mutableStateOf<String?>(null) }
    fun saveOptIn(requested: Boolean) {
        if (saving) return
        error = null
        saving = true
        scope.launch {
            try {
                betaSettings.setEnabled(requested)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = if (requested) "Could not save the Rambler beta setting. Try again." else
                    "Could not turn off Google setup beta. Turn the switch off to retry."
            } finally {
                saving = false
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Google setup beta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                enabled = !saving,
                onCheckedChange = ::saveOptIn,
                modifier = Modifier.semantics { contentDescription = "Enable Google setup beta" },
            )
        }
        Text(
            "Optional experimental Google account login and automatic Gemini key setup.",
            style = MaterialTheme.typography.bodyMedium,
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            })
        }
        if (enabled) {
            RamblerBetaEnrollmentSection(enrollment, onWithdrawn = { saveOptIn(false) })
        }
        RamblerSettingsSection(store, googleSetup = googleSetup, showGoogleSetup = enabled)
    }
}
