package com.rokid.style.ha

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

private const val TAG = "TtsManager"

/**
 * Thin wrapper around [TextToSpeech].
 *
 * Mirrors the iOS AVSpeechSynthesizer pattern: queue utterances, speak
 * with a priority override that interrupts less important speech.
 *
 * The [onReady] callback fires once TTS is initialised; callers should
 * not call [speak] before this.
 */
class TtsManager(
    context: Context,
    private val onReady: (() -> Unit)? = null
) {
    private val appContext: Context = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isReady = false

    /** Pending utterances queued before TTS is ready. */
    private val pendingQueue = ArrayDeque<Pair<String, Boolean>>()

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureEngine()
                isReady = true
                Log.i(TAG, "TTS ready")
                onReady?.invoke()
                // drain anything that was queued before init
                pendingQueue.forEach { (text, interrupt) -> doSpeak(text, interrupt) }
                pendingQueue.clear()
            } else {
                Log.e(TAG, "TTS init failed with status $status")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Speak [text].
     *
     * @param interrupt  When true, flush any current speech and speak immediately.
     *                   When false, add to queue.
     */
    fun speak(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!isReady) {
            pendingQueue.add(text to interrupt)
            return
        }
        doSpeak(text, interrupt)
    }

    /** Stop speaking immediately and flush the queue. */
    fun stop() {
        tts?.stop()
    }

    /** Release TTS resources (call from onDestroy). */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private fun configureEngine() {
        tts?.language = Locale.US
        tts?.setSpeechRate(0.95f)    // slightly slower than default — clearer on glasses
        tts?.setPitch(1.0f)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?)  { Log.v(TAG, "Speaking: $utteranceId") }
            override fun onDone(utteranceId: String?)   { Log.v(TAG, "Done: $utteranceId") }
            override fun onError(utteranceId: String?)  { Log.w(TAG, "TTS error for: $utteranceId") }
        })
    }

    private fun doSpeak(text: String, interrupt: Boolean) {
        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(
            text,
            queueMode,
            null,                          // no audio params override
            UUID.randomUUID().toString()   // unique utterance ID for tracking
        )
    }
}
