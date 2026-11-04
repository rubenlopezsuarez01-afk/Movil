package com.copiloto.core

import com.copiloto.core.dialog.CommandParser
import com.copiloto.core.dialog.Criterion
import com.copiloto.core.dialog.VoiceCommand
import com.copiloto.core.food.Cuisine
import com.copiloto.core.fuel.FuelSort
import com.copiloto.core.fuel.FuelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandParserTest {
    private fun p(s: String) = CommandParser.parse(s)

    @Test fun fuel() {
        assertEquals(VoiceCommand.FindFuel(), p("¿Dónde está la gasolinera más barata?"))
        assertEquals(VoiceCommand.FindFuel(fuel = FuelType.DIESEL), p("busca diésel barato"))
        assertEquals(VoiceCommand.FindFuel(fuel = FuelType.GASOLINA_98, radiusKm = 20), p("gasolina 98 a menos de 20 km"))
        assertEquals(VoiceCommand.FindFuel(sort = FuelSort.CLOSEST), p("la gasolinera más cercana"))
        assertEquals(VoiceCommand.FindFuel(onRoute = true), p("necesito repostar de camino"))
        assertEquals(VoiceCommand.FindFuel(goNow = true), p("Llévame a la gasolinera más barata"))
        assertEquals(VoiceCommand.FindFuel(fuel = FuelType.DIESEL, radiusKm = 10), p("gasoil en diez kilómetros"))
    }

    @Test fun setFuel() {
        assertEquals(VoiceCommand.SetFuel(FuelType.DIESEL), p("mi coche es diésel"))
        assertEquals(VoiceCommand.SetFuel(FuelType.GASOLINA_98), p("uso gasolina 98"))
    }

    @Test fun food() {
        assertEquals(VoiceCommand.FindFood(), p("¿dónde puedo comer?"))
        assertEquals(VoiceCommand.FindFood(), p("tengo hambre"))
        assertEquals(VoiceCommand.FindFood(cuisine = Cuisine.PIZZA), p("busca una pizzería"))
        assertEquals(VoiceCommand.FindFood(cuisine = Cuisine.TAPAS, onRoute = true), p("un sitio de tapas de camino"))
        assertEquals(VoiceCommand.FindFood(fastFood = true), p("comida rápida"))
    }

    @Test fun routes() {
        assertEquals(VoiceCommand.PlanRoute("valencia"), p("rutas a Valencia"))
        assertEquals(VoiceCommand.PlanRoute("madrid", avoidTolls = true), p("¿cómo llego a Madrid sin peajes?"))
        assertEquals(VoiceCommand.PlanRoute("calle mayor 5 alcala de henares"), p("llévame a la calle Mayor 5, Alcalá de Henares"))
        assertEquals(VoiceCommand.PlanRoute(null), p("¿hay rutas alternativas?"))
        assertEquals(VoiceCommand.PlanRoute("casa"), p("llévame a casa"))
    }

    @Test fun selection() {
        assertEquals(VoiceCommand.Select(index = 0), p("la primera"))
        assertEquals(VoiceCommand.Select(index = 1), p("Llévame a la segunda"))
        assertEquals(VoiceCommand.Select(index = 2), p("opción tres"))
        assertEquals(VoiceCommand.Select(criterion = Criterion.FASTEST), p("la más rápida"))
        assertEquals(VoiceCommand.Select(criterion = Criterion.CHEAPEST), p("la más barata"))
        assertEquals(VoiceCommand.Select(criterion = Criterion.NO_TOLLS), p("la que no tenga peajes"))
        assertEquals(VoiceCommand.Select(criterion = Criterion.LAST), p("la última"))
        assertEquals(VoiceCommand.Details(index = 2), p("dame detalles de la tercera"))
    }

    @Test fun control() {
        assertEquals(VoiceCommand.Yes, p("Sí, por favor"))
        assertEquals(VoiceCommand.No, p("no"))
        assertEquals(VoiceCommand.Stop, p("gracias"))
        assertEquals(VoiceCommand.Help, p("¿qué puedes hacer?"))
        assertEquals(VoiceCommand.ContinueToDestination, p("continuar a mi destino"))
        assertEquals(VoiceCommand.ContinueToDestination, p("seguimos la ruta"))
        assertEquals(VoiceCommand.MoreOptions, p("más opciones"))
        assertEquals(VoiceCommand.Repeat, p("repite"))
        assertIs<VoiceCommand.Unknown>(p("pon música de los Beatles"))
    }

    @Test fun asDestination() {
        assertEquals("valencia", CommandParser.asDestination("A Valencia"))
        assertEquals("plaza mayor de salamanca", CommandParser.asDestination("quiero ir a la Plaza Mayor de Salamanca".replace("a la", "a")))
        assertTrue(CommandParser.asDestination("a") == null)
    }
}
