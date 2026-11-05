package com.copiloto.core.sports

import com.copiloto.core.normalizeEs
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class MatchState { SCHEDULED, LIVE, FINISHED, OTHER }

data class Match(
    val competition: String,
    val home: String,
    val away: String,
    val start: Instant,
    val state: MatchState,
    val homeScore: Int?,
    val awayScore: Int?,
    /** "67'", "Descanso"… */
    val clock: String,
    /** Prioridad de la competición (0 = la más importante). */
    val priority: Int,
)

/** Competición consultable en el marcador público de ESPN (sin clave de API). */
data class League(val path: String, val spoken: String, val keywords: List<String>)

object Espn {
    val LEAGUES = listOf(
        League("soccer/esp.1", "LaLiga", listOf("liga", "laliga", "primera")),
        League("soccer/uefa.champions", "Champions", listOf("champions", "liga de campeones")),
        League("soccer/uefa.europa", "Europa League", listOf("europa league", "uefa")),
        League("soccer/uefa.europa.conf", "Conference League", listOf("conference")),
        League("soccer/esp.copa_del_rey", "Copa del Rey", listOf("copa del rey", "copa")),
        League("soccer/esp.2", "Segunda División", listOf("segunda", "hypermotion")),
        League("soccer/eng.1", "Premier League", listOf("premier", "inglesa", "inglaterra")),
        League("soccer/ita.1", "Serie A", listOf("serie a", "italiana", "calcio")),
        League("soccer/ger.1", "Bundesliga", listOf("bundesliga", "alemana")),
        League("soccer/fra.1", "Ligue 1", listOf("ligue 1", "francesa")),
        League("soccer/fifa.world", "Mundial", listOf("mundial")),
        League("soccer/uefa.nations", "Nations League", listOf("nations", "seleccion")),
        League("basketball/nba", "NBA", listOf("nba", "baloncesto")),
    )

    fun url(league: League, from: LocalDate, to: LocalDate = from): String {
        val f = from.format(DateTimeFormatter.BASIC_ISO_DATE)
        val dates = if (to == from) f else "$f-${to.format(DateTimeFormatter.BASIC_ISO_DATE)}"
        return "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?dates=$dates&lang=es&region=es&limit=100"
    }

    fun parse(json: String, league: League): List<Match> {
        val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
        val priority = LEAGUES.indexOf(league).coerceAtLeast(0)
        val out = ArrayList<Match>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors = comp.optJSONArray("competitors") ?: continue
            var home: JSONObject? = null
            var away: JSONObject? = null
            for (k in 0 until competitors.length()) {
                val c = competitors.optJSONObject(k) ?: continue
                if (c.optString("homeAway") == "home") home = c else away = c
            }
            if (home == null || away == null) continue
            val status = (comp.optJSONObject("status") ?: e.optJSONObject("status"))
            val type = status?.optJSONObject("type")
            val state = when (type?.optString("state")) {
                "pre" -> MatchState.SCHEDULED
                "in" -> MatchState.LIVE
                "post" -> if (type.optBoolean("completed", true)) MatchState.FINISHED else MatchState.OTHER
                else -> MatchState.OTHER
            }
            val start = runCatching { Instant.parse(normalizeIso(e.optString("date"))) }.getOrNull() ?: continue
            out += Match(
                competition = league.spoken,
                home = teamName(home),
                away = teamName(away),
                start = start,
                state = state,
                homeScore = home.optString("score").toIntOrNull(),
                awayScore = away.optString("score").toIntOrNull(),
                clock = status?.optString("displayClock").orEmpty(),
                priority = priority,
            )
        }
        return out
    }

    private fun teamName(c: JSONObject): String {
        val t = c.optJSONObject("team") ?: return "?"
        return t.optString("shortDisplayName").ifBlank { t.optString("displayName") }.ifBlank { t.optString("name") }
    }

    /** ESPN usa "2026-11-05T20:00Z" (sin segundos), que Instant.parse no acepta. */
    private fun normalizeIso(s: String): String =
        if (Regex("T\\d{2}:\\d{2}Z$").containsMatchIn(s)) s.dropLast(1) + ":00Z" else s
}

object Teams {
    /** Apodos habituales → texto que aparece en el nombre del equipo. */
    private val ALIASES = mapOf(
        "madrid" to "real madrid", "el madrid" to "real madrid", "real madrid" to "real madrid",
        "barca" to "barcelona", "barsa" to "barcelona", "el barca" to "barcelona",
        "atleti" to "atletico", "atletico de madrid" to "atletico", "atletico" to "atletico",
        "athletic" to "athletic", "athletic de bilbao" to "athletic", "bilbao" to "athletic",
        "betis" to "betis", "sevilla" to "sevilla", "valencia" to "valencia", "villarreal" to "villarreal",
        "real sociedad" to "sociedad", "la real" to "sociedad", "celta" to "celta", "osasuna" to "osasuna",
        "getafe" to "getafe", "rayo" to "rayo", "girona" to "girona", "mallorca" to "mallorca",
        "espanyol" to "espanyol", "alaves" to "alaves", "las palmas" to "palmas", "leganes" to "leganes",
        "valladolid" to "valladolid", "levante" to "levante", "elche" to "elche", "oviedo" to "oviedo",
        "deportivo" to "deportivo", "depor" to "deportivo", "racing" to "racing", "zaragoza" to "zaragoza",
        "malaga" to "malaga", "sporting" to "sporting", "espana" to "spain", "la seleccion" to "spain",
    )

    fun canonical(spoken: String): String {
        val n = spoken.normalizeEs().removePrefix("el ").removePrefix("la ").trim()
        return ALIASES[n] ?: ALIASES["el $n"] ?: n
    }

    fun matches(match: Match, spokenTeam: String): Boolean {
        val key = canonical(spokenTeam)
        if (key.isBlank()) return false
        val h = match.home.normalizeEs()
        val a = match.away.normalizeEs()
        // "real madrid" no debe coincidir con "Atlético de Madrid".
        return h.contains(key) || a.contains(key) || (key == "real madrid" && (h == "madrid" || a == "madrid"))
    }
}

object MatchSpeech {
    private val TIME = DateTimeFormatter.ofPattern("H:mm")

    fun line(m: Match, zone: ZoneId): String {
        val local = m.start.atZone(zone)
        val time = local.format(TIME).let { if (it.endsWith(":00")) it.dropLast(3) else it }
        return when (m.state) {
            MatchState.SCHEDULED -> "${m.home} contra ${m.away}, a las $time"
            MatchState.LIVE -> "${m.home} ${m.homeScore ?: 0}, ${m.away} ${m.awayScore ?: 0}, en juego" +
                (m.clock.takeIf { it.isNotBlank() && it != "0'" }?.let { ", minuto ${it.trimEnd('\'')}" } ?: "")
            MatchState.FINISHED -> "${m.home} ${m.homeScore ?: 0}, ${m.away} ${m.awayScore ?: 0}, final"
            MatchState.OTHER -> "${m.home} contra ${m.away}, aplazado o suspendido"
        }
    }

    fun shortLine(m: Match, zone: ZoneId): String = when (m.state) {
        MatchState.SCHEDULED -> m.start.atZone(zone).format(TIME)
        MatchState.LIVE -> "${m.homeScore ?: 0}-${m.awayScore ?: 0} · ${m.clock}"
        MatchState.FINISHED -> "${m.homeScore ?: 0}-${m.awayScore ?: 0} · final"
        MatchState.OTHER -> "aplazado"
    }
}
