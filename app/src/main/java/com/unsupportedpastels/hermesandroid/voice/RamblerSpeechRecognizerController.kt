package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import com.unsupportedpastels.mercury.core.voice.RamblerEvent
import com.unsupportedpastels.mercury.core.voice.RamblerFailure
import com.unsupportedpastels.mercury.core.voice.RamblerProtocol
import com.unsupportedpastels.mercury.core.voice.RamblerTranscriptAccumulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64

/** Native dictation lifecycle; all transcript decisions belong to mercury-core. */
internal class RamblerSpeechRecognizerController(
    private val keyLoader: suspend () -> String?,
    private val keyConfigured: () -> Boolean,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val transportFactory: () -> RamblerTransport,
    private val microphoneFactory: () -> RamblerMicrophone,
    private val setupTimeoutMillis: Long = 15_000,
    private val finalFlushTimeoutMillis: Long = 5_000,
    private val maxSessionMillis: Long = 9 * 60_000,
) : DeviceSpeechRecognizer {
    constructor(
        context: Context,
        keyLoader: suspend () -> String?,
        keyConfigured: () -> Boolean,
        scope: CoroutineScope,
    ) : this(
        keyLoader = keyLoader,
        keyConfigured = keyConfigured,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        transportFactory = { KtorRamblerTransport() },
        microphoneFactory = { AudioRecordRamblerMicrophone(context.applicationContext) },
    )

    private val mutableState = MutableStateFlow(DeviceSpeechRecognizerState.Idle)
    override val state: StateFlow<DeviceSpeechRecognizerState> = mutableState.asStateFlow()
    override val isActive: Boolean get() = current != null
    private var generation = 0L
    private var current: Run? = null

    private class Run(
        val generation: Long,
        val baseDraft: String,
        var onDraftChanged: ((String) -> Unit)?,
        var onError: ((String) -> Unit)?,
    ) {
        val transcript = RamblerTranscriptAccumulator(baseDraft)
        val finishRequested = CompletableDeferred<Unit>()
        val remoteClosed = CompletableDeferred<Unit>()
        val sendMutex = Mutex()
        var transport: RamblerTransport? = null
        var microphone: RamblerMicrophone? = null
        var job: Job? = null
        var captureJob: Job? = null
        var listening = false
        @Volatile var finishing = false
    }

    override fun start(
        currentDraft: String,
        onDraftChanged: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (isActive || !keyConfigured() || scope.coroutineContext[Job]?.isActive == false) return false
        val run = Run(++generation, currentDraft, onDraftChanged, onError)
        current = run
        mutableState.value = DeviceSpeechRecognizerState.Preparing
        run.job = scope.launch(start = CoroutineStart.LAZY) { execute(run) }
        run.job?.start()
        return true
    }

    override fun finish() {
        val run = current ?: return
        if (run.finishing) return
        run.finishing = true
        run.microphone?.stop()
        run.captureJob?.cancel()
        if (!run.listening) {
            endImmediately(run, restoreBase = false)
            return
        }
        mutableState.value = DeviceSpeechRecognizerState.Finishing
        run.finishRequested.complete(Unit)
    }

    /** Explicit cancel discards this dictation; lifecycle abort preserves its visible draft. */
    override fun cancel() { current?.let { endImmediately(it, restoreBase = true) } }

    override fun abort() { current?.let { endImmediately(it, restoreBase = false) } }

    private fun endImmediately(run: Run, restoreBase: Boolean) {
        if (!isCurrent(run)) return
        // Invalidate synchronously before cancellation can deliver another callback.
        current = null
        generation++
        mutableState.value = DeviceSpeechRecognizerState.Idle
        run.finishing = true
        run.microphone?.stop()
        run.captureJob?.cancel()
        run.job?.cancel()
        if (restoreBase) run.onDraftChanged?.invoke(run.baseDraft)
        clearCallbacks(run)
    }

    private suspend fun execute(run: Run) {
        try {
            prepare(run)
            if (!isCurrent(run) || run.finishing) return
            val microphone = microphoneFactory()
            run.microphone = microphone
            val started = withContext(ioDispatcher) { microphone.start() }
            if (!isCurrent(run) || run.finishing) return
            if (!started) throw SessionFailure(MICROPHONE_UNAVAILABLE)
            run.listening = true
            mutableState.value = DeviceSpeechRecognizerState.Listening
            stream(run, microphone)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SessionFailure) {
            if (isCurrent(run)) run.onError?.invoke(error.notice)
        } catch (error: GoogleRamblerProvisioningException) {
            if (isCurrent(run)) run.onError?.invoke(googleRamblerNotice(error.reason))
        } catch (error: RamblerTransportException) {
            if (isCurrent(run)) run.onError?.invoke(notice(error.reason))
        } catch (_: Exception) {
            if (isCurrent(run)) run.onError?.invoke(notice(RamblerFailure.Unavailable))
        } finally {
            run.microphone?.stop()
            run.captureJob?.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) {
                    withContext(ioDispatcher) { runCatching { run.transport?.close() } }
                }
            }
            if (isCurrent(run)) {
                current = null
                generation++
                mutableState.value = DeviceSpeechRecognizerState.Idle
            }
            clearCallbacks(run)
        }
    }

    private suspend fun prepare(run: Run) {
        try {
            withTimeout(setupTimeoutMillis) {
                val key = keyLoader()?.let(RamblerProtocol::normalizeApiKey)
                    ?: throw SessionFailure("Set up Rambler in Voice settings to use dictation")
                if (!isCurrent(run)) throw CancellationException()
                val transport = transportFactory()
                run.transport = transport
                transport.connect(key)
                if (!isCurrent(run)) throw CancellationException()
                transport.send(RamblerProtocol.setupMessage())
                while (isCurrent(run)) {
                    val message = transport.receive()
                        ?: throw RamblerTransportException(RamblerFailure.Unavailable)
                    val events = RamblerProtocol.decode(message)
                    events.filterIsInstance<RamblerEvent.Failure>().firstOrNull()?.let {
                        throw RamblerTransportException(it.reason)
                    }
                    if (RamblerEvent.Ready in events) return@withTimeout
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw SessionFailure("Rambler did not become ready. Check your connection and try again")
        }
    }

    private suspend fun stream(run: Run, microphone: RamblerMicrophone) = coroutineScope {
        val transport = requireNotNull(run.transport)
        run.captureJob = launch(ioDispatcher) {
            while (isActive && !run.finishing) {
                val pcm = microphone.read()
                if (run.finishing) break
                if (pcm == null || pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > MAX_AUDIO_CHUNK_BYTES) {
                    throw SessionFailure(MICROPHONE_UNAVAILABLE)
                }
                run.sendMutex.withLock {
                    if (!run.finishing) {
                        transport.send(RamblerProtocol.audioMessage(Base64.getEncoder().encodeToString(pcm)))
                    }
                }
            }
        }
        val receiver = launch {
            while (isCurrent(run)) {
                val message = transport.receive()
                if (message == null) {
                    if (run.finishing) {
                        run.remoteClosed.complete(Unit)
                        return@launch
                    }
                    throw RamblerTransportException(RamblerFailure.Unavailable)
                }
                for (event in RamblerProtocol.decode(message)) {
                    if (!isCurrent(run)) return@launch
                    if (event is RamblerEvent.Failure) throw RamblerTransportException(event.reason)
                    if (event is RamblerEvent.Interim || event is RamblerEvent.Final) {
                        val text = run.transcript.accept(event)
                        if (run.transcript.limitReached) {
                            throw RamblerTransportException(RamblerFailure.TranscriptLimit)
                        }
                        run.onDraftChanged?.invoke(text)
                    }
                    // TurnComplete can precede the final input transcription.
                    // Keep the preview until SMART sends its corrected final.
                }
            }
        }
        val limit = launch {
            delay(maxSessionMillis)
            throw SessionFailure("Rambler dictation reached its time limit. Tap the microphone to continue")
        }
        try {
            run.finishRequested.await()
            run.captureJob?.cancelAndJoin()
            run.sendMutex.withLock { transport.send(RamblerProtocol.audioStreamEndMessage()) }
            // Live does not promise ordering of transcription and turn-complete
            // messages. Drain the full grace window, or until the socket ends.
            withTimeoutOrNull(finalFlushTimeoutMillis) { run.remoteClosed.await() }
        } finally {
            receiver.cancel()
            limit.cancel()
            run.captureJob?.cancel()
        }
    }

    private fun isCurrent(run: Run): Boolean = current === run && generation == run.generation

    private fun clearCallbacks(run: Run) {
        run.onDraftChanged = null
        run.onError = null
    }

    private class SessionFailure(val notice: String) : Exception()

    private companion object {
        const val MAX_AUDIO_CHUNK_BYTES = 32_000
        const val MICROPHONE_UNAVAILABLE = "Microphone is unavailable. Check microphone permission and try again"

        fun notice(reason: RamblerFailure): String = when (reason) {
            RamblerFailure.Authentication -> "Gemini could not authorize Rambler. Check your API key in Voice input settings"
            RamblerFailure.Quota -> "Gemini transcription quota is exhausted. Check your Gemini API quota"
            RamblerFailure.ModelUnavailable -> "Rambler is unavailable for this Gemini API project. Check model access and region"
            RamblerFailure.SessionExpired -> "The Rambler session ended. Tap the microphone to continue"
            RamblerFailure.TranscriptLimit -> "Rambler dictation reached its text limit. Tap the microphone to continue"
            RamblerFailure.InvalidRequest, RamblerFailure.MalformedResponse, RamblerFailure.Remote,
            RamblerFailure.Unavailable -> "Rambler is unavailable. Check your connection and Gemini API project, then try again"
        }
    }
}
