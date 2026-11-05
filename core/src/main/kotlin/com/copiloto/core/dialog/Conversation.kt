package com.copiloto.core.dialog

import com.copiloto.core.LatLng
import com.copiloto.core.apps.AppIntent
import com.copiloto.core.apps.AppNotification
import com.copiloto.core.apps.Apps
import com.copiloto.core.sports.Espn
import com.copiloto.core.sports.League
import com.copiloto.core.sports.Match
import com.copiloto.core.sports.MatchSpeech
import com.copiloto.core.sports.MatchState
import com.copiloto.core.sports.Teams
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import com.copiloto.core.Speech
import com.copiloto.core.distanceMeters
import com.copiloto.core.distanceToPolylineMeters
import com.copiloto.core.farthestPointFrom
import com.copiloto.core.food.Overpass
import com.copiloto.core.food.RankedRestaurant
import com.copiloto.core.food.Restaurant
import com.copiloto.core.fuel.FuelRanker
import com.copiloto.core.fuel.FuelSort
import com.copiloto.core.fuel.FuelStation
import com.copiloto.core.fuel.FuelType
import com.copiloto.core.fuel.RankedStation
import com.copiloto.core.prettyName
import com.copiloto.core.route.RouteOption
import com.copiloto.core.samplePolyline

/** Lugar con nombre legible. */
data class Place(val name: String, val location: LatLng)

/** Acceso a datos externos. La app Android lo implementa con GPS, Geocoder y APIs abiertas. */
interface Services {
    suspend fun currentLocation(): LatLng?

    /** Gasolineras de las provincias que contienen alguno de [points]. */
    suspend fun fuelStations(points: List<LatLng>, fuel: FuelType): List<FuelStation>
    suspend fun restaurants(command: VoiceCommand.FindFood, center: LatLng, radiusM: Int): List<Restaurant>
    suspend fun geocode(query: String, near: LatLng?): Place?
    suspend fun routes(from: LatLng, to: LatLng, avoidTolls: Boolean, avoidMotorways: Boolean): List<RouteOption>

    /** ¿El usuario ha dado permiso para leer notificaciones? */
    fun notificationAccess(): Boolean = false

    /** Notificaciones recientes del móvil, de la más nueva a la más antigua. */
    suspend fun notifications(): List<AppNotification> = emptyList()

    /** Partidos entre [from] y [to] (ambos incluidos) de las competiciones indicadas. */
    suspend fun matches(from: LocalDate, to: LocalDate, leagues: List<League>): List<Match> = emptyList()

    fun zone(): ZoneId = ZoneId.of("Europe/Madrid")
    fun today(): LocalDate = LocalDate.now(zone())
}

data class Settings(
    val fuel: FuelType = FuelType.GASOLINA_95,
    val radiusKm: Int = 10,
    val tankLitres: Int = 50,
    val home: String? = null,
    val work: String? = null,
    /** Equipo favorito ("¿juega mi equipo hoy?"). */
    val team: String? = null,
)

/** Lo que se muestra en la pantalla del coche. */
sealed interface Display {
    data object Home : Display
    data class Places(val title: String, val items: List<Item>) : Display
    data class Routes(val title: String, val items: List<Item>) : Display
    /** Lista solo informativa (notificaciones, partidos). */
    data class Info(val title: String, val items: List<Item>) : Display

    data class Item(val title: String, val subtitle: String, val location: LatLng?, val badge: String)
}

/** Destino de navegación. [resumeLater] = parada intermedia, luego se puede "continuar al destino". */
data class NavTarget(val name: String, val location: LatLng, val resumeLater: Boolean)

/** Respuesta a un turno de conversación. */
data class Turn(
    val speech: String,
    val display: Display? = null,
    val navigateTo: NavTarget? = null,
    /** Si hay que volver a escuchar al usuario tras hablar. */
    val keepListening: Boolean = true,
)

/**
 * Gestor de diálogo: mantiene el contexto (resultados mostrados, destino,
 * rutas, pregunta pendiente) para que el conductor pueda encadenar órdenes
 * como "gasolinera más barata" → "llévame a la segunda" → "continuar a mi destino".
 */
