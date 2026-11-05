package com.copiloto.core.dialog

import com.copiloto.core.food.Cuisine
import com.copiloto.core.fuel.FuelSort
import com.copiloto.core.fuel.FuelType
import com.copiloto.core.normalizeEs
import com.copiloto.core.parseSpanishNumber

enum class Criterion { CHEAPEST, CLOSEST, FASTEST, SHORTEST, NO_TOLLS, LAST }

sealed interface VoiceCommand {
    data class FindFuel(
        val fuel: FuelType? = null,
        val sort: FuelSort = FuelSort.CHEAPEST,
        val radiusKm: Int? = null,
        val onRoute: Boolean = false,
        /** "llévame a la gasolinera más barata": navegar directamente a la mejor. */
        val goNow: Boolean = false,
    ) : VoiceCommand

    data class FindFood(
        val cuisine: Cuisine? = null,
        val radiusKm: Int? = null,
        val onRoute: Boolean = false,
        val fastFood: Boolean = false,
        val goNow: Boolean = false,
    ) : VoiceCommand

    data class PlanRoute(
        val destination: String? = null,
        val avoidTolls: Boolean = false,
        val avoidMotorways: Boolean = false,
    ) : VoiceCommand

    /** [index] base 0; o bien un [criterion] ("la más rápida"). */
    data class Select(val index: Int? = null, val criterion: Criterion? = null) : VoiceCommand
    data class Details(val index: Int? = null) : VoiceCommand
    data class SetFuel(val fuel: FuelType) : VoiceCommand
    data object ContinueToDestination : VoiceCommand
    data object MoreOptions : VoiceCommand
    data object Repeat : VoiceCommand
    data object Help : VoiceCommand
    data object Yes : VoiceCommand
    data object No : VoiceCommand
    data object Stop : VoiceCommand

    /** "abre GasofApp", "entra en el correo", "abre Flashscore". */
    data class OpenApp(val name: String) : VoiceCommand

    /** "lee mis correos", "¿tengo notificaciones?", "¿qué dice Flashscore?". [app] null = todas. */
    data class ReadNotifications(val app: String? = null) : VoiceCommand

    /** "¿qué partidos hay hoy?", "¿cómo va el Madrid?", "partidos de la Champions mañana". */
    data class Matches(
        val team: String? = null,
        val competition: String? = null,
        val dayOffset: Int = 0,
        val myTeam: Boolean = false,
    ) : VoiceCommand

    data class SetTeam(val team: String) : VoiceCommand
    data class Unknown(val text: String) : VoiceCommand
}

/**
 * Convierte la transcripción de voz (español) en un [VoiceCommand].
 * Basado en reglas: no necesita red ni modelos y responde al instante.
 */
object CommandParser {

    private fun Regex.hit(s: String) = containsMatchIn(s)
    private fun r(p: String) = Regex(p)

    private val STOP = r("^(nada|no nada|cancela|cancelar|para|parar|calla|callate|adios|hasta luego|gracias|muchas gracias|eso es todo|dejalo|salir|sal|cierra|olvidalo|nada mas)( gracias)?$")
    private val HELP = r("\\b(ayuda|que mas puedes hacer|que puedes hacer|que sabes hacer|que te puedo pedir|opciones de voz|como funciona)\\b")
    private val REPEAT = r("\\b(repite|repitelo|repetir|otra vez|que has dicho|no te he oido|no te he entendido|como dices)\\b")
    private val MORE = r("^(sigue|continua|siguiente|sigue leyendo|lee mas|mas|dime mas|mas partidos|mas correos)$|\\b(mas opciones|otras opciones|siguientes|mas resultados|dame mas|hay mas|alguna mas|algo mas lejos)\\b")
    private val CONTINUE = r("\\b((continuar|continua|continuamos|seguir|sigue|seguimos|volver|vuelve|retomar|retoma|reanudar)( la| mi)? (a|al|hacia|con|ruta|viaje|camino|destino|navegacion)|vamos al destino|ir al destino|ruta original)\\b")
    private val YES = r("^(si|si si|vale|venga|ok|okey|de acuerdo|perfecto|adelante|claro|vamos|dale|eso|correcto|si por favor|si vamos|si llevame|llevame|me vale|esa|ese)( por favor| gracias)?$")
    private val NO = r("^(no|no gracias|ninguna|ninguno|ninguna de esas|otra|otro|mejor no|no me convence)$")

