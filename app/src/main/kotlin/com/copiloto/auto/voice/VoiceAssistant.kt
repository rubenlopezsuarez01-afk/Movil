package com.copiloto.auto.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voz manos libres en Android Auto:
 *  - Habla con TextToSpeech por el altavoz del coche (con "ducking" de la música).
 *  - Escucha por el micrófono del coche (CarAudioRecord) y transcribe con el
 *    reconocedor de Android (Android 13+). Si no es posible, usa el micro por defecto.
 */
class VoiceAssistant(private val carContext: CarContext) {

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = carContext.getSystemService(AudioManager::class.java)

    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<Pair<String, () -> Unit>>()
    private val speakCallbacks = HashMap<String, () -> Unit>()
    private var utteranceId = 0
    private var speakFocus: AudioFocusRequest? = null

    private val tts: TextToSpeech = TextToSpeech(carContext) { status ->
        main.post {
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) configureTts()
            while (pendingSpeech.isNotEmpty()) {
                val (text, done) = pendingSpeech.removeFirst()
                speak(text, done)
            }
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private var listenFocus: AudioFocusRequest? = null
    private var carRecord: CarAudioRecord? = null
    private var carSource: ParcelFileDescriptor? = null
    private val recording = AtomicBoolean(false)
    private var useCarMic = true
    private var listening = false

    val isListening get() = listening

    private fun configureTts() {
        val es = Locale("es", "ES")
        if (tts.isLanguageAvailable(es) >= TextToSpeech.LANG_AVAILABLE) tts.language = es
        else tts.language = Locale("es")
        tts.setSpeechRate(1.05f)
        tts.setAudioAttributes(speechAttributes(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finishUtterance(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finishUtterance(id)
            override fun onError(id: String?, errorCode: Int) = finishUtterance(id)
        })
    }

    private fun finishUtterance(id: String?) = main.post {
        val cb = synchronized(speakCallbacks) { speakCallbacks.remove(id) } ?: return@post
        abandon(speakFocus); speakFocus = null
        cb()
    }

    /** Dice [text] y llama a [onDone] al terminar (en el hilo principal). */
    fun speak(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) { main.post(onDone); return }
        if (!ttsReady) {
            pendingSpeech.addLast(text to onDone)
            return
        }
        stopListening()
        speakFocus = requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        val id = "u${utteranceId++}"
        synchronized(speakCallbacks) { speakCallbacks[id] = onDone }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) finishUtterance(id)
    }

    fun stopSpeaking() {
        tts.stop()
        synchronized(speakCallbacks) { speakCallbacks.clear() }
        abandon(speakFocus); speakFocus = null
    }

    /** Escucha una frase. [onResult] recibe las hipótesis (vacía si no se oyó nada). */
    fun listen(onResult: (List<String>) -> Unit) {
        if (listening) return
        if (!hasMicPermission()) { onResult(emptyList()); return }
        if (!SpeechRecognizer.isRecognitionAvailable(carContext)) {
            Log.w(TAG, "No hay servicio de reconocimiento de voz")
            onResult(emptyList()); return
        }
        listening = true
        val viaCar = useCarMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5
        startRecognition(viaCar, onResult)
    }

    private fun startRecognition(viaCar: Boolean, onResult: (List<String>) -> Unit) {
        listening = true
        val sr = SpeechRecognizer.createSpeechRecognizer(carContext)
        recognizer = sr
        listenFocus = requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, AudioAttributes.USAGE_ASSISTANT)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        }
        if (viaCar) {
            val source = startCarMic()
            if (source == null) {
                useCarMic = false
                cleanupRecognition()
                startRecognition(false, onResult)
                return
            }
            carSource = source
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
        }

        var delivered = false
        fun deliver(results: List<String>) {
            if (delivered) return
            delivered = true
            main.removeCallbacksAndMessages(TIMEOUT_TOKEN)
            cleanupRecognition()
            onResult(results)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                deliver(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Error de reconocimiento $error (micro del coche=$viaCar)")
                val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (viaCar && !silence && !delivered) {
                    // El reconocedor no acepta audio externo: se reintenta con el micro por defecto.
                    delivered = true
                    useCarMic = false
                    cleanupRecognition()
                    main.post { startRecognition(false, onResult) }
                } else {
                    deliver(emptyList())
                }
            }

            override fun onEndOfSpeech() { stopCarMic() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(intent)
        // Seguridad: si el reconocedor no termina, se corta a los 12 s.
        main.postAtTime({ stopCarMic(); sr.stopListening() }, TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + 12_000)
        main.postAtTime({ deliver(emptyList()) }, TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + 16_000)
    }

    @SuppressLint("MissingPermission") // Se comprueba en listen().
    /** Arranca el micrófono del coche y devuelve el extremo de lectura de una tubería con PCM 16 kHz. */
    private fun startCarMic(): ParcelFileDescriptor? = try {
        val record = CarAudioRecord.create(carContext)
        val pipe = ParcelFileDescriptor.createPipe()
        carRecord = record
        record.startRecording()
        recording.set(true)
        Thread({
            val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                try {
                    while (recording.get()) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (n > 0) out.write(buffer, 0, n)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Fin de la captura: ${e.message}")
                }
            }
            runCatching { record.stopRecording() }
        }, "car-mic").start()
        pipe[0]
    } catch (e: Exception) {
        Log.w(TAG, "No se puede usar el micrófono del coche", e)
        null
    }

    private fun stopCarMic() {
        recording.set(false)
        carRecord = null
    }

    private fun cleanupRecognition() {
        stopCarMic()
        carSource?.let { runCatching { it.close() } }
        carSource = null
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        abandon(listenFocus); listenFocus = null
        listening = false
    }

    fun stopListening() {
        if (!listening) return
        main.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        cleanupRecognition()
    }

    fun release() {
        stopListening()
        stopSpeaking()
        tts.shutdown()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun speechAttributes(usage: Int) = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus(gain: Int, usage: Int): AudioFocusRequest? {
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(speechAttributes(usage))
            .setOnAudioFocusChangeListener { }
            .build()
        return if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else null
    }

    private fun abandon(req: AudioFocusRequest?) {
        req?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private companion object {
        const val TAG = "CopilotoVoz"
        val TIMEOUT_TOKEN = Any()
    }
}
