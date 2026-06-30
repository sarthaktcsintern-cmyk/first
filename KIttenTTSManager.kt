package com.example.audiodemoapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class KittenTTSManager(private val context: Context) {
    private var tts: OfflineTts? = null
    private var isReady = false
    private var audioTrack: AudioTrack? = null
    private var isStopped = false

    // New: callback to report latencies (firstMs, totalMs)
    var latencyCallback: ((firstMs: Long, totalMs: Long) -> Unit)? = null

    // Helpers for measuring first-sample time
    private val firstSampleRecorded = AtomicBoolean(false)
    @Volatile
    private var firstSampleTimeNs: Long = -1L

    companion object {
        private const val TAG = "KittenTTSManager"
    }

    /**
     * Initialize Kitten Nano TTS
     * Copies espeak-ng-data to external storage, then loads from assets
     */
    fun initialize(scope: CoroutineScope): Boolean {
        return try {
            Log.i(TAG, "Starting Kitten TTS initialization...")

            // Copy espeak-ng-data to external files directory
            copyDataDir("kitten-nano-en-v0_1-fp16/espeak-ng-data")

            val dataDir = "${context.getExternalFilesDir(null)}/kitten-nano-en-v0_1-fp16/espeak-ng-data"

            Log.i(TAG, "Data dir path: $dataDir")

            // Create TTS config for Kitten
            val config = getOfflineTtsConfig(
                modelDir = "kitten-nano-en-v0_1-fp16",
                modelName = "model.fp16.onnx",
                acousticModelName = "",
                vocoder = "",
                voices = "voices.bin",
                lexicon = "",
                dataDir = dataDir,
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                isKitten = true,
                isSupertonic = false,
                durationPredictor = "",
                textEncoder = "",
                vectorEstimator = "",
                supertonicVocoder = "",
                ttsJson = "",
                unicodeIndexer = "",
                voiceStyle = ""
            )

            Log.i(TAG, "Config created, initializing TTS...")
            tts = OfflineTts(assetManager = context.assets, config = config!!)

            // Initialize AudioTrack
            initAudioTrack()

            isReady = true
            Log.i(TAG, "Kitten Nano TTS initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Kitten Nano TTS: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Copy espeak-ng-data from assets to external files directory
     */
    private fun copyDataDir(dataDir: String) {
        try {
            Log.i(TAG, "Copying data dir: $dataDir")
            copyAssets(dataDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy data dir: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Recursively copy assets
     */
    private fun copyAssets(path: String) {
        try {
            val assets = context.assets.list(path)
            if (assets != null) {
                if (assets.isEmpty()) {
                    copyFile(path)
                } else {
                    val fullPath = "${context.getExternalFilesDir(null)}/$path"
                    val dir = File(fullPath)
                    dir.mkdirs()

                    for (asset in assets) {
                        val newPath = if (path.isEmpty()) asset else "$path/$asset"
                        copyAssets(newPath)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy assets: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Copy individual file from assets
     */
    private fun copyFile(filename: String) {
        try {
            val inputStream = context.assets.open(filename)
            val outputPath = "${context.getExternalFilesDir(null)}/$filename"
            val outputStream = FileOutputStream(outputPath)

            val buffer = ByteArray(8192)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }

            inputStream.close()
            outputStream.close()
            Log.i(TAG, "Copied $filename to $outputPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file $filename: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Initialize AudioTrack for playback
     */
    private fun initAudioTrack() {
        val sampleRate = tts?.sampleRate() ?: 22050
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        Log.i(TAG, "Sample Rate: $sampleRate, Buffer Length: $bufLength")

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        audioTrack = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    /**
     * Callback for audio playback
     * We record the time when the first samples arrive.
     */
    private fun audioCallback(samples: FloatArray): Int {
        if (firstSampleRecorded.compareAndSet(false, true)) {
            firstSampleTimeNs = System.nanoTime()
        }

        return if (!isStopped) {
            audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            1
        } else {
            audioTrack?.stop()
            0
        }
    }

    /**
     * Synthesize and speak text
     */
    fun synthesizeAndSpeak(text: String, speed: Float = 1.0f) {
        if (!isReady || tts == null) {
            Log.e(TAG, "Kitten Nano TTS is not ready")
            return
        }

        // Run synthesis in background thread
        Thread {
            val startNs = System.nanoTime()
            try {
                isStopped = false
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()

                // Reset first-sample tracking
                firstSampleRecorded.set(false)
                firstSampleTimeNs = -1L

                Log.i(TAG, "Synthesizing text: $text with speed: $speed")
                val genConfig = GenerationConfig(sid = 0, speed = speed)
                val audio = tts!!.generateWithConfigAndCallback(
                    text = text,
                    config = genConfig,
                    callback = this::audioCallback
                )

                val endNs = System.nanoTime()

                val firstMs = if (firstSampleTimeNs > 0) (firstSampleTimeNs - startNs) / 1_000_000 else -1L
                val totalMs = (endNs - startNs) / 1_000_000

                // Notify listener
                latencyCallback?.invoke(firstMs, totalMs)

                // Save to file
                val filename = "${context.filesDir.absolutePath}/kitten_generated.wav"
                if (audio.samples.isNotEmpty()) {
                    audio.save(filename)
                    Log.i(TAG, "Audio saved to $filename")
                }

                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error during synthesis: ${e.message}")
                e.printStackTrace()
                latencyCallback?.invoke(-1L, -1L)
            }
        }.start()
    }

    /**
     * Check if TTS is ready
     */
    fun isReady(): Boolean = isReady

    /**
     * Stop synthesis
     */
    fun stop() {
        isStopped = true
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
        }
        tts = null
        isReady = false
    }
}