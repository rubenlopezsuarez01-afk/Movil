package com.copiloto.auto.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.copiloto.auto.data.AndroidServices
import com.copiloto.auto.data.Prefs
import com.copiloto.auto.voice.VoiceAssistant
import com.copiloto.core.dialog.Conversation
import com.copiloto.core.dialog.Display
import com.copiloto.core.dialog.NavTarget
import com.copiloto.core.dialog.Turn
import com.copiloto.core.dialog.VoiceCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Estado visible en pantalla mientras se conversa. */
enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Sesión de Android Auto. Orquesta el bucle de conversación:
 * hablar → escuchar → entender → buscar → mostrar/hablar → (navegar o volver a escuchar).
 */
class CopilotoSession : Session() {

    lateinit var voice: VoiceAssistant
        private set
    lateinit var conversation: Conversation
        private set

    var state: VoiceState = VoiceState.IDLE
        private set
    private val stateListeners = mutableListOf<() -> Unit>()
    private var job: Job? = null
    private var silentTurns = 0
    private var greeted = false

    override fun onCreateScreen(intent: Intent): Screen {
        val prefs = Prefs(carContext)
        voice = VoiceAssistant(carContext)
        conversation = Conversation(AndroidServices(carContext), prefs.load()) { prefs.save(it) }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Al abrir la app ("Ok Google, abre Copiloto") o al volver desde el navegador,
                // la conversación arranca sola: no hace falta tocar la pantalla.
                conversation.settings = prefs.load()
                if (hasPermissions()) startConversation()
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
                voice.stopListening()
                voice.stopSpeaking()
                setState(VoiceState.IDLE)
            }

            override fun onDestroy(owner: LifecycleOwner) = voice.release()
        })
        return HomeScreen(carContext, this)
    }

    /**
     * Si Copiloto ya está en pantalla y se vuelve a pedir "Ok Google, abre Copiloto"
     * (por ejemplo con el botón de voz del volante), vuelve a escuchar.
     */
    override fun onNewIntent(intent: Intent) {
        if (!hasPermissions()) return
        if (state == VoiceState.IDLE) startConversation() else pushToTalk()
    }

    fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(carContext, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissions(onGranted: () -> Unit) {
        carContext.requestPermissions(REQUIRED_PERMISSIONS) { granted, _ ->
            if (granted.containsAll(REQUIRED_PERMISSIONS)) onGranted()
            else CarToast.makeText(carContext, "Concede ubicación y micrófono en el móvil", CarToast.LENGTH_LONG).show()
        }
    }

    fun addStateListener(l: () -> Unit) { stateListeners += l }
    fun removeStateListener(l: () -> Unit) { stateListeners -= l }

    private fun setState(s: VoiceState) {
        if (state == s) return
        state = s
        stateListeners.toList().forEach { it() }
    }

    /** Saludo + escucha. */
    fun startConversation() {
        if (state != VoiceState.IDLE) return
        silentTurns = 0
        deliver(conversation.greeting(firstTime = !greeted))
        greeted = true
    }

    /** Pulsar el micro: interrumpe lo que esté diciendo y escucha. */
    fun pushToTalk() {
        job?.cancel()
        voice.stopSpeaking()
        voice.stopListening()
        silentTurns = 0
        listen()
    }

    /** Atajos táctiles (por si el copiloto prefiere tocar). Pasan por el mismo diálogo. */
    fun runCommand(cmd: VoiceCommand) {
        job?.cancel()
        voice.stopSpeaking()
        voice.stopListening()
        setState(VoiceState.THINKING)
        job = lifecycleScope.launch { deliver(conversation.handle(cmd)) }
    }

    private fun listen() {
        setState(VoiceState.LISTENING)
        voice.listen { hypotheses ->
            if (hypotheses.isEmpty()) {
                silentTurns++
                if (silentTurns >= 2) {
                    setState(VoiceState.IDLE)
                    CarToast.makeText(carContext, "Pulsa el micro para hablar", CarToast.LENGTH_SHORT).show()
                } else {
                    deliver(Turn("¿Sigues ahí? Dime qué necesitas."))
                }
                return@listen
            }
            silentTurns = 0
            Log.d(TAG, "Oído: $hypotheses")
            setState(VoiceState.THINKING)
            job = lifecycleScope.launch { deliver(conversation.handle(hypotheses)) }
        }
    }

    private fun deliver(turn: Turn) {
        turn.display?.let { show(it) }
        setState(VoiceState.SPEAKING)
        voice.speak(turn.speech) {
            when {
                turn.navigateTo != null -> { setState(VoiceState.IDLE); navigate(turn.navigateTo!!) }
                turn.keepListening -> listen()
                else -> setState(VoiceState.IDLE)
            }
        }
    }

    private fun show(display: Display) {
        val sm = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        when (display) {
            Display.Home -> sm.popToRoot()
            is Display.Places, is Display.Routes, is Display.Info -> {
                val top = sm.top
                if (top is ResultsScreen) {
                    // Se actualiza la pantalla existente para no consumir pasos de la plantilla.
                    top.update(display)
                } else {
                    sm.popToRoot()
                    sm.push(ResultsScreen(carContext, this, display))
                }
            }
        }
    }

    /** Entrega la navegación al navegador predeterminado (Google Maps, Waze...). */
    private fun navigate(target: NavTarget) {
        val (lat, lng) = target.location
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(target.name)})")
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la navegación", e)
            CarToast.makeText(carContext, "No se pudo abrir el navegador", CarToast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "Copiloto"
        val REQUIRED_PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
    }
}
