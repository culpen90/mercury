package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.runtime.Composable

/** Only user-safe setup status crosses into the UI; credentials stay in the setup owner. */
internal data class RamblerGoogleSetupUi(
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val onContinue: () -> Unit,
)

internal typealias RamblerGoogleSetupFactory = @Composable (onConfigured: () -> Unit) -> RamblerGoogleSetupUi