    private val FUEL = r("\\b(gasolin\\w*|repost\\w*|combustible|carburante|diesel|gasoleo|gasoil|gasoi|glp|autogas|echar (gasolina|diesel|gasoil)|llenar el deposito|deposito|surtidor)\\b")
    private val FOOD = r("\\b(comer|comida|restaurante\\w*|cenar|cena|almorzar|almuerzo|desayun\\w*|merendar|hambre|tapas|tapear|picar algo|bar|bares|cafe|cafeteria|bocadillo|pizz\\w*|hamburgues\\w*|burger|sushi|kebab|asador|marisqu\\w*|mexicano|chino|italiano|japones|vegetarian\\w*|vegano|indio)\\b")
    private val ROUTE_WORDS = r("\\b(ruta|rutas|camino|caminos|como llego|como voy|como ir|llevame|llevanos|lleva me|navega|navegar|ir a|ir al|ir hasta|vamos a|vamos al|quiero ir|alternativa|alternativas|variantes|otra ruta|otras rutas|sin peajes|evita\\w* (los )?peajes|sin autopista)\\b")
    private val ON_ROUTE = r("\\b(de camino|en (mi|la) ruta|por el camino|en el camino|sin desviarme|sin desviarnos|por la ruta|en ruta)\\b")
    private val GO_NOW = r("\\b(llevame|llevanos|vamos|ir|navega|navegar)\\b")
    private val CLOSEST = r("\\b(mas cercan\\w*|cerquita|mas proxim\\w*|la de al lado|lo mas cerca|mas cerca)\\b")
    private val RADIUS = r("\\b(?:a menos de|en un radio de|en|dentro de|hasta) (\\d+|\\w+) (?:km|kilometros|kilometro|kms)\\b")
    private val NO_TOLLS = r("\\b(sin peaje\\w*|evita\\w* (los )?peaje\\w*|que no (tenga|pague|pase por) peaje\\w*|sin pagar)\\b")
    private val NO_MOTORWAY = r("\\b(sin autopista\\w*|evita\\w* (las )?autopista\\w*|por nacional|por carretera nacional|sin autovia\\w*)\\b")
    private val SET_FUEL = r("\\b(mi coche (es|usa|gasta|lleva)|uso|gasto|mi combustible es|tengo un (coche )?(de )?|mi coche es de)\\b")
    private val DETAILS = r("\\b(detalle\\w*|informacion|info|horario|cuando (abre|cierra)|esta abiert\\w*|dime mas|cuentame mas|mas datos|direccion exacta)\\b")

    private val ORDINALS = listOf(
        r("\\b(primer|primera|primero|uno|una|1)\\b"),
        r("\\b(segunda|segundo|dos|2)\\b"),
        r("\\b(tercera|tercero|tercer|tres|3)\\b"),
        r("\\b(cuarta|cuarto|cuatro|4)\\b"),
        r("\\b(quinta|quinto|cinco|5)\\b"),
        r("\\b(sexta|sexto|seis|6)\\b"),
    )
    private val LAST = r("\\b(ultima|ultimo)\\b")
    /** Palabras que pueden acompañar a una selección sin cambiar su significado. */
    private val SELECTION_FILLER = setOf(
        "la", "el", "lo", "a", "al", "de", "en", "por", "opcion", "numero", "ruta", "gasolinera", "restaurante",
        "sitio", "quiero", "prefiero", "me", "quedo", "con", "elijo", "escojo", "pon", "ponme", "llevame",
        "vamos", "ve", "ir", "esa", "ese", "si", "vale", "venga", "pues", "mejor", "dame", "detalles",
        "informacion", "dime", "mas", "sobre", "del", "y", "favor", "porfa", "que", "es", "lugar", "uno", "una",
        "primer", "primera", "primero", "segunda", "segundo", "dos", "tercera", "tercero", "tercer", "tres",
        "cuarta", "cuarto", "cuatro", "quinta", "quinto", "cinco", "sexta", "sexto", "seis", "ultima", "ultimo",
        "1", "2", "3", "4", "5", "6", "horario", "cuando", "abre", "cierra", "esta", "abierta", "abierto",
    )

