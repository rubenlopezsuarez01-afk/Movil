package com.copiloto.core

import com.copiloto.core.food.Overpass
import com.copiloto.core.fuel.FuelRanker
import com.copiloto.core.fuel.FuelSort
import com.copiloto.core.fuel.FuelType
import com.copiloto.core.fuel.MinistryParser
import com.copiloto.core.fuel.Provinces
import com.copiloto.core.route.Osrm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataTest {
    private val ministry = """
        {"Fecha":"04/11/2026 10:00:00","ListaEESSPrecio":[
          {"C.P.":"28013","Dirección":"CALLE MAYOR, 1","Horario":"L-D: 24H","Latitud":"40,416775","Localidad":"MADRID",
           "Longitud (WGS84)":"-3,703790","Municipio":"Madrid","Precio Gasoleo A":"1,459","Precio Gasolina 95 E5":"1,599",
           "Provincia":"MADRID","Rótulo":"REPSOL","IDEESS":"1"},
          {"C.P.":"28014","Dirección":"PASEO DEL PRADO, 2","Horario":"L-V: 07:00-22:00","Latitud":"40,413","Localidad":"MADRID",
           "Longitud (WGS84)":"-3,692","Municipio":"Madrid","Precio Gasoleo A":"1,399","Precio Gasolina 95 E5":"",
           "Provincia":"MADRID","Rótulo":"BALLENOIL","IDEESS":"2"},
          {"C.P.":"28100","Dirección":"CTRA A-1 KM 17","Horario":"L-D: 24H","Latitud":"40,54","Localidad":"ALCOBENDAS",
           "Longitud (WGS84)":"-3,64","Municipio":"Alcobendas","Precio Gasoleo A":"1,299","Precio Gasolina 95 E5":"1,450",
           "Provincia":"MADRID","Rótulo":"PLENOIL","IDEESS":"3"}
        ],"ResultadoConsulta":"OK"}
    """.trimIndent()

    private val sol = LatLng(40.4169, -3.7035)

    @Test fun parsesMinistryAndSkipsMissingPrices() {
        val g95 = MinistryParser.parse(ministry, FuelType.GASOLINA_95)
        assertEquals(listOf("1", "3"), g95.map { it.id })
        val diesel = MinistryParser.parse(ministry, FuelType.DIESEL)
        assertEquals(1.399, diesel.first { it.id == "2" }.price)
        assertEquals(40.416775, diesel.first().location.lat)
    }

    @Test fun ranksByPriceWithinRadius() {
        val diesel = MinistryParser.parse(ministry, FuelType.DIESEL)
        val ranked = FuelRanker.rank(diesel, sol, 5_000.0, FuelSort.CHEAPEST)
        assertEquals(listOf("2", "1"), ranked.map { it.station.id }) // Alcobendas queda fuera de 5 km
        val wide = FuelRanker.rank(diesel, sol, 30_000.0, FuelSort.CHEAPEST)
        assertEquals("3", wide.first().station.id)
        val closest = FuelRanker.rank(diesel, sol, 30_000.0, FuelSort.CLOSEST)
        assertEquals("1", closest.first().station.id)
    }

    @Test fun provinces() {
        assertEquals("28", Provinces.fromPostalCode("28013"))
        assertEquals("08", Provinces.fromPostalCode("08001"))
        assertEquals(null, Provinces.fromPostalCode("99001"))
        assertEquals("29", Provinces.fromName("Málaga"))
        assertEquals("15", Provinces.fromName("A Coruña"))
        assertEquals("28", Provinces.fromName("Comunidad de Madrid"))
        assertEquals(null, Provinces.fromName("Andalucía"))
    }

    @Test fun parsesOverpass() {
        val json = """{"elements":[
            {"type":"node","id":1,"lat":40.417,"lon":-3.704,"tags":{"amenity":"restaurant","name":"Casa Paco","cuisine":"tapas;spanish","opening_hours":"Mo-Su 12:00-24:00"}},
            {"type":"way","id":2,"center":{"lat":40.42,"lon":-3.70},"tags":{"amenity":"fast_food","name":"Burger Sol","cuisine":"burger"}},
            {"type":"node","id":3,"lat":40.41,"lon":-3.70,"tags":{"amenity":"restaurant"}}
        ]}"""
        val list = Overpass.parse(json)
        assertEquals(listOf("Casa Paco", "Burger Sol"), list.map { it.name })
        val ranked = Overpass.rank(list, sol)
        assertEquals("Casa Paco", ranked.first().restaurant.name)
        assertEquals("tapas", Overpass.spokenCuisine(list[0]))
        assertTrue(Overpass.query(sol, 2000, null, false).contains("around:2000"))
    }

    @Test fun parsesOsrm() {
        val json = """{"code":"Ok","routes":[
          {"distance":350000,"duration":12600,"geometry":"_p~iF~ps|U_ulLnnqC_mqNvxq`@",
           "legs":[{"summary":"A-3, AP-7","steps":[{"distance":300000,"ref":"A-3","intersections":[{"classes":["motorway"]}]},
                   {"distance":50000,"ref":"AP-7","intersections":[{"classes":["motorway","toll"]}]}]}]},
          {"distance":360000,"duration":13500,"geometry":"_p~iF~ps|U_ulLnnqC",
           "legs":[{"summary":"N-III","steps":[{"distance":360000,"ref":"N-III","intersections":[{}]}]}]}
        ]}"""
        val routes = Osrm.parse(json)
        assertEquals(2, routes.size)
        assertTrue(routes[0].hasTolls && routes[0].usesMotorway)
        assertTrue(!routes[1].hasTolls)
        assertEquals("A-3, AP-7", routes[0].summary)
        assertEquals(3, routes[0].geometry.size)
        assertEquals(38.5, routes[0].geometry[0].lat, 1e-6)
    }

    @Test fun speech() {
        assertEquals("1,459 euros", Speech.price(1.459))
        assertEquals("2,3 kilómetros", Speech.distance(2_300.0))
        assertEquals("800 metros", Speech.distance(810.0))
        assertEquals("1 hora y 5 minutos", Speech.duration(3_900.0))
        assertEquals("a, b y c", Speech.join(listOf("a", "b", "c")))
        assertEquals("Avenida de la Paz, 12", "AVENIDA DE LA PAZ, 12".prettyName())
    }

    @Test fun geometry() {
        val line = listOf(LatLng(40.0, -3.0), LatLng(40.0, -2.0))
        val d = distanceToPolylineMeters(LatLng(40.01, -2.5), line)
        assertTrue(d in 1_000.0..1_200.0, "d=$d")
    }
}
