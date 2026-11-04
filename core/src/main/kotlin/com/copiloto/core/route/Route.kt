package com.copiloto.core.route

import com.copiloto.core.LatLng
import com.copiloto.core.decodePolyline
import org.json.JSONObject

data class RouteOption(
    val distanceM: Double,
    val durationS: Double,
    /** Carreteras principales, p. ej. "A-6, M-50". */
    val summary: String,
    val hasTolls: Boolean,
    val usesMotorway: Boolean,
    val geometry: List<LatLng>,
    /** true si se pidió explícitamente sin peajes. */
    val tollFree: Boolean = false,
)

/** Cliente sin estado para el servidor OSRM (https://project-osrm.org). */
object Osrm {
    const val ENDPOINT = "https://router.project-osrm.org"

    /** El perfil de coche de OSRM solo permite excluir una clase a la vez. */
    fun url(from: LatLng, to: LatLng, exclude: String?): String =
        "$ENDPOINT/route/v1/driving/${from.lng},${from.lat};${to.lng},${to.lat}" +
            "?alternatives=3&steps=true&overview=full&geometries=polyline" +
            (exclude?.let { "&exclude=$it" } ?: "")

    fun parse(json: String, tollFree: Boolean = false): List<RouteOption> {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return emptyList()
        val routes = root.optJSONArray("routes") ?: return emptyList()
        val out = ArrayList<RouteOption>()
        for (i in 0 until routes.length()) {
            val r = routes.optJSONObject(i) ?: continue
            var tolls = false
            var motorway = false
            val roads = LinkedHashMap<String, Double>()
            val legs = r.optJSONArray("legs")
            val legSummaries = mutableListOf<String>()
            for (l in 0 until (legs?.length() ?: 0)) {
                val leg = legs!!.optJSONObject(l) ?: continue
                leg.optString("summary").takeIf { it.isNotBlank() }?.let { legSummaries += it }
                val steps = leg.optJSONArray("steps") ?: continue
                for (s in 0 until steps.length()) {
                    val step = steps.optJSONObject(s) ?: continue
                    val ref = step.optString("ref").ifBlank { step.optString("name") }
                    if (ref.isNotBlank()) roads[ref] = (roads[ref] ?: 0.0) + step.optDouble("distance", 0.0)
                    val inters = step.optJSONArray("intersections") ?: continue
                    for (k in 0 until inters.length()) {
                        val classes = inters.optJSONObject(k)?.optJSONArray("classes") ?: continue
                        for (c in 0 until classes.length()) {
                            when (classes.optString(c)) {
                                "toll" -> tolls = true
                                "motorway" -> motorway = true
                            }
                        }
                    }
                }
            }
            val mainRoads = roads.entries.sortedByDescending { it.value }.take(2).map { it.key.substringBefore(';') }
            out += RouteOption(
                distanceM = r.optDouble("distance", 0.0),
                durationS = r.optDouble("duration", 0.0),
                summary = legSummaries.joinToString(", ").ifBlank { mainRoads.joinToString(", ") },
                hasTolls = tolls,
                usesMotorway = motorway,
                geometry = r.optString("geometry").takeIf { it.isNotBlank() }?.let { decodePolyline(it) } ?: emptyList(),
                tollFree = tollFree,
            )
        }
        return out
    }
}