    private val DESTINATION = r("(?:rutas?|caminos?|alternativas|variantes|como llego|como voy|como ir|llevame|llevanos|navega|navegar|ir|vamos|quiero ir|opciones)(?: hasta| hacia| para| a| al| por| en)+ (.+)$")
    private val DESTINATION_TRAILING = r("\\b(sin peaje\\w*|evita\\w*.*|por favor|porfa|sin autopista\\w*|por nacional|rapido|ahora|ya)\\b.*$")

    fun parse(raw: String): VoiceCommand {
        val s = raw.normalizeEs()
        if (s.isBlank()) return VoiceCommand.Unknown(raw)

        if (STOP.hit(s)) return VoiceCommand.Stop
        if (HELP.hit(s)) return VoiceCommand.Help
        if (YES.hit(s)) return VoiceCommand.Yes
        if (NO.hit(s)) return VoiceCommand.No
        if (REPEAT.hit(s) && s.split(' ').size <= 5) return VoiceCommand.Repeat
        if (CONTINUE.hit(s)) return VoiceCommand.ContinueToDestination
        if (MORE.hit(s) && !FUEL.hit(s) && !FOOD.hit(s)) return VoiceCommand.MoreOptions

        SET_TEAM.find(s)?.let { m -> cleanTeam(m.groupValues[2])?.let { return VoiceCommand.SetTeam(it) } }
        OPEN_APP.find(s)?.let { m ->
            val name = m.groupValues[2].replace(Regex("^(la |el |mi |mis )"), "").trim()
            if (name.isNotBlank()) return VoiceCommand.OpenApp(name)
        }
        notifications(s)?.let { return it }
        matches(s)?.let { return it }

        fuelType(s)?.let { ft -> if (SET_FUEL.hit(s) && !FUEL_SEARCH_VERB.hit(s)) return VoiceCommand.SetFuel(ft) }

        selection(s)?.let { return it }

        if (FUEL.hit(s)) {
            return VoiceCommand.FindFuel(
                fuel = fuelType(s),
                sort = if (CLOSEST.hit(s) && !s.contains("barat")) FuelSort.CLOSEST else FuelSort.CHEAPEST,
                radiusKm = radius(s),
                onRoute = ON_ROUTE.hit(s),
                goNow = GO_NOW.hit(s),
            )
        }
        if (FOOD.hit(s)) {
            return VoiceCommand.FindFood(
                cuisine = cuisine(s),
                radiusKm = radius(s),
                onRoute = ON_ROUTE.hit(s),
                fastFood = s.contains("comida rapida") || s.contains("algo rapido"),
                goNow = GO_NOW.hit(s) && !s.contains("donde"),
            )
        }
        if (ROUTE_WORDS.hit(s) || NO_TOLLS.hit(s)) {
            return VoiceCommand.PlanRoute(
                destination = destination(s),
                avoidTolls = NO_TOLLS.hit(s),
                avoidMotorways = NO_MOTORWAY.hit(s),
            )
        }
        return VoiceCommand.Unknown(raw)
    }

    private val FUEL_SEARCH_VERB = r("\\b(busca|buscame|donde|llevame|quiero|necesito|hay|barat\\w*|cercan\\w*|repost\\w*|echar)\\b")

    /** Selección del tipo "la segunda", "llévame a la primera", "la más rápida", "detalles de la tercera". */
    private fun selection(s: String): VoiceCommand? {
        val words = s.split(' ')
        val isDetails = DETAILS.hit(s)
        val criterion = when {
            s.contains("mas rapid") -> Criterion.FASTEST
            s.contains("mas cort") -> Criterion.SHORTEST
            NO_TOLLS.hit(s) && !s.contains(" a ") && words.size <= 6 -> Criterion.NO_TOLLS
            else -> null
        }
        if (criterion != null && !FUEL.hit(s) && !FOOD.hit(s)) return VoiceCommand.Select(criterion = criterion)

        if (!words.all { it in SELECTION_FILLER }) {
            // "la más barata" / "la más cercana" solos se interpretan como elección dentro de la lista.
            if (words.size <= 4 && !FUEL.hit(s) && !FOOD.hit(s)) {
                if (s.contains("mas barat")) return VoiceCommand.Select(criterion = Criterion.CHEAPEST)
                if (CLOSEST.hit(s)) return VoiceCommand.Select(criterion = Criterion.CLOSEST)
            }
            return null
        }
        val index = when {
            LAST.hit(s) -> return if (isDetails) VoiceCommand.Details(null) else VoiceCommand.Select(criterion = Criterion.LAST)
            else -> ORDINALS.indexOfFirst { it.hit(s) }.takeIf { it >= 0 }
        }
        if (isDetails) return VoiceCommand.Details(index)
        return index?.let { VoiceCommand.Select(index = it) }
    }

