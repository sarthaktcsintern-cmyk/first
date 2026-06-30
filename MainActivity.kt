package com.example.audiodemoapp

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var inputText: EditText
    private lateinit var nativeTtsButton: Button
    private lateinit var kokoroTtsButton: Button
    private lateinit var kittenTtsButton: Button
    private lateinit var piperTtsButton: Button
    private lateinit var nativeSpeedInput: EditText
    private lateinit var kokoroSpeedInput: EditText
    private lateinit var kittenSpeedInput: EditText
    private lateinit var piperSpeedInput: EditText

    // TextViews to display latency
    private lateinit var nativeLatencyText: TextView
    private lateinit var kokoroLatencyText: TextView
    private lateinit var kittenLatencyText: TextView
    private lateinit var piperLatencyText: TextView

    // New: TextViews to display energy metrics
    private lateinit var nativeEnergyText: TextView
    private lateinit var kokoroEnergyText: TextView
    private lateinit var kittenEnergyText: TextView
    private lateinit var piperEnergyText: TextView

    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    // Offline TTS components
    private lateinit var kokoroTTSManager: KokoroTTSManager
    private lateinit var kittenTTSManager: KittenTTSManager
    private lateinit var piperTTSManager: PiperTTSManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // For native TTS timing
    private var nativeStartNs: Long = 0L
    private var nativeFirstMs: Long = -1L

    // Track the currently active Piper request id (null when none)
    private var lastPiperRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity)

        // Initialize UI elements
        inputText = findViewById(R.id.inputText)
        nativeTtsButton = findViewById(R.id.nativeTtsButton)
        kokoroTtsButton = findViewById(R.id.kokoroTtsButton)
        kittenTtsButton = findViewById(R.id.kittenTtsButton)
        piperTtsButton = findViewById(R.id.piperTtsButton)
        nativeSpeedInput = findViewById(R.id.nativeSpeedInput)
        kokoroSpeedInput = findViewById(R.id.kokoroSpeedInput)
        kittenSpeedInput = findViewById(R.id.kittenSpeedInput)
        piperSpeedInput = findViewById(R.id.piperSpeedInput)

        // Latency display textviews
        nativeLatencyText = findViewById(R.id.nativeLatencyText)
        kokoroLatencyText = findViewById(R.id.kokoroLatencyText)
        kittenLatencyText = findViewById(R.id.kittenLatencyText)
        piperLatencyText = findViewById(R.id.piperLatencyText)

        // Energy metrics display textviews
        nativeEnergyText = findViewById(R.id.nativeEnergyText)
        kokoroEnergyText = findViewById(R.id.kokoroEnergyText)
        kittenEnergyText = findViewById(R.id.kittenEnergyText)
        piperEnergyText = findViewById(R.id.piperEnergyText)

        // Initialize Google TTS (Native)
        textToSpeech = TextToSpeech(this, this)

        // Initialize offline TTS managers
        kokoroTTSManager = KokoroTTSManager(this)
        kittenTTSManager = KittenTTSManager(this)
        piperTTSManager = PiperTTSManager(this)

        // Wire latency callbacks
        kokoroTTSManager.latencyCallback = { firstMs, totalMs ->
            runOnUiThread {
                val first = if (firstMs >= 0) "$firstMs ms" else "N/A"
                val total = if (totalMs >= 0) "$totalMs ms" else "N/A"
                kokoroLatencyText.text = "first: $first, total: $total"
            }
        }

        kittenTTSManager.latencyCallback = { firstMs, totalMs ->
            runOnUiThread {
                val first = if (firstMs >= 0) "$firstMs ms" else "N/A"
                val total = if (totalMs >= 0) "$totalMs ms" else "N/A"
                kittenLatencyText.text = "first: $first, total: $total"
            }
        }

        piperTTSManager.latencyCallback = { firstMs, totalMs ->
            runOnUiThread {
                val first = if (firstMs >= 0) "$firstMs ms" else "N/A"
                val total = if (totalMs >= 0) "$totalMs ms" else "N/A"
                val idDisplay = lastPiperRequestId ?: "id: -"
                piperLatencyText.text = "$idDisplay ? first: $first, total: $total"

                // generation finished (success or failure). reset button state
                lastPiperRequestId = null
                piperTtsButton.text = "Piper Start"
            }
        }

        // New: Wire energy callbacks for Piper
        piperTTSManager.energyCallback = { metrics ->
            runOnUiThread {
                val cpu = if (metrics.cpuTimeMs >= 0) "${metrics.cpuTimeMs} ms" else "N/A"
                val memory = if (metrics.memoryMB >= 0) "${"%.2f".format(metrics.memoryMB)} MB" else "N/A"
                val battery = if (metrics.estimatedBatteryPercent >= 0) "${"%.3f".format(metrics.estimatedBatteryPercent)} %" else "N/A"
                piperEnergyText.text = "CPU: $cpu | Memory: $memory | Battery: $battery"
            }
        }

        // Initialize managers in background
        scope.launch(Dispatchers.IO) {
            val success = kokoroTTSManager.initialize(scope)
            withContext(Dispatchers.Main) {
                if (success) Toast.makeText(this@MainActivity, "Kokoro TTS initialized", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this@MainActivity, "Failed to initialize Kokoro TTS", Toast.LENGTH_SHORT).show()
            }
        }

        scope.launch(Dispatchers.IO) {
            val success = kittenTTSManager.initialize(scope)
            withContext(Dispatchers.Main) {
                if (success) Toast.makeText(this@MainActivity, "Kitten Nano TTS initialized", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this@MainActivity, "Failed to initialize Kitten Nano TTS", Toast.LENGTH_SHORT).show()
            }
        }

        scope.launch(Dispatchers.IO) {
            val success = piperTTSManager.initialize(scope)
            withContext(Dispatchers.Main) {
                if (success) Toast.makeText(this@MainActivity, "Piper TTS initialized", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this@MainActivity, "Failed to initialize Piper TTS", Toast.LENGTH_SHORT).show()
            }
        }

        // Set button click listeners
        nativeTtsButton.setOnClickListener { speakUsingNativeTts() }
        kokoroTtsButton.setOnClickListener { speakUsingKokoroTts() }
        kittenTtsButton.setOnClickListener { speakUsingKittenTts() }

        // Single toggle button for Piper start/stop
        piperTtsButton.setOnClickListener {
            val activeId = lastPiperRequestId
            if (activeId != null) {
                // There is an active Piper request -> stop it
                piperTTSManager.stopRequest(activeId)
                // update UI immediately
                piperLatencyText.text = "id: $activeId ? stopped"
                lastPiperRequestId = null
                piperTtsButton.text = "Piper Start"
                return@setOnClickListener
            }

            // Otherwise start a new Piper request
            val text = inputText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!piperTTSManager.isReady()) {
                Toast.makeText(this, "Piper TTS is not ready", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val speedText = piperSpeedInput.text.toString().trim()
            val speed = speedText.toFloatOrNull() ?: 1.0f

            // Use UUID as a unique utterance/request id
            val requestId = UUID.randomUUID().toString()
            lastPiperRequestId = requestId

            piperLatencyText.text = "id: $requestId ? running..."
            piperEnergyText.text = "Computing..."
            piperTtsButton.text = "Stop"

            Toast.makeText(this, "Starting Piper TTS (id=$requestId)", Toast.LENGTH_SHORT).show()

            // Start synthesis with the request id
            piperTTSManager.synthesizeAndSpeak(text, speed, requestId)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.getDefault())
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isTtsReady) {
                Toast.makeText(this, "Language not supported for Native TTS", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Native TTS initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speakUsingNativeTts() {
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show(); return }
        if (!isTtsReady) { Toast.makeText(this, "Native TTS is not ready", Toast.LENGTH_SHORT).show(); return }

        val speedText = nativeSpeedInput.text.toString().trim()
        val speed = speedText.toFloatOrNull() ?: 1.0f

        nativeStartNs = System.nanoTime()
        nativeFirstMs = -1L
        val utteranceId = "native_tts_${nativeStartNs}"
        textToSpeech?.setSpeechRate(speed)
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uttId: String?) {
                nativeFirstMs = (System.nanoTime() - nativeStartNs) / 1_000_000
                runOnUiThread { nativeLatencyText.text = "first: ${nativeFirstMs} ms" }
            }

            override fun onDone(uttId: String?) {
                val totalMs = (System.nanoTime() - nativeStartNs) / 1_000_000
                runOnUiThread {
                    val firstDisplay = if (nativeFirstMs >= 0) "${nativeFirstMs} ms" else "N/A"
                    nativeLatencyText.text = "first: $firstDisplay, total: ${totalMs} ms"
                }
            }

            override fun onError(uttId: String?) {
                runOnUiThread { nativeLatencyText.text = "error" }
            }
        })

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun speakUsingKokoroTts() {
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show(); return }
        if (!kokoroTTSManager.isReady()) { Toast.makeText(this, "Kokoro TTS is not ready", Toast.LENGTH_SHORT).show(); return }

        val speedText = kokoroSpeedInput.text.toString().trim()
        val speed = speedText.toFloatOrNull() ?: 1.0f
        Toast.makeText(this, "Speaking with Kokoro TTS", Toast.LENGTH_SHORT).show()
        kokoroTTSManager.synthesizeAndSpeak(text, speed)
    }

    private fun speakUsingKittenTts() {
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show(); return }
        if (!kittenTTSManager.isReady()) { Toast.makeText(this, "Kitten Nano TTS is not ready", Toast.LENGTH_SHORT).show(); return }

        val speedText = kittenSpeedInput.text.toString().trim()
        val speed = speedText.toFloatOrNull() ?: 1.0f
        Toast.makeText(this, "Speaking with Kitten Nano TTS", Toast.LENGTH_SHORT).show()
        kittenTTSManager.synthesizeAndSpeak(text, speed)
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        kokoroTTSManager.stop()
        kittenTTSManager.stop()
        piperTTSManager.stop()
        scope.cancel()
        super.onDestroy()
    }
}