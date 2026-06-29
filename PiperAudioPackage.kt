package com.example.ecganalysis.audio.piper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


/**
 * Lightweight wrapper for offline Piper TTS. Keeps native Android TTS untouched.
 * Use this to test Piper from a single button.
 */
class PiperAudioPackage(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val piper = PiperTTSManager(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    var onReady: ((Boolean) -> Unit)? = null
    var onStart: ((String) -> Unit)? = null
    var onDone: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null


    fun initialize() {
        Thread {
            val ok = piper.initialize(scope)
            mainHandler.post { onReady?.invoke(ok) }
            if (!ok) mainHandler.post { Toast.makeText(context, "Piper init failed", Toast.LENGTH_SHORT).show() }
        }.start()

        piper.firstSampleCallback = { requestId ->
            requestId?.let { mainHandler.post { onStart?.invoke(it) } }
        }
    }

    fun speak(text: String, speed: Float = 1.0f) {
        if (!piper.isReady()) {
            mainHandler.post { Toast.makeText(context, "Piper not ready", Toast.LENGTH_SHORT).show() }
            return
        }

        piper.synthesizeAndSpeak(text = text, speed = speed)
    }

    fun stopCurrent() {
        piper.stopCurrent()
            mainHandler.post { onError?.invoke("STOPPED") }
        }
    }

    fun isReady(): Boolean = piper.isReady()
    fun release() { piper.stop() }

    // Optional helper to bind to a test button quickly
    fun bindTestButton(button: Button, testText: String = "Hello from Piper offline") {
        mainHandler.post {
            button.isEnabled = false
            button.text = "Initializing Piper..."
        }
        onReady = { ready ->
            mainHandler.post {
                button.isEnabled = ready
                button.text = if (ready) "Test Piper (offline)" else "Piper init failed"
            }
        }
        onStart = { _ -> mainHandler.post { button.text = "Playing..." } }
        onDone = { _ -> mainHandler.post { button.text = "Test Piper (offline)"; Toast.makeText(context, "Piper done", Toast.LENGTH_SHORT).show() } }
        onError = { _ -> mainHandler.post { button.text = "Test Piper (offline)"; Toast.makeText(context, "Piper error", Toast.LENGTH_SHORT).show() } }

        button.setOnClickListener {
            speak(testText)
        }

        initialize()
    }
}