    // ------------------------------------------------------------ Apps, correo y deportes

    private val OPEN_APP = r("^(?:oye |copiloto )?(abre|abrir|abreme|entra en|entra a|entrar en|lanza|inicia|ejecuta|arranca|quiero abrir|puedes abrir)(?: la app| la aplicacion| app| aplicacion)?(?: de)? (.+)$")
    private val MAIL = r("\\b(correo\\w*|emails?|e mails?|mails?|gmail|outlook|bandeja de entrada)\\b")
    private val NOTIF = r("\\b(notificacion\\w*|avisos|alertas|mensajes nuevos)\\b")
    private val READ_VERB = r("\\b(lee|leeme|leer|leelos|tengo|hay|ha llegado|han llegado|mira|revisa|comprueba|dime|algun|alguna|nuevo|nuevos|ultimo|ultimos)\\b")
    private val WHAT_SAYS = r("^(?:que|y que) (?:dice|dicen|pone|hay en|tengo en) (?:el |la |mi )?(.+)$")
    private val NOTIF_OF = r("\\b(?:notificacion\\w*|avisos|alertas) (?:de|del) (?:la |el |mi )?(.+)$")

    private fun notifications(s: String): VoiceCommand? {
        NOTIF_OF.find(s)?.let { return VoiceCommand.ReadNotifications(it.groupValues[1].trim()) }
        if (NOTIF.hit(s)) return VoiceCommand.ReadNotifications(null)
        if (MAIL.hit(s) && (READ_VERB.hit(s) || s.split(' ').size <= 3)) return VoiceCommand.ReadNotifications("correo")
        WHAT_SAYS.find(s)?.let { m ->
            val app = m.groupValues[1].trim()
            if (!MATCH_WORDS.hit(s) && app.isNotBlank()) return VoiceCommand.ReadNotifications(app)
        }
        return null
    }

    private val MATCH_WORDS = r("\\b(partido\\w*|juega|juegan|jugamos|resultado\\w*|marcador\\w*|como va|como van|como ha quedado|como han quedado|como quedo|quien gana|quien va ganando|futbol|champions|nba|jornada)\\b")
    private val TEAM_AFTER = r("\\b(?:juega|juegan|como va|como van|como ha quedado|como quedo|como ha ido|partido del|partido de|partidos del|partidos de|resultado del|resultado de|marcador del|marcador de|quien gana el|gana el) (?:el |la |los )?(.+)$")
    private val SET_TEAM = r("\\b(mi equipo (?:favorito )?es|soy (?:del|de la|de los|de)) (.+)$")
    private val DAY_WORDS = r("\\b(hoy|manana|ayer|esta noche|esta tarde|ahora|en directo|en vivo|ahora mismo|este finde|el finde)\\b.*$")

    private fun matches(s: String): VoiceCommand? {
        if (!MATCH_WORDS.hit(s)) return null
        val day = when {
            Regex("\\bayer\\b").hit(s) -> -1
            Regex("\\bmanana\\b").hit(s) && !s.contains("por la manana") -> 1
            else -> 0
        }
        val competition = com.copiloto.core.sports.Espn.LEAGUES.firstOrNull { l -> l.keywords.any { Regex("\\b$it\\b").hit(s) } }?.spoken
        val myTeam = Regex("\\b(mi equipo|los mios|nuestro equipo)\\b").hit(s)
        val team = if (myTeam) null else TEAM_AFTER.find(s)?.groupValues?.get(1)?.let(::cleanTeam)
            ?.takeIf { t -> com.copiloto.core.sports.Espn.LEAGUES.none { l -> l.keywords.any { k -> t == k || t == "la $k" } } }
        return VoiceCommand.Matches(team = team, competition = competition, dayOffset = day, myTeam = myTeam)
    }

