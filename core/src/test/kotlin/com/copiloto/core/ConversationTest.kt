package com.copiloto.core

import com.copiloto.core.dialog.Conversation
import com.copiloto.core.dialog.Display
import com.copiloto.core.dialog.Place
import com.copiloto.core.dialog.Services
import com.copiloto.core.dialog.VoiceCommand
import com.copiloto.core.food.Restaurant
import com.copiloto.core.fuel.FuelStation
import com.copiloto.core.fuel.FuelType
import com.copiloto.core.route.RouteOption
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationTest {
    private val here = LatLng(40.4169, -3.7035)
    private val valencia = LatLng(39.4699, -0.3763)

    private val fake = object : Services {
        override suspend fun currentLocation() = here
        override suspend fun fuelStations(points: List<LatLng>, fuel: FuelType) = listOf(
            FuelStation("1", "REPSOL", "CALLE MAYOR, 1", "MADRID", LatLng(40.4168, -3.7038), 1.599, "L-D: 24H"),
            FuelStation("2", "BALLENOIL", "PASEO DEL PRADO, 2", "MADRID", LatLng(40.413, -3.692), 1.459, "L-D: 24H"),
            FuelStation("3", "CEPSA", "CALLE ALCALA, 100", "MADRID", LatLng(40.421, -3.68), 1.549, "L-D: 24H"),
            FuelStation("4", "LEJANA", "SORIA", "SORIA", LatLng(41.76, -2.46), 1.2, ""),
        )
        override suspend fun restaurants(command: VoiceCommand.FindFood, center: LatLng, radiusM: Int) = listOf(
            Restaurant("n/1", "Casa Paco", "restaurant", "tapas", LatLng(40.417, -3.704), null, null),
        )
        override suspend fun geocode(query: String, near: LatLng?) =
            if (query.contains("valencia")) Place("Valencia", valencia) else null
        override suspend fun routes(from: LatLng, to: LatLng, avoidTolls: Boolean, avoidMotorways: Boolean): List<RouteOption> {
            val main = listOf(from, LatLng(39.9, -2.0), to)
            val alt = listOf(from, LatLng(39.3, -2.5), to)
            return if (avoidTolls) listOf(RouteOption(370_000.0, 14_000.0, "N-III", false, false, alt, tollFree = true))
            else listOf(
                RouteOption(355_000.0, 12_600.0, "A-3", true, true, main),
                RouteOption(380_000.0, 13_500.0, "A-31, A-35", true, true, alt),
            )
        }
    }

    @Test fun fuelThenSelectThenContinue() = runBlocking {
        val c = Conversation(fake)
        val routes = c.handle(listOf("rutas a Valencia"))
        assertIs<Display.Routes>(routes.display)
        assertEquals(3, (routes.display as Display.Routes).items.size) // + opción sin peajes
        assertTrue(routes.speech.contains("Valencia"))

        val fuel = c.handle(listOf("gasolinera más barata"))
        val places = assertIs<Display.Places>(fuel.display)
        assertEquals("Ballenoil", places.items.first().title)
        assertTrue(fuel.keepListening)
        assertTrue(fuel.speech.contains("1,459 euros"), fuel.speech)

        val go = c.handle(listOf("llévame a la segunda"))
        val target = assertNotNull(go.navigateTo)
        assertTrue(target.resumeLater)
        assertFalse(go.keepListening)

        val back = c.greeting(firstTime = false)
        assertTrue(back.speech.contains("Seguimos hacia Valencia"))
        val resume = c.handle(listOf("sí"))
        assertEquals(valencia, resume.navigateTo?.location)
    }

    @Test fun asksForDestinationAndUsesFollowUp() = runBlocking {
        val c = Conversation(fake)
        val ask = c.handle(listOf("quiero ver rutas alternativas"))
        assertTrue(ask.speech.contains("¿A dónde"))
        val routes = c.handle(listOf("a Valencia"))
        assertIs<Display.Routes>(routes.display)
        val pick = c.handle(listOf("la que no tenga peajes"))
        assertNotNull(pick.navigateTo)
        assertTrue(pick.navigateTo!!.resumeLater) // fuerza la alternativa pasando por un punto intermedio
    }

    @Test fun selectingWithoutResultsAsksWhatToSearch() = runBlocking {
        val c = Conversation(fake)
        val t = c.handle(listOf("la primera"))
        assertNull(t.navigateTo)
        assertTrue(t.keepListening)
    }

    @Test fun picksBestHypothesis() = runBlocking {
        val c = Conversation(fake)
        val t = c.handle(listOf("gasolinera mas va rata", "gasolinera más barata"))
        assertIs<Display.Places>(t.display)
    }

    @Test fun rememberPreferredFuel() = runBlocking {
        var saved: FuelType? = null
        val c = Conversation(fake, onSettingsChanged = { saved = it.fuel })
        c.handle(listOf("mi coche es diésel"))
        assertEquals(FuelType.DIESEL, saved)
    }
}
