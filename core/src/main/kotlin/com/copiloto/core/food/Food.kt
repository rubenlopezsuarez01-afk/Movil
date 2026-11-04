package com.copiloto.core.food

import com.copiloto.core.LatLng
import com.copiloto.core.distanceMeters
import com.copiloto.core.distanceToPolylineMeters
import com.copiloto.core.prettyName
import org.json.JSONObject

/** Tipos de cocina reconocidos por voz y su valor en la etiqueta cuisine de OpenStreetMap. */
enum class Cuisine(val osmRegex: String, val spoken: String) {
    PIZZA("pizza", "pizzería"),
    BURGER("burger", "hamburguesería"),
    SUSHI("sushi|japanese", "japonés"),
    CHINESE("chinese", "chino"),
    ITALIAN("italian|pizza", "italiano"),
    MEXICAN("mexican|tex-mex", "mexicano"),
    TAPAS("tapas|spanish", "tapas"),
    KEBAB("kebab|turkish", "kebab"),
    STEAK("steak_house|grill|barbecue", "asador"),
    SEAFOOD("seafood|fish", "marisquería"),
    VEGETARIAN("vegetarian|vegan", "vegetariano"),
    INDIAN("indian", "indio"),
    COFFEE("coffee_shop|cafe", "cafetería"),
}

data class Restaurant(
    val id: String,
    val name: String,
    val kind: String,
    val cuisine: String?,
    val location: LatLng,
    val openingHours: String?,
    val address: String?,
)

data class RankedRestaurant(val restaurant: Restaurant, val distanceM: Double)

object Overpass {
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    /** Consulta Overpass QL: restaurantes/comida rápida/cafeterías alrededor de [center]. */
    fun query(center: LatLng, radiusM: Int, cuisine: Cuisine?, fastFoodOnly: Boolean): String {
        val amenity = when {
            fastFoodOnly -> "fast_food"
            cuisine == Cuisine.COFFEE -> "cafe"
            else -> "restaurant|fast_food"
        }
        val cuisineFilter = if (cuisine != null && cuisine != Cuisine.COFFEE) "[\"cuisine\"~\"${cuisine.osmRegex}\",i]" else ""
        return "[out:json][timeout:20];" +
            "nwr[\"amenity\"~\"^($amenity)$\"][\"name\"]$cuisineFilter(around:$radiusM,${center.lat},${center.lng});" +
            "out center 80;"
    }

    fun parse(json: String): List<Restaurant> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Restaurant>()
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: continue
            val center = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.optDouble("lat") else center?.optDouble("lat") ?: continue
            val lng = if (e.has("lon")) e.optDouble("lon") else center?.optDouble("lon") ?: continue
            if (lat.isNaN() || lng.isNaN()) continue
            val street = tags.optString("addr:street")
            val number = tags.optString("addr:housenumber")
            out += Restaurant(
                id = "${e.optString("type")}/${e.optLong("id")}",
                name = name,
                kind = tags.optString("amenity"),
                cuisine = tags.optString("cuisine").takeIf { it.isNotBlank() },
                location = LatLng(lat, lng),
                openingHours = tags.optString("opening_hours").takeIf { it.isNotBlank() },
                address = street.takeIf { it.isNotBlank() }?.let { if (number.isNotBlank()) "$it, $number" else it },
            )
        }
        return out
    }

    fun rank(
        list: List<Restaurant>,
        from: LatLng,
        route: List<LatLng>? = null,
        maxDetourM: Double = 1_500.0,
    ): List<RankedRestaurant> = list.asSequence()
        .distinctBy { it.id }
        .map { RankedRestaurant(it, distanceMeters(from, it.location)) }
        .filter { route == null || distanceToPolylineMeters(it.restaurant.location, route) <= maxDetourM }
        .sortedBy { it.distanceM }
        .toList()

    /** Traduce la etiqueta cuisine de OSM ("pizza;italian") a algo pronunciable. */
    fun spokenCuisine(r: Restaurant): String? {
        val first = r.cuisine?.split(';', ',')?.firstOrNull()?.trim()?.lowercase() ?: return when (r.kind) {
            "fast_food" -> "comida rápida"
            "cafe" -> "cafetería"
            else -> null
        }
        val known = mapOf(
            "pizza" to "pizzería", "burger" to "hamburguesas", "sushi" to "sushi", "japanese" to "japonés",
            "chinese" to "chino", "italian" to "italiano", "mexican" to "mexicano", "tapas" to "tapas",
            "spanish" to "cocina española", "regional" to "cocina regional", "kebab" to "kebab",
            "turkish" to "turco", "steak_house" to "asador", "grill" to "parrilla", "seafood" to "marisco",
            "fish" to "pescado", "vegetarian" to "vegetariano", "vegan" to "vegano", "indian" to "indio",
            "sandwich" to "bocadillos", "chicken" to "pollo", "coffee_shop" to "cafetería",
            "mediterranean" to "mediterráneo", "asian" to "asiático", "american" to "americano",
        )
        return known[first] ?: first.replace('_', ' ').prettyName().lowercase()
    }
}