    private fun cleanTeam(raw: String): String? = DAY_WORDS.replace(raw, "")
        .replace(Regex("\\b(en la|en el|de la|de|en) (liga|champions|copa.*|europa league|premier)\\b.*$"), "")
        .replace(Regex("^(el |la |los )"), "")
        .trim()
        .takeIf { it.isNotBlank() && it !in setOf("hoy", "partido", "partidos", "futbol", "que", "quien") }

    fun fuelType(s: String): FuelType? = when {
        Regex("\\b(diesel|gasoleo|gasoil) (premium|plus|optima|e\\+|10)\\b").hit(s) -> FuelType.DIESEL_PREMIUM
        Regex("\\b(diesel|gasoleo|gasoil|gasoi)\\b").hit(s) -> FuelType.DIESEL
        Regex("\\b98\\b|noventa y ocho").hit(s) -> FuelType.GASOLINA_98
        Regex("\\b95\\b|noventa y cinco|sin plomo").hit(s) -> FuelType.GASOLINA_95
        Regex("\\b(glp|autogas|gas licuado)\\b").hit(s) -> FuelType.GLP
        Regex("\\bgasolina\\b").hit(s) && SET_FUEL.hit(s) -> FuelType.GASOLINA_95
        else -> null
    }

    private fun radius(s: String): Int? =
        RADIUS.find(s)?.groupValues?.get(1)?.let(::parseSpanishNumber)?.takeIf { it in 1..100 }

    private fun cuisine(s: String): Cuisine? = when {
        Regex("\\bpizz\\w*").hit(s) -> Cuisine.PIZZA
        Regex("\\b(hamburgues\\w*|burger)").hit(s) -> Cuisine.BURGER
        Regex("\\b(sushi|japones\\w*)").hit(s) -> Cuisine.SUSHI
        Regex("\\bchin[oa]\\b").hit(s) -> Cuisine.CHINESE
        Regex("\\b(italian[oa]|pasta)\\b").hit(s) -> Cuisine.ITALIAN
        Regex("\\bmexican[oa]\\b").hit(s) -> Cuisine.MEXICAN
        Regex("\\b(tapas|tapear|picar algo)\\b").hit(s) -> Cuisine.TAPAS
        Regex("\\bkebab\\b").hit(s) -> Cuisine.KEBAB
        Regex("\\b(asador|carne|parrilla|brasa)\\b").hit(s) -> Cuisine.STEAK
        Regex("\\b(marisqu\\w*|marisco|pescado)").hit(s) -> Cuisine.SEAFOOD
        Regex("\\b(vegetarian\\w*|vegan[oa])").hit(s) -> Cuisine.VEGETARIAN
        Regex("\\b(indi[oa]|hindu)\\b").hit(s) -> Cuisine.INDIAN
        Regex("\\b(cafe|cafeteria|desayun\\w*|merendar)\\b").hit(s) -> Cuisine.COFFEE
        else -> null
    }

    private fun destination(s: String): String? {
        val m = DESTINATION.find(s) ?: return null
        val dest = DESTINATION_TRAILING.replace(m.groupValues[1], "").trim()
            .removePrefix("la ").removePrefix("el ").trim()
        return dest.takeIf { it.isNotBlank() && it !in setOf("destino", "mi destino", "alli", "ahi", "casa de", "otra", "otro") }
    }

    /** Si el usuario solo dice un sitio ("Valencia", "a la calle Mayor") tras preguntarle el destino. */
    fun asDestination(raw: String): String? {
        val s = raw.normalizeEs()
            .replace(Regex("^(quiero ir|vamos|voy|llevame|ir|pues|a ver|al|a|hasta|hacia)( a| al| hasta| hacia)? "), "")
            .let { DESTINATION_TRAILING.replace(it, "") }
            .trim()
        return s.takeIf { it.length >= 2 }
    }
}
