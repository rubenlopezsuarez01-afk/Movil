package com.copiloto.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

private const val EARTH_RADIUS_M = 6_371_000.0

private fun Double.rad() = this * PI / 180.0

/** Distancia ortodrómica (haversine) en metros. */
fun distanceMeters(a: LatLng, b: LatLng): Double {
    val dLat = (b.lat - a.lat).rad()
    val dLng = (b.lng - a.lng).rad()
    val h = sin(dLat / 2).let { it * it } +
        cos(a.lat.rad()) * cos(b.lat.rad()) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * Distancia mínima (metros) de [p] a la polilínea [line]. Usa una proyección
 * equirectangular local, suficientemente precisa para distancias de pocos km.
 */
fun distanceToPolylineMeters(p: LatLng, line: List<LatLng>): Double {
    if (line.isEmpty()) return Double.MAX_VALUE
    if (line.size == 1) return distanceMeters(p, line[0])
    val kx = cos(p.lat.rad()) * EARTH_RADIUS_M * PI / 180.0
    val ky = EARTH_RADIUS_M * PI / 180.0
    var best = Double.MAX_VALUE
    for (i in 0 until line.size - 1) {
        val ax = (line[i].lng - p.lng) * kx
        val ay = (line[i].lat - p.lat) * ky
        val bx = (line[i + 1].lng - p.lng) * kx
        val by = (line[i + 1].lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        best = min(best, sqrt(cx * cx + cy * cy))
    }
    return best
}

/** Decodifica una polilínea codificada (formato Google/OSRM, precisión 5). */
fun decodePolyline(encoded: String, precision: Int = 5): List<LatLng> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = ArrayList<LatLng>()
    var index = 0
    var lat = 0L
    var lng = 0L
    while (index < encoded.length) {
        for (coord in 0..1) {
            var shift = 0
            var result = 0L
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            if (coord == 0) lat += delta else lng += delta
        }
        out.add(LatLng(lat / factor, lng / factor))
    }
    return out
}

/** Puntos de la ruta separados aproximadamente [everyMeters] (incluye el primero y el último). */
fun samplePolyline(line: List<LatLng>, everyMeters: Double, max: Int = 12): List<LatLng> {
    if (line.isEmpty()) return emptyList()
    val out = mutableListOf(line.first())
    var acc = 0.0
    for (i in 1 until line.size) {
        acc += distanceMeters(line[i - 1], line[i])
        if (acc >= everyMeters) {
            out.add(line[i]); acc = 0.0
            if (out.size >= max - 1) break
        }
    }
    if (out.last() != line.last()) out.add(line.last())
    return out
}

/** Punto de [route] más alejado de [reference]; sirve para forzar una ruta alternativa. */
fun farthestPointFrom(route: List<LatLng>, reference: List<LatLng>): LatLng? {
    if (route.size < 3) return null
    // Se ignoran los extremos (origen y destino son comunes a todas las rutas).
    val inner = route.subList(route.size / 10, route.size - route.size / 10)
    return inner.maxByOrNull { distanceToPolylineMeters(it, reference) }
}
