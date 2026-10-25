package com.unsupportedpastels.hermesandroid.voice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RamblerBetaSettingsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun absentPreferenceDefaultsOff() = runTest {
        val job = SupervisorJob()
        try {
            val settings = DataStoreRamblerBetaSettings(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { File(temporaryFolder.root, "beta.preferences_pb") },
            ))
            assertFalse(settings.enabled.first())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun optInAndOptOutSurviveStoreRecreation() = runTest {
        val file = File(temporaryFolder.root, "beta.preferences_pb")
        suspend fun withSettings(block: suspend (RamblerBetaSettings) -> Unit) {
            val job = SupervisorJob()
            try {
                val settings = DataStoreRamblerBetaSettings(PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file },
                ))
                block(settings)
            } finally {
                job.cancelAndJoin()
            }
        }
        withSettings { it.setEnabled(true) }
        withSettings { assertTrue(it.enabled.first()); it.setEnabled(false) }
        withSettings { assertFalse(it.enabled.first()) }
    }
}
