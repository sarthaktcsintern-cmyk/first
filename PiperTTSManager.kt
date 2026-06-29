package com.example.ecganalysis.audio.piper

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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

import java.util.concurrent.Executors
import java.util.concurrent.Future

class PiperTTSManager(private val context: Context) {
    private var tts: OfflineTts? = null
    private var isReady = false
    private var audioTrack: AudioTrack? = null
    private var isStopped = false

    // New: latency callback
    var latencyCallback: ((firstMs: Long, totalMs: Long) -> Unit)? = null

    // New: first-sample callback to notify when audio actually starts
    var firstSampleCallback: ((requestId: String?) -> Unit)? = null

    // Helpers for measuring first-sample time
    private val firstSampleRecorded = AtomicBoolean(false)
    @Volatile
    private var firstSampleTimeNs: Long = -1L

    // New: current active request id (only one active request supported at a time)
    @Volatile
    private var activeRequestId: String? = null

    private val synthesisExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var currentTask: Future<*>? = null

    @Volatile
    private var pendingText: String? = null
    @Volatile
    private var pendingSpeed: Float = 1.0f
    @Volatile
    private var pendingRequestId: String? = null

    companion object {
        private const val TAG = "PiperTTSManager"
    }

    /**
     * Initialize TTS. Pass a CoroutineScope if you want (not required).
     */
    fun initialize(scope: CoroutineScope?): Boolean {
        return try {
            // Copy espeak-ng-data (and any other required files under the model dir) to external files directory
            copyDataDir("vits-piper-en_US-amy-low/espeak-ng-data")

            val dataDir = "${context.getExternalFilesDir(null)}/vits-piper-en_US-amy-low/espeak-ng-data"

            // Create TTS config for Piper (VITS)
            val config = getOfflineTtsConfig(
                modelDir = "vits-piper-en_US-amy-low",
                modelName = "en_US-amy-low.onnx",
                acousticModelName = "",
                vocoder = "",
                voices = "",
                lexicon = "",
                dataDir = dataDir,
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                isKitten = false,
                isSupertonic = false,
                durationPredictor = "",
                textEncoder = "",
                vectorEstimator = "",
                supertonicVocoder = "",
                ttsJson = "",
                unicodeIndexer = "",
                voiceStyle = ""
            )

            tts = OfflineTts(assetManager = context.assets, config = config!!)

            // Initialize AudioTrack for streaming callback playback
            initAudioTrack()

            isReady = true
            Log.i(TAG, "Piper TTS initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Piper TTS: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Recursively copy a folder from assets into external files dir.
     * Path should be relative to assets root, e.g. "vits-piper-en_US-amy-low/espeak-ng-data"
     */
    private fun copyDataDir(dataDir: String) {
        try {
            copyAssets(dataDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy data dir: ${e.message}")
        }
    }

    private fun copyAssets(path: String) {
        try {
            val assets = context.assets.list(path)
            if (assets != null) {
                if (assets.isEmpty()) {
                    // It's a file
                    copyFile(path)
                } else {
                    // It's a directory
                    val fullPath = "${context.getExternalFilesDir(null)}/$path"
                    val dir = File(fullPath)
                    if (!dir.exists()) dir.mkdirs()

                    for (asset in assets) {
                        val newPath = if (path.isEmpty()) asset else "$path/$asset"
                        copyAssets(newPath)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy assets: ${e.message}")
        }
    }

    private fun copyFile(filename: String) {
        try {
            context.assets.open(filename).use { inputStream ->
                val newFilename = "${context.getExternalFilesDir(null)}/$filename"
                val outFile = File(newFilename)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { outputStream ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                }
            }
            Log.i(TAG, "Copied $filename")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to copy $filename, $ex")
        }
    }

    /**
     * Initialize AudioTrack for playback of float PCM via callback writes.
     */
    private fun initAudioTrack() {
        val sampleRate = tts?.sampleRate() ?: 24000
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
            attr, format, if (bufLength > 0) bufLength else sampleRate,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    /**
     * Callback that will be invoked by sherpa-onnx native code with generated float samples.
     * Return 1 to continue, 0 to stop.
     */
    private fun audioCallback(samples: FloatArray): Int {
        // record first-sample time if this is the first callback for the active request
        if (firstSampleRecorded.compareAndSet(false, true)) {
            firstSampleTimeNs = System.nanoTime()
            // notify first sample to UI/adapter
            firstSampleCallback?.invoke(activeRequestId)
        }

        // If the request was stopped or there is no active request, stop streaming
        if (isStopped || activeRequestId == null) {
            audioTrack?.stop()
            return 0
        }

        return try {
            audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            1
        } catch (e: Exception) {
            Log.e(TAG, "Audio write failed: ${e.message}")
            0
        }
    }

    /**
     * Synthesize and speak text using Piper (VITS) model.
     * Accepts an optional requestId; if provided it will be used as the active request id.
     */
    fun synthesizeAndSpeak(text: String, speed: Float = 1.0f, requestId: String? = null) {
        if (!isReady || tts == null) {
            Log.e(TAG, "Piper TTS is not ready")
            return
        }

        if( currentTask !=null && !currentTask!!.isDone){

            pendingText = text
            pendingSpeed = speed
            pendingRequestId = requestId

            stopRequest(activeRequestId ?:return)

            return
        }

        currentTask = synthesisExecutor.submit {
            val startNs = System.nanoTime()
            val rid = requestId ?: "piper_${startNs}"
            synchronized(this) {
                // Start this request as active (cancels previous implicitly by overriding activeRequestId)
                activeRequestId = rid
                isStopped = false
            }

            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()

                // Reset first-sample tracking
                firstSampleRecorded.set(false)
                firstSampleTimeNs = -1L

                val genConfig = GenerationConfig(sid = 0, speed = speed)
                val audio = tts!!.generateWithConfigAndCallback(
                    text = text,
                    config = genConfig,
                    callback = this::audioCallback
                )

                val endNs = System.nanoTime()
                val firstMs =
                    if (firstSampleTimeNs > 0) (firstSampleTimeNs - startNs) / 1_000_000 else -1L
                val totalMs = (endNs - startNs) / 1_000_000

                // Notify listener
                latencyCallback?.invoke(firstMs, totalMs)

                // Save to file for playback/sharing if generation produced samples
                val filename = "${context.filesDir.absolutePath}/piper_generated.wav"
                if (audio.samples.isNotEmpty()) {
                    audio.save(filename)

                }

                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error during synthesis:", e)
                latencyCallback?.invoke(-1L, -1L)
            } finally {
                // Clear activeRequestId only if it still matches this rid
                synchronized(this) {
                    if (activeRequestId == rid) {
                        activeRequestId = null
                    }
                }
                currentTask = null
                val nextText = pendingText
                val nextSpeed = pendingSpeed
                val nextRequestId =pendingRequestId

                pendingText = null
                pendingRequestId = null

                if(nextText != null){

                    synthesizeAndSpeak(
                        text=nextText,
                        speed=nextSpeed,
                        requestId=nextRequestId
                    )
                }

            }
        }
    }
    /**
     * Request stop of a specific request id. If the id matches the active request we'll set isStopped
     * and clear the activeRequestId so the audio callback will stop streaming.
     */
    fun stopRequest(requestId: String) {
        synchronized(this) {
            if (activeRequestId != null && activeRequestId == requestId) {
                isStopped = true
                activeRequestId = null
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping audio for request: ${e.message}")
                }
                Log.i(TAG, "Stopped Piper request: $requestId")
            } else {
                Log.i(TAG, "stopRequest: requestId $requestId does not match activeRequestId $activeRequestId")
            }
        }
    }

    fun stopCurrent(){
        synchronized(this){
            isStopped=true
            activeRequestId=null

            try{
                audioTrack?.pause()
                audioTrack?.flush()
            }catch(e:Exception){
                Log.e(TAG, "Error stopping audio",e)
            }
        }
    }

    /**
     * Check if TTS is ready
     */
    fun isReady(): Boolean = isReady

    /**
     * Stop synthesis and free resources (existing full cleanup)
     */
    fun stop() {
        isStopped = true
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            tts?.free()
            tts = null
            isReady = false
            activeRequestId = null
            Log.i(TAG, "Piper TTS stopped and resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Piper TTS: ${e.message}")
        }
    }
}