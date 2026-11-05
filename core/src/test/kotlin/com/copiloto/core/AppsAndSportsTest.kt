package com.copiloto.core

import com.copiloto.core.apps.AppIntent
import com.copiloto.core.apps.AppNotification
import com.copiloto.core.apps.Apps
import com.copiloto.core.dialog.CommandParser
import com.copiloto.core.dialog.Conversation
import com.copiloto.core.dialog.Display
import com.copiloto.core.dialog.Place
import com.copiloto.core.dialog.Services
import com.copiloto.core.dialog.Settings
import com.copiloto.core.dialog.VoiceCommand
import com.copiloto.core.food.Restaurant
import com.copiloto.core.fuel.FuelStation
import com.copiloto.core.fuel.FuelType
import com.copiloto.core.route.RouteOption
import com.copiloto.core.sports.Espn
import com.copiloto.core.sports.League
import com.copiloto.core.sports.Match
import com.copiloto.core.sports.MatchState
import com.copiloto.core.sports.Teams
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppsAndSportsTest {
    private fun p(s: String) = CommandParser.parse(s)

    @Test fun parsesAppCommands() {
        assertEquals(VoiceCommand.OpenApp("gasofapp"), p("Abre GasofApp"))
        assertEquals(VoiceCommand.OpenApp("flashscore"), p("entra en Flashscore"))
        assertEquals(VoiceCommand.OpenApp("correo"), p("abre el correo"))
        assertEquals(VoiceCommand.ReadNotifications("correo"), p("¿Tengo correos nuevos?"))
        assertEquals(VoiceCommand.ReadNotifications("correo"), p("léeme los emails"))
        assertEquals(VoiceCommand.ReadNotifications(null), p("lee mis notificaciones"))
        assertEquals(VoiceCommand.ReadNotifications("flashscore"), p("notificaciones de Flashscore"))
        assertEquals(VoiceCommand.ReadNotifications("whatsapp"), p("¿qué dice WhatsApp?"))
        assertEquals(VoiceCommand.MoreOptions, p("sigue"))
    }

    @Test fun parsesSportsCommands() {
        assertEquals(VoiceCommand.Matches(), p("¿Qué partidos hay hoy?"))
        assertEquals(VoiceCommand.Matches(team = "madrid"), p("¿Cómo va el Madrid?"))
        assertEquals(VoiceCommand.Matches(team = "betis", dayOffset = 1), p("¿Juega el Betis mañana?"))
        assertEquals(VoiceCommand.Matches(competition = "Champions"), p("partidos de Champions"))
        assertEquals(VoiceCommand.Matches(myTeam = true), p("¿juega mi equipo hoy?"))
        assertEquals(VoiceCommand.Matches(team = "barca", dayOffset = -1), p("resultado del Barça ayer"))
        assertEquals(VoiceCommand.SetTeam("sevilla"), p("mi equipo es el Sevilla"))
    }

    @Test fun classifiesApps() {
        assertEquals(AppIntent.FUEL, Apps.classify("gasofapp"))
        assertEquals(AppIntent.MAIL, Apps.classify("gmail"))
        assertEquals(AppIntent.SPORTS, Apps.classify("flashscore"))
        assertEquals(AppIntent.OTHER, Apps.classify("amazon"))
    }

    @Test fun filtersNotifications() {
        val list = listOf(
            AppNotification("com.google.android.gm", "Gmail", "Amazon", "Tu pedido ha sido enviado", 3),
            AppNotification("eu.livesport.FlashScore_com", "Flashscore", "GOL Real Madrid", "Real Madrid 1-0 Sevilla", 2),
            AppNotification("com.bank", "Mi Banco", "Cargo", "12,50 €", 1),
        )
        assertEquals("Gmail", Apps.filter(list, "correo").single().appName)
        assertEquals("Flashscore", Apps.filter(list, "flash score").single().appName)
        assertEquals("Mi Banco", Apps.filter(list, "mi banco").single().appName)
    }

    @Test fun teamMatching() {
        val m = Match("LaLiga", "Atlético Madrid", "Real Madrid", Instant.EPOCH, MatchState.SCHEDULED, null, null, "", 0)
        assertTrue(Teams.matches(m, "el Madrid"))
        assertTrue(Teams.matches(m, "atleti"))
        assertTrue(!Teams.matches(m, "barça"))
        val n = Match("LaLiga", "Atlético Madrid", "Sevilla", Instant.EPOCH, MatchState.SCHEDULED, null, null, "", 0)
        assertTrue(!Teams.matches(n, "madrid"))
    }

    @Test fun parsesEspn() {
        val json = """{"events":[{"date":"2026-11-05T20:00Z","competitions":[{"competitors":[
            {"homeAway":"home","score":"2","team":{"displayName":"Real Betis","shortDisplayName":"Betis"}},
            {"homeAway":"away","score":"1","team":{"displayName":"Sevilla","shortDisplayName":"Sevilla"}}],
            "status":{"displayClock":"67'","type":{"state":"in","completed":false}}}]}]}"""
        val m = Espn.parse(json, Espn.LEAGUES[0]).single()
        assertEquals(MatchState.LIVE, m.state)
        assertEquals("Betis", m.home)
        assertEquals(2, m.homeScore)
        assertEquals(Instant.parse("2026-11-05T20:00:00Z"), m.start)
    }

    private val today = LocalDate.of(2026, 11, 5)

    private val services = object : Services {
        var access = true
        override suspend fun currentLocation() = LatLng(40.4, -3.7)
        override suspend fun fuelStations(points: List<LatLng>, fuel: FuelType) =
            listOf(FuelStation("1", "PLENOIL", "CALLE A", "MADRID", LatLng(40.401, -3.7), 1.399, ""))
        override suspend fun restaurants(command: VoiceCommand.FindFood, center: LatLng, radiusM: Int) = emptyList<Restaurant>()
        override suspend fun geocode(query: String, near: LatLng?): Place? = null
        override suspend fun routes(from: LatLng, to: LatLng, avoidTolls: Boolean, avoidMotorways: Boolean) = emptyList<RouteOption>()
        override fun notificationAccess() = access
        override suspend fun notifications() = (1..6).map {
            AppNotification("com.google.android.gm", "Gmail", "Remitente $it", "Asunto $it", it.toLong())
        }
        override fun today() = today
        override suspend fun matches(from: LocalDate, to: LocalDate, leagues: List<League>): List<Match> {
            val all = listOf(
                Match("LaLiga", "Betis", "Sevilla", Instant.parse("2026-11-05T20:00:00Z"), MatchState.SCHEDULED, null, null, "", 0),
                Match("Champions", "Real Madrid", "Milan", Instant.parse("2026-11-05T20:00:00Z"), MatchState.LIVE, 1, 0, "30'", 1),
                Match("LaLiga", "Valencia", "Getafe", Instant.parse("2026-11-08T17:00:00Z"), MatchState.SCHEDULED, null, null, "", 0),
            )
            return all.filter {
                val d = it.start.atZone(zone()).toLocalDate()
                !d.isBefore(from) && !d.isAfter(to) && leagues.any { l -> l.spoken == it.competition }
            }
        }
    }

    @Test fun readsMailInChunks() = runBlocking {
        val c = Conversation(services)
        val first = c.handle(listOf("lee mis correos"))
        assertTrue(first.speech.startsWith("Tienes 6 correos"), first.speech)
        assertTrue(first.speech.endsWith("¿Sigo?"))
        assertIs<Display.Info>(first.display)
        val second = c.handle(listOf("sí"))
        assertTrue(second.speech.contains("Remitente 5"), second.speech)
        assertTrue(second.speech.endsWith("¿Algo más?"))
    }

    @Test fun asksForNotificationPermission() = runBlocking {
        services.access = false
        val t = Conversation(services).handle(listOf("abre el correo"))
        assertTrue(t.speech.contains("Permitir leer notificaciones"))
        services.access = true
    }

    @Test fun openGasofappSearchesFuel() = runBlocking {
        val t = Conversation(services).handle(listOf("abre gasofapp"))
        assertIs<Display.Places>(t.display)
        assertTrue(t.speech.contains("Plenoil"))
    }

    @Test fun matchesToday() = runBlocking {
        val c = Conversation(services)
        val all = c.handle(listOf("qué partidos hay hoy"))
        assertTrue(all.speech.startsWith("Hoy hay 2 partidos, 1 en juego"), all.speech)
        assertTrue(all.speech.contains("Real Madrid 1, Milan 0"), all.speech)
        val betis = c.handle(listOf("a qué hora juega el Betis"))
        assertTrue(betis.speech.contains("Betis contra Sevilla, a las 21"), betis.speech)
        val valencia = c.handle(listOf("juega el Valencia hoy"))
        assertTrue(valencia.speech.contains("próximo partido es el domingo 8"), valencia.speech)
    }

    @Test fun favouriteTeam() = runBlocking {
        var saved: Settings? = null
        val c = Conversation(services, onSettingsChanged = { saved = it })
        c.handle(listOf("soy del Betis"))
        assertEquals("betis", saved?.team)
        val t = c.handle(listOf("¿juega mi equipo hoy?"))
        assertTrue(t.speech.contains("Sevilla"), t.speech)
    }
}
