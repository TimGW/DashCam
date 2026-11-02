package com.dashcam.data.recorder

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Listens for a spoken "lock" command while recording, using the phone's speech recognition
 * (on-device where available, so it also works offline). Android's recognizer handles one
 * utterance at a time, so it is restarted after every result or timeout. Only short utterances
 * count, so a keyword in a conversation or on the radio doesn't lock clips. Main thread only.
 */
class VoiceCommandListener(
    private val context: Context,
    private val onCommand: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private var failures = 0
    private var lastCommandAt = 0L

    /** False when the phone has no speech recognition. */
    fun start(): Boolean {
        if (running) return true
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) return false
        running = true
        createRecognizer()
        listen()
        return true
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = (
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            ).apply { setRecognitionListener(listener) }
    }

    private fun listen() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { recognizer?.startListening(intent) }
            .onFailure { Log.w(TAG, "Couldn't start listening", it); retry() }
    }

    private fun listenAgain(delayMillis: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(::listen, delayMillis)
    }

    /** Backs off when recognition keeps failing, so a broken recognizer doesn't spin. */
    private fun retry() {
        failures++
        if (failures == FAILURES_BEFORE_LOG) log("Voice commands: speech recognition keeps failing; retrying")
        val delayMillis = (RETRY_INITIAL_MS shl (failures - 1).coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!running) return@postDelayed
            createRecognizer()
            listen()
        }, delayMillis)
    }

    private fun onUtterance(candidates: List<String>) {
        val heard = candidates.firstOrNull { isCommand(it) } ?: return
        val now = System.currentTimeMillis()
        if (now - lastCommandAt < COOLDOWN_MS) return
        lastCommandAt = now
        log("Voice command: \"$heard\"")
        onCommand()
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            failures = 0
            onUtterance(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            listenAgain(RESTART_MS)
        }

        override fun onError(error: Int) {
            if (!running) return
            when (error) {
                // Nothing (recognisable) was said: the normal way an idle listen ends.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    failures = 0
                    listenAgain(RESTART_MS)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    log("Voice commands off: no microphone permission")
                    stop()
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    if (onDevice) {
                        // The on-device model lacks the phone's language: fall back to the regular one.
                        onDevice = false
                        createRecognizer()
                        listenAgain(RESTART_MS)
                    } else {
                        log("Voice commands off: speech recognition doesn't support the phone's language")
                        stop()
                    }
                else -> retry()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "VoiceCommandListener"
        private const val RESTART_MS = 200L
        private const val RETRY_INITIAL_MS = 1_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val FAILURES_BEFORE_LOG = 3
        private const val COOLDOWN_MS = 3_000L
        /** Longer utterances are speech, not a command. */
        private const val MAX_COMMAND_WORDS = 4

        /** English and Dutch; "lok" and "lokken" are how a Dutch recognizer often hears "lock". */
        private val KEYWORDS = setOf(
            "lock", "locken", "lok", "lokken", "slot", "vergrendel", "vergrendelen",
            "save", "opslaan", "bewaar", "bewaren", "keep",
            "mark", "markeer", "markeren", "vastleggen", "vastzetten",
            "protect", "beveilig", "beveiligen",
            "incident", "ongeluk", "botsing", "crash",
        )

        /** Separable Dutch verbs: "sla (het) op", "leg (dit) vast". */
        private val PHRASES = listOf(
            setOf("sla", "op"), setOf("leg", "vast"), setOf("zet", "vast"), setOf("hou", "vast"),
        )

        fun isCommand(utterance: String): Boolean {
            val words = utterance.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
            if (words.size > MAX_COMMAND_WORDS) return false
            return words.any { it in KEYWORDS } || PHRASES.any { words.containsAll(it) }
        }
    }
}
