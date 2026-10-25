package com.unsupportedpastels.hermesandroid.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/** One-use microphone, returning 16 kHz mono signed little-endian PCM16. */
internal interface RamblerMicrophone {
    suspend fun start(): Boolean
    suspend fun read(): ByteArray?
    /** Stops capture immediately, including an outstanding blocking read. */
    fun stop()
}

/**
 * One session owns one recorder. The controller calls start/read on its IO
 * dispatcher; stop may race either operation and never opens a replacement.
 */
internal class AudioRecordRamblerMicrophone(private val context: Context) : RamblerMicrophone {
    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var stopped = false

    override suspend fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return synchronized(lock) {
            if (stopped || recorder != null) return@synchronized false
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) return@synchronized false
            try {
                @Suppress("DEPRECATION")
                val input = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 2, CHUNK_BYTES * 2),
                )
                recorder = input
                if (input.state != AudioRecord.STATE_INITIALIZED) {
                    stopLocked()
                    return@synchronized false
                }
                input.startRecording()
                if (input.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    stopLocked()
                    return@synchronized false
                }
                true
            } catch (_: Exception) {
                stopLocked()
                false
            }
        }
    }

    override suspend fun read(): ByteArray? {
        val input = synchronized(lock) { recorder } ?: return null
        val pcm = ByteArray(CHUNK_BYTES)
        val count = try {
            input.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
        } catch (_: Exception) {
            return null
        }
        if (synchronized(lock) { stopped }) return null
        // PCM16 frames must contain complete samples, including a short read.
        return if (count > 0 && count % 2 == 0) pcm.copyOf(count) else null
    }

    override fun stop() {
        synchronized(lock) {
            stopped = true
            stopLocked()
        }
    }

    private fun stopLocked() {
        val input = recorder
        recorder = null
        if (input != null) {
            runCatching { input.stop() }
            runCatching { input.release() }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10
    }
}
