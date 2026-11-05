package com.copiloto.auto.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.copiloto.auto.R
import com.copiloto.core.dialog.Criterion
import com.copiloto.core.dialog.Display
import com.copiloto.core.dialog.VoiceCommand

private fun CarContext.icon(@DrawableRes res: Int, tint: CarColor? = null): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).apply { tint?.let { setTint(it) } }.build()

private fun stateLabel(state: VoiceState, idle: String) = when (state) {
    VoiceState.LISTENING -> "Te escucho…"
    VoiceState.THINKING -> "Buscando…"
    VoiceState.SPEAKING -> "Hablando…"
    VoiceState.IDLE -> idle
}

/** Base que repinta la plantilla cuando cambia el estado de la voz. */
abstract class VoiceAwareScreen(carContext: CarContext, protected val session: CopilotoSession) : Screen(carContext) {
    private val listener: () -> Unit = { invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = session.addStateListener(listener)
            override fun onStop(owner: LifecycleOwner) = session.removeStateListener(listener)
        })
    }

    protected fun micStrip(): ActionStrip = ActionStrip.Builder()
        .addAction(
            Action.Builder()
                .setIcon(carContext.icon(R.drawable.ic_mic, if (session.state == VoiceState.LISTENING) CarColor.RED else null))
                .setOnClickListener { session.pushToTalk() }
                .build(),
        )
        .build()
}

/** Pantalla principal: accesos grandes y micrófono. Todo se puede hacer también por voz. */
class HomeScreen(carContext: CarContext, session: CopilotoSession) : VoiceAwareScreen(carContext, session) {

    override fun onGetTemplate(): Template {
        if (!session.hasPermissions()) {
            return MessageTemplate.Builder("Copiloto necesita la ubicación y el micrófono para funcionar por voz.")
                .setTitle("Permisos")
                .setHeaderAction(Action.APP_ICON)
                .addAction(
                    Action.Builder()
                        .setTitle("Conceder en el móvil")
                        .setOnClickListener { session.requestPermissions { invalidate(); session.startConversation() } }
                        .build(),
                )
                .build()
        }

        fun item(title: String, text: String, @DrawableRes icon: Int, onClick: () -> Unit) =
            GridItem.Builder()
                .setTitle(title)
                .setText(text)
                .setImage(carContext.icon(icon), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener(onClick)
                .build()

        val items = ItemList.Builder()
            .addItem(item("Hablar", "\"Ok, dime\"", R.drawable.ic_mic) { session.pushToTalk() })
            .addItem(item("Gasolina", "La más barata", R.drawable.ic_fuel) { session.runCommand(VoiceCommand.FindFuel()) })
            .addItem(item("Comer", "Cerca de ti", R.drawable.ic_food) { session.runCommand(VoiceCommand.FindFood()) })
            .addItem(item("Rutas", "Alternativas", R.drawable.ic_route) { session.runCommand(VoiceCommand.PlanRoute()) })
            .apply {
                if (session.conversation.hasDestination) {
                    addItem(item("Continuar", "A mi destino", R.drawable.ic_navigate) {
                        session.runCommand(VoiceCommand.ContinueToDestination)
                    })
                }
            }
            .build()

        return GridTemplate.Builder()
            .setTitle(stateLabel(session.state, "Copiloto · di lo que necesitas"))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items)
            .setActionStrip(micStrip())
            .build()
    }
}

/** Resultados (gasolineras, restaurantes o rutas). Se elige por voz ("la segunda") o tocando. */
private const val MAX_ROWS = 6

class ResultsScreen(
    carContext: CarContext,
    session: CopilotoSession,
    private var display: Display,
) : VoiceAwareScreen(carContext, session) {

    fun update(d: Display) {
        display = d
        invalidate()
    }

    override fun onGetTemplate(): Template = when (val d = display) {
        is Display.Places -> places(d)
        is Display.Routes -> routes(d)
        is Display.Info -> info(d)
        Display.Home -> MessageTemplate.Builder("Sin resultados").setHeaderAction(Action.BACK).build()
    }

    private fun places(d: Display.Places): Template {
        val list = ItemList.Builder()
        d.items.forEachIndexed { i, it ->
            val row = Row.Builder()
                .setTitle("${i + 1}. ${it.title}  ·  ${it.badge}")
                .addText(it.subtitle)
                .setOnClickListener { session.runCommand(VoiceCommand.Select(index = i)) }
            it.location?.let { loc ->
                row.setMetadata(
                    Metadata.Builder().setPlace(
                        Place.Builder(CarLocation.create(loc.lat, loc.lng))
                            .setMarker(PlaceMarker.Builder().setLabel("${i + 1}").build())
                            .build(),
                    ).build(),
                )
            }
            list.addItem(row.build())
        }
        val locationGranted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return PlaceListMapTemplate.Builder()
            .setTitle(if (session.state == VoiceState.IDLE) d.title else stateLabel(session.state, d.title))
            .setHeaderAction(Action.BACK)
            .setItemList(list.build())
            .setCurrentLocationEnabled(locationGranted)
            .setActionStrip(micStrip())
            .build()
    }

    /** Correos, notificaciones o partidos: solo lectura, el contenido se escucha por voz. */
    private fun info(d: Display.Info): Template {
        val list = ItemList.Builder()
        d.items.take(MAX_ROWS).forEach {
            list.addItem(
                Row.Builder()
                    .setTitle(if (it.badge.isNotBlank()) "${it.title}  ·  ${it.badge}" else it.title)
                    .apply { if (it.subtitle.isNotBlank()) addText(it.subtitle) }
                    .build(),
            )
        }
        if (d.items.isEmpty()) list.setNoItemsMessage("Nada que mostrar")
        return ListTemplate.Builder()
            .setTitle(if (session.state == VoiceState.IDLE) d.title else stateLabel(session.state, d.title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .setActionStrip(micStrip())
            .build()
    }

    private fun routes(d: Display.Routes): Template {
        val list = ItemList.Builder()
        d.items.forEachIndexed { i, it ->
            list.addItem(
                Row.Builder()
                    .setTitle("${it.badge}  ·  ${it.title}")
                    .addText(it.subtitle)
                    .setOnClickListener { session.runCommand(VoiceCommand.Select(index = i)) }
                    .build(),
            )
        }
        val noTolls = Action.Builder()
            .setTitle("Sin peajes")
            .setOnClickListener { session.runCommand(VoiceCommand.Select(criterion = Criterion.NO_TOLLS)) }
            .build()
        return ListTemplate.Builder()
            .setTitle(if (session.state == VoiceState.IDLE) d.title else stateLabel(session.state, d.title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(noTolls)
                    .addAction(micStrip().actions.first())
                    .build(),
            )
            .build()
    }
}
