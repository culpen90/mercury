package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.ramblerBetaDataStore by preferencesDataStore(name = "rambler_beta_preferences")
private val RamblerBetaEnabledKey = booleanPreferencesKey("enabled_v1")

/** Local Google onboarding opt-in; saved-key dictation works independently. */
interface RamblerBetaSettings {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}

class DataStoreRamblerBetaSettings(private val dataStore: DataStore<Preferences>) : RamblerBetaSettings {
    constructor(context: Context) : this(context.applicationContext.ramblerBetaDataStore)

    override val enabled: Flow<Boolean> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { it[RamblerBetaEnabledKey] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[RamblerBetaEnabledKey] = enabled }
    }
}

@Composable
internal fun rememberRamblerBetaSettings(): RamblerBetaSettings {
    val context = LocalContext.current
    val inspectionMode = LocalInspectionMode.current
    return remember(context, inspectionMode) {
        if (inspectionMode) PreviewRamblerBetaSettings else DataStoreRamblerBetaSettings(context)
    }
}

private object PreviewRamblerBetaSettings : RamblerBetaSettings {
    override val enabled: Flow<Boolean> = flowOf(false)
    override suspend fun setEnabled(enabled: Boolean) = Unit
}