class Conversation(
    private val services: Services,
    var settings: Settings = Settings(),
    private val onSettingsChanged: (Settings) -> Unit = {},
) {
    private sealed interface Choice {
        val name: String
        val location: LatLng
        data class Fuel(val r: RankedStation) : Choice {
            override val name get() = r.station.brand.prettyName()
            override val location get() = r.station.location
        }
        data class Food(val r: RankedRestaurant) : Choice {
            override val name get() = r.restaurant.name
            override val location get() = r.restaurant.location
        }
        data class Route(val option: RouteOption, val index: Int) : Choice {
            override val name get() = "ruta ${index + 1}"
            override val location get() = option.geometry.lastOrNull() ?: LatLng(0.0, 0.0)
        }
    }

    private sealed interface Pending {
        data class AskDestination(val avoidTolls: Boolean, val avoidMotorways: Boolean) : Pending
        data object ConfirmContinue : Pending
    }

    private var choices: List<Choice> = emptyList()
    private var page = 0
    private var destination: Place? = null
    private var routes: List<RouteOption> = emptyList()
    private var activeRoute: List<LatLng>? = null
    private var stopoverActive = false
    private var pending: Pending? = null
    private var lastSpeech = ""
    private var misunderstood = 0
    /** Lectura en curso (correos, notificaciones, partidos) para "sigue" / "más". */
    private var readout: List<String> = emptyList()
    private var readoutPos = 0
    private var readoutActive = false

    val hasDestination get() = destination != null

    /** Saludo al abrir la app (con "Ok Google, abre Copiloto" o al volver desde Maps). */
    fun greeting(firstTime: Boolean): Turn {
        val dest = destination
        val text = when {
            stopoverActive && dest != null -> {
                pending = Pending.ConfirmContinue
                "Hola de nuevo. ¿Seguimos hacia ${dest.name}?"
            }
            firstTime -> "Hola, soy tu copiloto. Pídeme la gasolinera más barata, dónde comer o rutas para llegar a un sitio."
            else -> "Te escucho."
        }
        return say(text)
    }

    /** Procesa las hipótesis del reconocedor de voz (de más a menos probable). */
    suspend fun handle(hypotheses: List<String>): Turn {
        val candidates = hypotheses.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return say("No te he oído. ¿Qué necesitas?")
        val parsed = candidates.map { it to CommandParser.parse(it) }
        val (text, cmd) = parsed.firstOrNull { it.second !is VoiceCommand.Unknown } ?: parsed.first()
        return handle(cmd, text)
    }

    suspend fun handle(cmd: VoiceCommand, rawText: String = ""): Turn {
        val p = pending
        // Respuestas a una pregunta pendiente.
        if (p is Pending.AskDestination && (cmd is VoiceCommand.Unknown || (cmd is VoiceCommand.PlanRoute && cmd.destination == null))) {
            pending = null
            val dest = CommandParser.asDestination(rawText)
                ?: return say("¿A dónde quieres ir?").also { pending = p }
            return planRoute(VoiceCommand.PlanRoute(dest, p.avoidTolls, p.avoidMotorways))
        }
        if (p is Pending.ConfirmContinue) {
            pending = null
            if (cmd is VoiceCommand.Yes) return continueToDestination()
            if (cmd is VoiceCommand.No) { stopoverActive = false; return say("Vale. ¿En qué te ayudo?") }
        }

        // Cualquier otra orden cancela la pregunta pendiente.
        pending = null
        if (readoutActive) {
            when (cmd) {
                VoiceCommand.MoreOptions, VoiceCommand.Yes -> return continueReadout()
                VoiceCommand.No -> { readoutActive = false; return say("Vale. ¿Algo más?") }
                VoiceCommand.Repeat -> {}
                else -> readoutActive = false
            }
        }
        if (cmd !is VoiceCommand.Unknown) misunderstood = 0
        return when (cmd) {
            is VoiceCommand.FindFuel -> findFuel(cmd)
            is VoiceCommand.FindFood -> findFood(cmd)
            is VoiceCommand.PlanRoute -> planRoute(cmd)
            is VoiceCommand.Select -> select(cmd)
            is VoiceCommand.Details -> details(cmd.index)
            is VoiceCommand.SetFuel -> {
                settings = settings.copy(fuel = cmd.fuel)
                onSettingsChanged(settings)
                say("Anotado: a partir de ahora buscaré precios de ${cmd.fuel.spoken}.")
            }
            is VoiceCommand.SetTeam -> {
                settings = settings.copy(team = cmd.team)
                onSettingsChanged(settings)
                say("Anotado, tu equipo es el ${cmd.team.replaceFirstChar { it.uppercase() }}. Pregúntame '¿juega mi equipo hoy?'.")
            }
            is VoiceCommand.OpenApp -> openApp(cmd.name)
            is VoiceCommand.ReadNotifications -> readNotifications(cmd.app)
            is VoiceCommand.Matches -> matches(cmd)
            VoiceCommand.ContinueToDestination -> continueToDestination()
            VoiceCommand.MoreOptions -> moreOptions()
            VoiceCommand.Repeat -> Turn(lastSpeech.ifBlank { "Todavía no te he dicho nada." })
            VoiceCommand.Help -> say(
                "Puedes decirme, por ejemplo: gasolinera más barata, diésel más barato de camino, " +
                    "dónde comer, una pizzería cerca, rutas a Valencia sin peajes, llévame a la segunda, " +
                    "dame detalles de la primera, continuar a mi destino, lee mis correos, " +
                    "qué partidos hay hoy, cómo va el Madrid o abre Flashscore. Para terminar, di gracias.",
            )
            VoiceCommand.Yes -> if (choices.isNotEmpty()) select(VoiceCommand.Select(index = 0)) else say("¿En qué te ayudo?")
            VoiceCommand.No -> if (choices.size > (page + 1) * PAGE) moreOptions() else say("Vale. ¿Necesitas algo más?")
            VoiceCommand.Stop -> Turn("De acuerdo. Si me necesitas, abre Copiloto.", keepListening = false)
            is VoiceCommand.Unknown -> {
                misunderstood++
                if (misunderstood >= 3) {
                    misunderstood = 0
                    Turn("No te estoy entendiendo. Lo dejo por ahora.", keepListening = false)
                } else {
                    say("Perdona, no te he entendido. Puedes pedirme la gasolinera más barata, dónde comer o rutas a un destino.")
                }
            }
        }
    }

    // ---------------------------------------------------------------- Apps del móvil

    /**
     * Android Auto no deja mostrar apps normales del móvil en la pantalla del coche,
     * así que "abre X" se resuelve haciendo por voz lo que harías en esa app.
     */
    private suspend fun openApp(name: String): Turn = when (Apps.classify(name)) {
        AppIntent.FUEL -> {
            val t = findFuel(VoiceCommand.FindFuel())
            t.copy(speech = "No puedo abrir esa app en la pantalla del coche, pero uso los mismos precios oficiales. ${t.speech}")
        }
        AppIntent.MAIL -> readNotifications("correo")
        AppIntent.SPORTS -> {
            val t = matches(VoiceCommand.Matches())
            t.copy(speech = "Te cuento los partidos de hoy. ${t.speech}")
        }
        AppIntent.OTHER -> {
            val fromApp = if (services.notificationAccess()) Apps.filter(safeNotifications(), name) else emptyList()
            if (fromApp.isNotEmpty()) readNotifications(name)
            else say(
                "Android Auto no deja abrir $name en la pantalla del coche mientras conduces. " +
                    "Si es una app compatible con Android Auto, como Spotify, WhatsApp o Waze, pídeselo a Google: " +
                    "'Ok Google, abre $name'. Si quieres, te leo sus notificaciones cuando lleguen.",
            )
        }
    }

    private suspend fun safeNotifications(): List<AppNotification> = runCatching { services.notifications() }.getOrDefault(emptyList())

    private suspend fun readNotifications(app: String?): Turn {
        if (!services.notificationAccess()) {
            return say(
                "Para leerte el correo y las notificaciones necesito permiso. Cuando estés parado, abre Copiloto en el móvil " +
                    "y pulsa 'Permitir leer notificaciones'.",
            )
        }
        val isMail = app != null && Apps.classify(app) == AppIntent.MAIL
        val list = Apps.filter(safeNotifications(), app).take(MAX_READOUT)
        if (list.isEmpty()) {
            return say(
                when {
                    isMail -> "No tienes correos nuevos."
                    app != null -> "No tengo notificaciones recientes de $app."
                    else -> "No tienes notificaciones nuevas."
                },
            )
        }
        val includeApp = !isMail && list.map { it.appName }.distinct().size > 1
        val header = when {
            isMail -> if (list.size == 1) "Tienes un correo." else "Tienes ${list.size} correos recientes."
            app != null -> "De ${list.first().appName} tienes ${list.size} ${if (list.size == 1) "aviso" else "avisos"}."
            else -> "Tienes ${list.size} notificaciones."
        }
        val title = if (isMail) "Correo" else app?.replaceFirstChar { it.uppercase() } ?: "Notificaciones"
        val display = Display.Info(title, list.map {
            Display.Item(it.title.ifBlank { it.appName }, it.text.take(80), null, it.appName)
        })
        return startReadout(header, list.map { Apps.spoken(it, includeApp) }, display)
    }

    // ---------------------------------------------------------------- Partidos

    private suspend fun matches(cmd: VoiceCommand.Matches): Turn {
        val team = cmd.team ?: if (cmd.myTeam) settings.team ?: return say(
            "No sé cuál es tu equipo. Dímelo así: 'mi equipo es el Betis'.",
        ) else null
        val league = cmd.competition?.let { c -> Espn.LEAGUES.firstOrNull { it.spoken == c } }
        val leagues = when {
            league != null -> listOf(league)
            team != null -> Espn.LEAGUES.filter { it.path.startsWith("soccer/") }
            else -> Espn.LEAGUES.take(TOP_LEAGUES)
        }
        val day = services.today().plusDays(cmd.dayOffset.toLong())
        val zone = services.zone()
        val dayWord = when (cmd.dayOffset) { -1 -> "ayer"; 1 -> "mañana"; else -> "hoy" }
        val found = try {
            services.matches(day, day, leagues)
        } catch (e: Exception) {
            return networkError("los partidos")
        }

        if (team != null) {
            val teamName = team.replaceFirstChar { it.uppercase() }
            val mine = found.filter { Teams.matches(it, team) }.sortedBy { it.start }
            if (mine.isNotEmpty()) {
                val lines = mine.map { "${MatchSpeech.line(it, zone)}, ${it.competition}" }
                return say(
                    (if (mine.first().state == MatchState.SCHEDULED) "$dayWord juega el $teamName: " else "") +
                        lines.joinToString(". ") + ".",
                    Display.Info(teamName, mine.map { item(it, zone) }),
                )
            }
            // No juega ese día: se busca el próximo partido en la próxima semana.
            if (cmd.dayOffset >= 0) {
                val next = runCatching { services.matches(day.plusDays(1), day.plusDays(8), leagues) }.getOrDefault(emptyList())
                    .filter { Teams.matches(it, team) && it.state == MatchState.SCHEDULED }
                    .minByOrNull { it.start }
                if (next != null) {
                    val local = next.start.atZone(zone)
                    val weekday = local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es", "ES"))
                    return say(
                        "$dayWord no juega el $teamName. Su próximo partido es el $weekday ${local.dayOfMonth}: " +
                            "${MatchSpeech.line(next, zone)}, en ${next.competition}.",
                        Display.Info(teamName, listOf(item(next, zone))),
                    )
                }
            }
            return say("No encuentro partidos del $teamName $dayWord.")
        }

        if (found.isEmpty()) {
            return say("No hay partidos ${league?.let { "de ${it.spoken} " } ?: "de las competiciones principales "}$dayWord.")
        }
        val sorted = found.sortedWith(compareBy<Match>({ it.state != MatchState.LIVE }, { it.priority }, { it.start }))
        val live = sorted.count { it.state == MatchState.LIVE }
        var lastComp = ""
        val lines = sorted.map { m ->
            val prefix = if (m.competition != lastComp) "En ${m.competition}: " else ""
            lastComp = m.competition
            prefix + MatchSpeech.line(m, zone)
        }
        val header = buildString {
            append("${if (cmd.dayOffset == -1) "Ayer hubo" else "${dayWord.replaceFirstChar { it.uppercase() }} hay"} ${sorted.size} ${if (sorted.size == 1) "partido" else "partidos"}")
            if (live > 0) append(", $live en juego ahora")
            append(".")
        }
        return startReadout(header, lines, Display.Info("Partidos $dayWord", sorted.take(MAX_READOUT).map { item(it, zone) }))
    }

    private fun item(m: Match, zone: ZoneId) =
        Display.Item("${m.home} - ${m.away}", m.competition, null, MatchSpeech.shortLine(m, zone))

    // ---------------------------------------------------------------- Lectura por partes

    private fun startReadout(header: String, lines: List<String>, display: Display): Turn {
        readout = lines
        readoutPos = 0
        readoutActive = true
        return nextReadoutChunk(header, display)
    }

    private fun continueReadout(): Turn =
        if (readoutPos >= readout.size) { readoutActive = false; say("Eso es todo. ¿Algo más?") }
        else nextReadoutChunk("", null)

    private fun nextReadoutChunk(header: String, display: Display?): Turn {
        val chunk = readout.drop(readoutPos).take(READOUT_CHUNK)
        readoutPos += chunk.size
        val tail = if (readoutPos < readout.size) " ¿Sigo?" else { readoutActive = false; " ¿Algo más?" }
        return say("$header ${chunk.joinToString(". ") { it.trimEnd('.') }}.$tail".trim(), display)
    }

    // ---------------------------------------------------------------- Gasolina

    private suspend fun findFuel(cmd: VoiceCommand.FindFuel): Turn {
        val here = services.currentLocation() ?: return noLocation()
        val fuel = cmd.fuel ?: settings.fuel
        val useRoute = cmd.onRoute && activeRoute != null
        val route = if (useRoute) remainingRoute(here) else null
        val points = route?.let { samplePolyline(it, 40_000.0) } ?: listOf(here)
        val all = try {
            services.fuelStations(points, fuel)
        } catch (e: Exception) {
            return networkError("los precios de carburante")
        }

        var radiusKm = cmd.radiusKm ?: settings.radiusKm
        var ranked = FuelRanker.rank(all, here, radiusKm * 1000.0, cmd.sort, route)
        var widened = false
        while (ranked.isEmpty() && route == null && radiusKm < 50) {
            radiusKm = minOf(50, radiusKm * 2)
            ranked = FuelRanker.rank(all, here, radiusKm * 1000.0, cmd.sort, null)
            widened = true
        }
        if (ranked.isEmpty()) return say("No he encontrado gasolineras con ${fuel.spoken} cerca. ¿Quieres que busque otra cosa?")

        choices = ranked.take(MAX_RESULTS).map { Choice.Fuel(it) }
        page = 0
        val best = ranked.first()
        val title = "${fuel.spoken.replaceFirstChar { it.uppercase() }} · ${if (cmd.sort == FuelSort.CHEAPEST) "más barata" else "más cercana"}"
        val display = Display.Places(title, choices.map { item(it) })

        if (cmd.goNow) return select(VoiceCommand.Select(index = 0)).copy(display = display)

        val intro = buildString {
            if (cmd.onRoute && !useRoute) append("No tengo una ruta activa, así que busco cerca de ti. ")
            if (widened) append("No había nada cerca, he ampliado a $radiusKm kilómetros. ")
            append(if (useRoute) "De camino, " else "")
            append(
                if (cmd.sort == FuelSort.CHEAPEST) "La más barata es ${describeFuel(best)}."
                else "La más cercana es ${describeFuel(best)}.",
            )
            val savings = FuelRanker.savingsVsAverage(ranked, settings.tankLitres.toDouble())
            if (cmd.sort == FuelSort.CHEAPEST && savings >= 1.0) {
                append(" Llenando ${settings.tankLitres} litros ahorras unos ${Speech.euros(savings)} frente a la media de la zona.")
            }
        }
        return say("$intro ${speakPage(skipFirst = true)}", display)
    }

    private fun describeFuel(r: RankedStation): String =
        "${r.station.brand.prettyName()} en ${r.station.address.prettyName()}, a ${Speech.distance(r.distanceM)}, " +
            "a ${Speech.price(r.station.price)} el litro"

    // ---------------------------------------------------------------- Comida

    private suspend fun findFood(cmd: VoiceCommand.FindFood): Turn {
        val here = services.currentLocation() ?: return noLocation()
        val useRoute = cmd.onRoute && activeRoute != null
        val route = if (useRoute) remainingRoute(here) else null
        // De camino: se busca alrededor de un punto unos km por delante.
        val center = route?.let { samplePolyline(it, 8_000.0).getOrNull(1) } ?: here
        val radiusKm = cmd.radiusKm ?: if (useRoute) 8 else minOf(settings.radiusKm, 5)
        val list = try {
            services.restaurants(cmd, center, radiusKm * 1000)
        } catch (e: Exception) {
            return networkError("los restaurantes")
        }
        val ranked = Overpass.rank(list, here, route)
        if (ranked.isEmpty()) {
            val what = cmd.cuisine?.spoken ?: "sitios para comer"
            return say("No he encontrado $what a menos de $radiusKm kilómetros. Prueba con otro tipo de comida o di 'más lejos'.")
        }
        choices = ranked.take(MAX_RESULTS).map { Choice.Food(it) }
        page = 0
        val title = cmd.cuisine?.spoken?.replaceFirstChar { it.uppercase() } ?: "Dónde comer"
        val display = Display.Places(title, choices.map { item(it) })
        if (cmd.goNow) return select(VoiceCommand.Select(index = 0)).copy(display = display)
        val intro = if (useRoute) "De camino tienes ${choices.size} opciones." else "Cerca tienes ${choices.size} opciones."
        return say("$intro ${speakPage(skipFirst = false)}", display)
    }

    // ---------------------------------------------------------------- Rutas

    private suspend fun planRoute(cmd: VoiceCommand.PlanRoute): Turn {
        val here = services.currentLocation() ?: return noLocation()
        val query = when (cmd.destination) {
            null -> null
            "casa", "mi casa" -> settings.home ?: return say("No tengo guardada tu casa. Puedes guardarla en la app del móvil. ¿A dónde vamos?")
                .also { pending = Pending.AskDestination(cmd.avoidTolls, cmd.avoidMotorways) }
            "trabajo", "el trabajo", "mi trabajo", "oficina", "la oficina" -> settings.work ?: return say("No tengo guardado tu trabajo. ¿A dónde vamos?")
                .also { pending = Pending.AskDestination(cmd.avoidTolls, cmd.avoidMotorways) }
            else -> cmd.destination
        }
        val dest = if (query != null) {
            try {
                services.geocode(query, here)
            } catch (e: Exception) {
                return networkError("el destino")
            } ?: return say("No encuentro $query. Dime el destino otra vez, por ejemplo con la ciudad.")
                .also { pending = Pending.AskDestination(cmd.avoidTolls, cmd.avoidMotorways) }
        } else {
            destination ?: return say("¿A dónde quieres ir?")
                .also { pending = Pending.AskDestination(cmd.avoidTolls, cmd.avoidMotorways) }
        }

        var options = try {
            services.routes(here, dest.location, cmd.avoidTolls, cmd.avoidMotorways)
        } catch (e: Exception) {
            return networkError("las rutas")
        }
        if (options.isEmpty()) return say("No he podido calcular rutas hasta ${dest.name}.")
        // Si todas tienen peaje, se añade una opción sin peajes para comparar.
        if (!cmd.avoidTolls && options.all { it.hasTolls }) {
            runCatching { services.routes(here, dest.location, avoidTolls = true, avoidMotorways = false) }
                .getOrNull()?.firstOrNull()?.takeIf { !it.hasTolls }?.let { options = options + it.copy(tollFree = true) }
        }
        destination = dest
        stopoverActive = false
        routes = options.take(4)
        activeRoute = routes.first().geometry
        choices = routes.mapIndexed { i, r -> Choice.Route(r, i) }
        page = 0

        val fastest = routes.minBy { it.durationS }
        val spoken = routes.mapIndexed { i, r ->
            buildString {
                append("La ${Speech.ordinal(i)}")
                if (r === fastest && routes.size > 1) append(", la más rápida")
                append(": ${Speech.duration(r.durationS)}, ${Speech.distance(r.distanceM)}")
                if (r.summary.isNotBlank()) append(", por ${r.summary}")
                append(if (r.hasTolls) ", con peajes" else if (routes.any { it.hasTolls }) ", sin peajes" else "")
                append(".")
            }
        }
        val header = if (routes.size == 1) "Hacia ${dest.name} hay una ruta." else "Hacia ${dest.name} tengo ${routes.size} rutas."
        val question = if (routes.size == 1) "¿Te llevo?" else "¿Cuál prefieres?"
        val display = Display.Routes("Rutas a ${dest.name}", choices.map { item(it) })
        return say("$header ${spoken.joinToString(" ")} $question", display)
    }

    // ---------------------------------------------------------------- Selección

    private fun select(cmd: VoiceCommand.Select): Turn {
        if (choices.isEmpty()) {
            return if (cmd.criterion == Criterion.NO_TOLLS) say("¿A dónde quieres ir sin peajes?")
                .also { pending = Pending.AskDestination(avoidTolls = true, avoidMotorways = false) }
            else say("Primero dime qué busco: gasolinera, dónde comer o una ruta.")
        }
        val index = when (cmd.criterion) {
            null -> cmd.index ?: 0
            Criterion.LAST -> choices.lastIndex
            Criterion.CHEAPEST -> choices.indices.minByOrNull { (choices[it] as? Choice.Fuel)?.r?.station?.price ?: Double.MAX_VALUE } ?: 0
            Criterion.CLOSEST -> choices.indices.minByOrNull { distanceOf(choices[it]) } ?: 0
            Criterion.FASTEST -> choices.indices.minByOrNull { (choices[it] as? Choice.Route)?.option?.durationS ?: Double.MAX_VALUE } ?: 0
            Criterion.SHORTEST -> choices.indices.minByOrNull { (choices[it] as? Choice.Route)?.option?.distanceM ?: Double.MAX_VALUE } ?: 0
            Criterion.NO_TOLLS -> choices.indexOfFirst { it is Choice.Route && !it.option.hasTolls }.takeIf { it >= 0 }
                ?: return say("Todas las rutas que tengo pasan por peajes. Pídeme 'rutas sin peajes' y las recalculo.")
        }
        val choice = choices.getOrNull(index)
            ?: return say("Solo tengo ${choices.size} opciones. ¿Cuál quieres?")

        return when (choice) {
            is Choice.Route -> chooseRoute(choice)
            else -> {
                val resume = destination != null
                stopoverActive = resume
                val extra = if (resume) " Cuando acabes, abre Copiloto y seguimos hacia ${destination!!.name}." else ""
                Turn(
                    "Vamos a ${choice.name}, a ${Speech.distance(distanceOf(choice))}.$extra",
                    navigateTo = NavTarget(choice.name, choice.location, resumeLater = resume),
                    keepListening = false,
                )
            }
        }
    }

    private fun chooseRoute(choice: Choice.Route): Turn {
        val dest = destination ?: return say("He perdido el destino. ¿A dónde vamos?")
        val main = routes.first()
        activeRoute = choice.option.geometry
        // La ruta principal (o una pedida sin peajes) se delega tal cual al navegador.
        if (choice.index == 0) {
            stopoverActive = false
            return Turn("Perfecto, en marcha hacia ${dest.name}. Llegada en ${Speech.duration(main.durationS)}.",
                navigateTo = NavTarget(dest.name, dest.location, resumeLater = false), keepListening = false)
        }
        // Para forzar una alternativa se navega primero a un punto característico de esa ruta.
        val via = farthestPointFrom(choice.option.geometry, main.geometry)
        if (via == null || distanceToRoute(via, main.geometry) < 1_000) {
            stopoverActive = false
            return Turn("En marcha hacia ${dest.name}. Esta ruta es casi igual que la principal.",
                navigateTo = NavTarget(dest.name, dest.location, resumeLater = false), keepListening = false)
        }
        stopoverActive = true
        val road = choice.option.summary.ifBlank { "esa variante" }
        return Turn(
            "Vale, vamos por $road. Te guío primero hasta un punto de esa ruta; " +
                "cuando llegues, abre Copiloto y di 'continuar a mi destino'.",
            navigateTo = NavTarget("Vía $road", via, resumeLater = true),
            keepListening = false,
        )
    }

    private fun continueToDestination(): Turn {
        val dest = destination ?: return say("No tengo un destino guardado. ¿A dónde vamos?")
            .also { pending = Pending.AskDestination(avoidTolls = false, avoidMotorways = false) }
        stopoverActive = false
        return Turn("Retomamos la ruta hacia ${dest.name}.",
            navigateTo = NavTarget(dest.name, dest.location, resumeLater = false), keepListening = false)
    }

    private fun details(index: Int?): Turn {
        if (choices.isEmpty()) return say("No tengo ningún resultado del que darte detalles.")
        val i = index ?: (page * PAGE)
        val c = choices.getOrNull(i) ?: return say("Solo tengo ${choices.size} opciones.")
        val text = when (c) {
            is Choice.Fuel -> buildString {
                val s = c.r.station
                append("${c.name}, en ${s.address.prettyName()}, ${s.town.prettyName()}. ")
                append("${Speech.price(s.price)} el litro, a ${Speech.distance(c.r.distanceM)}. ")
                if (s.schedule.isNotBlank()) append("Horario: ${spokenSchedule(s.schedule)}.")
            }
            is Choice.Food -> buildString {
                val r = c.r.restaurant
                append(r.name)
                Overpass.spokenCuisine(r)?.let { append(", $it") }
                append(", a ${Speech.distance(c.r.distanceM)}.")
                r.address?.let { append(" Está en $it.") }
                append(r.openingHours?.let { " Horario: ${spokenSchedule(it)}." } ?: " No tengo su horario.")
            }
            is Choice.Route -> "La ${Speech.ordinal(c.index)} ruta tarda ${Speech.duration(c.option.durationS)}, " +
                "recorre ${Speech.distance(c.option.distanceM)}" +
                (if (c.option.summary.isNotBlank()) " y va por ${c.option.summary}" else "") +
                (if (c.option.hasTolls) ". Tiene peajes." else ". No tiene peajes.") +
                (if (c.option.usesMotorway) " Usa autopista o autovía." else "")
        }
        return say("$text ¿Te llevo?")
    }

    private fun moreOptions(): Turn {
        if (choices.isEmpty()) return say("No tengo más opciones. ¿Qué busco?")
        if ((page + 1) * PAGE >= choices.size) return say("No hay más opciones. ¿Te llevo a alguna de las anteriores?")
        page++
        return say(speakPage(skipFirst = false))
    }

    // ---------------------------------------------------------------- Utilidades

    private fun speakPage(skipFirst: Boolean): String {
        val from = page * PAGE
        val slice = choices.drop(from).take(PAGE)
        val parts = slice.mapIndexedNotNull { k, c ->
            val i = from + k
            if (skipFirst && i == 0) return@mapIndexedNotNull null
            when (c) {
                is Choice.Fuel -> "la ${Speech.ordinal(i)}, ${c.name}, a ${Speech.distance(c.r.distanceM)}, ${Speech.price(c.r.station.price)}"
                is Choice.Food -> "${Speech.ordinal(i, feminine = false)}, ${c.name}" +
                    (Overpass.spokenCuisine(c.r.restaurant)?.let { ", $it" } ?: "") +
                    ", a ${Speech.distance(c.r.distanceM)}"
                is Choice.Route -> "la ${Speech.ordinal(i)}, ${Speech.duration(c.option.durationS)}"
            }
        }
        val list = if (parts.isEmpty()) "" else (if (skipFirst) "También tienes " else "") + Speech.join(parts) + ". "
        val more = if (choices.size > from + PAGE) "Di 'más opciones' para seguir. " else ""
        val ask = if (choices.firstOrNull() is Choice.Food) "¿A cuál te llevo? Di por ejemplo 'el primero'."
        else "¿A cuál te llevo? Di por ejemplo 'la primera'."
        return "$list$more$ask"
    }

    private fun item(c: Choice): Display.Item = when (c) {
        is Choice.Fuel -> Display.Item(
            title = c.name,
            subtitle = "${c.r.station.address.prettyName()} · ${Speech.shortDistance(c.r.distanceM)}",
            location = c.location,
            badge = Speech.shortPrice(c.r.station.price),
        )
        is Choice.Food -> Display.Item(
            title = c.name,
            subtitle = listOfNotNull(Overpass.spokenCuisine(c.r.restaurant), Speech.shortDistance(c.r.distanceM)).joinToString(" · "),
            location = c.location,
            badge = Speech.shortDistance(c.r.distanceM),
        )
        is Choice.Route -> Display.Item(
            title = "${c.index + 1}. ${c.option.summary.ifBlank { "Ruta ${c.index + 1}" }}",
            subtitle = "${Speech.shortDistance(c.option.distanceM)}${if (c.option.hasTolls) " · peajes" else " · sin peajes"}",
            location = null,
            badge = Speech.shortDuration(c.option.durationS),
        )
    }

    private fun distanceOf(c: Choice): Double = when (c) {
        is Choice.Fuel -> c.r.distanceM
        is Choice.Food -> c.r.distanceM
        is Choice.Route -> c.option.distanceM
    }

    private fun remainingRoute(here: LatLng): List<LatLng> {
        val route = activeRoute ?: return emptyList()
        val nearest = route.indices.minByOrNull { distanceMeters(route[it], here) } ?: 0
        return route.drop(nearest)
    }

    private fun distanceToRoute(p: LatLng, route: List<LatLng>) = distanceToPolylineMeters(p, route)

    private fun spokenSchedule(s: String): String = s
        .replace("24/7", "abierto 24 horas").replace("24H", "24 horas")
        .replace("L-D", "de lunes a domingo").replace("L-V", "de lunes a viernes")
        .replace("Mo-Su", "todos los días").replace("Mo-Fr", "de lunes a viernes")
        .replace(";", ",")

    private fun noLocation() = say("No consigo tu ubicación. Comprueba que el GPS y el permiso de ubicación están activados.")

    private fun networkError(what: String) =
        say("No he podido consultar $what. Puede que no haya cobertura. Inténtalo de nuevo en un momento.")

    private fun say(text: String, display: Display? = null) = Turn(text, display).also { lastSpeech = text }

    private companion object {
        const val PAGE = 3
        const val MAX_RESULTS = 6
        const val MAX_READOUT = 12
        const val READOUT_CHUNK = 4
        const val TOP_LEAGUES = 7
    }
}
