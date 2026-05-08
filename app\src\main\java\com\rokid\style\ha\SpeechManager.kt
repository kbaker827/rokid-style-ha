package com.rokid.style.ha

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*

private const val TAG = "SpeechManager"

/**
 * Continuous voice recognition using Android's [SpeechRecognizer].
 *
 * Android's built-in recognizer stops after silence/timeout, so we
 * auto-restart it in [onResults] / [onError] to keep the loop alive —
 * mirroring the continuous behaviour of iOS SFSpeechRecognizer.
 *
 * IMPORTANT: [SpeechRecognizer] must be created and used on the **main thread**.
 * The [scope] passed here must dispatch to [Dispatchers.Main].
 *
 * @param onResult      Called for each recognised phrase
 * @param onListening   Called when a new recognition session starts
 * @param onStopped     Called when recognition is fully stopped
 */
class SpeechManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onResult: (String) -> Unit,
    private val onListening: (() -> Unit)? = null,
    private val onStopped: (() -> Unit)? = null
) {
    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var restartJob: Job? = null

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Start continuous recognition. Must be called on the main thread. */
    fun start() {
        if (isRunning) return
        isRunning = true
        createAndStart()
    }

    /** Stop recognition and release resources. Must be called on the main thread. */
    fun stop() {
        isRunning = false
        restartJob?.cancel()
        destroyRecognizer()
        onStopped?.invoke()
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private fun createAndStart() {
        destroyRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            isRunning = false
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            // Suppress "speak now" prompt on Rokid / headless devices
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }
        try {
            recognizer?.startListening(intent)
            onListening?.invoke()
            Log.d(TAG, "Listening…")
        } catch (e: Exception) {
            Log.e(TAG, "startListening error: ${e.message}")
            scheduleRestart(delayMs = 2000)
        }
    }

    private fun scheduleRestart(delayMs: Long = 500) {
        if (!isRunning) return
        restartJob?.cancel()
        restartJob = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            if (isRunning) createAndStart()
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) { /* ignore */ }
        recognizer = null
    }

    // -----------------------------------------------------------------------
    // RecognitionListener
    // -----------------------------------------------------------------------

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull()?.trim()
            if (!best.isNullOrBlank()) {
                Log.i(TAG, "Recognised: \"$best\"")
                onResult(best)
            }
            // Restart immediately for continuous listening
            scheduleRestart(delayMs = 300)
        }

        override fun onPartialResults(partial: Bundle?) {
            // Not used; we wait for final results
        }

        override fun onError(error: Int) {
            val msg = speechErrorString(error)
            Log.w(TAG, "Recognition error $error: $msg")
            // On NO_MATCH or SPEECH_TIMEOUT, restart quickly; on others wait longer
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2000L
                else -> 1000L
            }
            scheduleRestart(delayMs = delay)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun speechErrorString(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO                -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT               -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK              -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT      -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH             -> "No match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY      -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER               -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT       -> "Speech timeout"
        else                                        -> "Unknown error $error"
    }
}
