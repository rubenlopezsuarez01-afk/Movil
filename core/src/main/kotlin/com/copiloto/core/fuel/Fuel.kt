package com.copiloto.core.fuel

import com.copiloto.core.LatLng
import com.copiloto.core.distanceMeters
import com.copiloto.core.distanceToPolylineMeters
import com.copiloto.core.normalizeEs
import org.json.JSONObject

/** Tipos de combustible y su campo en la API del Ministerio (geoportalgasolineras.es). */
enum class FuelType(val apiField: String, val spoken: String) {
    GASOLINA_95("Precio Gasolina 95 E5", "gasolina 95"),
    GASOLINA_98("Precio Gasolina 98 E5", "gasolina 98"),
    DIESEL("Precio Gasoleo A", "diésel"),
    DIESEL_PREMIUM("Precio Gasoleo Premium", "diésel premium"),
    GLP("Precio Gases licuados del petróleo", "GLP"),
}

data class FuelStation(
    val id: String,
    val brand: String,
    val address: String,
    val town: String,
    val location: LatLng,
    val price: Double,
    val schedule: String,
)

/**
 * Parser de la respuesta de
 * https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/
 * (y sus variantes FiltroProvincia/{id}). Los decimales vienen con coma.
 */
object MinistryParser {
    fun parse(json: String, fuel: FuelType): List<FuelStation> {
        val list = JSONObject(json).optJSONArray("ListaEESSPrecio") ?: return emptyList()
        val out = ArrayList<FuelStation>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val price = o.optString(fuel.apiField).toDecimal() ?: continue
            if (price <= 0.0) continue
            val lat = o.optString("Latitud").toDecimal() ?: continue
            val lng = o.optString("Longitud (WGS84)").toDecimal() ?: continue
            out += FuelStation(
                id = o.optString("IDEESS"),
                brand = o.optString("Rótulo").ifBlank { "Gasolinera" },
                address = o.optString("Dirección"),
                town = o.optString("Municipio").ifBlank { o.optString("Localidad") },
                location = LatLng(lat, lng),
                price = price,
                schedule = o.optString("Horario"),
            )
        }
        return out
    }

    private fun String.toDecimal(): Double? = trim().replace(',', '.').toDoubleOrNull()
}

enum class FuelSort { CHEAPEST, CLOSEST }

data class RankedStation(val station: FuelStation, val distanceM: Double)

object FuelRanker {
    /**
     * Filtra por radio (o por cercanía a la ruta si se da [route]) y ordena.
     * Con CHEAPEST, a igualdad de precio (±0,5 céntimos) gana la más cercana.
     */
    fun rank(
        stations: List<FuelStation>,
        from: LatLng,
        radiusM: Double,
        sort: FuelSort,
        route: List<LatLng>? = null,
        maxDetourM: Double = 2_000.0,
    ): List<RankedStation> {
        val candidates = stations.asSequence()
            .distinctBy { it.id.ifBlank { "${it.location}" } }
            .map { RankedStation(it, distanceMeters(from, it.location)) }
            .filter { r ->
                if (route != null) distanceToPolylineMeters(r.station.location, route) <= maxDetourM
                else r.distanceM <= radiusM
            }
            .toList()
        return when (sort) {
            FuelSort.CLOSEST -> candidates.sortedBy { it.distanceM }
            FuelSort.CHEAPEST -> candidates.sortedWith(
                compareBy<RankedStation> { Math.round(it.station.price * 200) }.thenBy { it.distanceM },
            )
        }
    }

    /** Ahorro de llenar [litres] en la primera frente a la media de las encontradas. */
    fun savingsVsAverage(ranked: List<RankedStation>, litres: Double): Double {
        if (ranked.size < 3) return 0.0
        val avg = ranked.map { it.station.price }.average()
        return ((avg - ranked.first().station.price) * litres).coerceAtLeast(0.0)
    }
}

/** Provincias (código INE = dos primeras cifras del código postal). */
object Provinces {
    private val NAMES: Map<String, List<String>> = mapOf(
        "01" to listOf("alava", "araba"),
        "02" to listOf("albacete"),
        "03" to listOf("alicante", "alacant"),
        "04" to listOf("almeria"),
        "05" to listOf("avila"),
        "06" to listOf("badajoz"),
        "07" to listOf("baleares", "illes balears", "islas baleares"),
        "08" to listOf("barcelona"),
        "09" to listOf("burgos"),
        "10" to listOf("caceres"),
        "11" to listOf("cadiz"),
        "12" to listOf("castellon", "castello"),
        "13" to listOf("ciudad real"),
        "14" to listOf("cordoba"),
        "15" to listOf("a coruna", "la coruna", "coruna"),
        "16" to listOf("cuenca"),
        "17" to listOf("girona", "gerona"),
        "18" to listOf("granada"),
        "19" to listOf("guadalajara"),
        "20" to listOf("gipuzkoa", "guipuzcoa"),
        "21" to listOf("huelva"),
        "22" to listOf("huesca"),
        "23" to listOf("jaen"),
        "24" to listOf("leon"),
        "25" to listOf("lleida", "lerida"),
        "26" to listOf("la rioja", "rioja"),
        "27" to listOf("lugo"),
        "28" to listOf("madrid"),
        "29" to listOf("malaga"),
        "30" to listOf("murcia"),
        "31" to listOf("navarra", "nafarroa"),
        "32" to listOf("ourense", "orense"),
        "33" to listOf("asturias"),
        "34" to listOf("palencia"),
        "35" to listOf("las palmas"),
        "36" to listOf("pontevedra"),
        "37" to listOf("salamanca"),
        "38" to listOf("santa cruz de tenerife", "tenerife"),
        "39" to listOf("cantabria"),
        "40" to listOf("segovia"),
        "41" to listOf("sevilla"),
        "42" to listOf("soria"),
        "43" to listOf("tarragona"),
        "44" to listOf("teruel"),
        "45" to listOf("toledo"),
        "46" to listOf("valencia"),
        "47" to listOf("valladolid"),
        "48" to listOf("bizkaia", "vizcaya"),
        "49" to listOf("zamora"),
        "50" to listOf("zaragoza"),
        "51" to listOf("ceuta"),
        "52" to listOf("melilla"),
    )

    fun fromPostalCode(postalCode: String?): String? {
        val digits = postalCode?.trim()?.takeIf { it.length == 5 && it.all(Char::isDigit) } ?: return null
        return digits.substring(0, 2).takeIf { it in NAMES }
    }

    /** Acepta "Madrid", "Comunidad de Madrid", "Provincia de Málaga", "València"... */
    fun fromName(name: String?): String? {
        val n = name?.normalizeEs()?.takeIf { it.isNotBlank() } ?: return null
        val padded = " $n "
        return NAMES.entries.firstOrNull { (_, alts) -> alts.any { padded.contains(" $it ") } }?.key
    }
}
