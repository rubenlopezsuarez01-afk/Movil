package com.copiloto.auto.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.copiloto.core.LatLng
import com.copiloto.core.dialog.Place
import com.copiloto.core.dialog.Services
import com.copiloto.core.dialog.VoiceCommand
import com.copiloto.core.food.Overpass
import com.copiloto.core.food.Restaurant
import com.copiloto.core.fuel.FuelStation
import com.copiloto.core.fuel.FuelType
import com.copiloto.core.fuel.MinistryParser
import com.copiloto.core.fuel.Provinces
import com.copiloto.core.route.Osrm
import com.copiloto.core.route.RouteOption
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale

/**
 * Implementación real de [Services]:
 *  - Ubicación: Fused Location Provider.
 *  - Carburantes: API abierta del Ministerio (precios oficiales, actualizados cada ~30 min).
 *  - Restaurantes: OpenStreetMap (Overpass).
 *  - Geocodificación: Geocoder de Android, con Nominatim (OSM) como respaldo.
 *  - Rutas alternativas: OSRM.
 * Ninguna requiere clave de API.
 */
class AndroidServices(context: Context) : Services {
    private val app = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(app)
    private val geocoder = Geocoder(app, Locale("es", "ES"))

    private data class CacheEntry(val json: String, val at: Long)
    private val provinceCache = HashMap<String, CacheEntry>()
    private val provinceOfPoint = HashMap<Pair<Int, Int>, String?>()

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): LatLng? = try {
        val cts = CancellationTokenSource()
        val loc = withTimeoutOrNull(8_000) {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
        } ?: fused.lastLocation.await()
        loc?.let { LatLng(it.latitude, it.longitude) }
    } catch (e: SecurityException) {
        null
    }

    override suspend fun fuelStations(points: List<LatLng>, fuel: FuelType): List<FuelStation> = coroutineScope {
        val provinces = points.map { async { provinceFor(it) } }.awaitAll().toSet()
        if (provinces.all { it == null }) {
            // Sin provincia conocida: listado nacional (más pesado, pero funciona).
            return@coroutineScope MinistryParser.parse(cached("ES", "$MINISTRY/EstacionesTerrestres/"), fuel)
        }
        provinces.filterNotNull()
            .map { id -> async { MinistryParser.parse(cached(id, "$MINISTRY/EstacionesTerrestres/FiltroProvincia/$id"), fuel) } }
            .awaitAll()
            .flatten()
    }

    private suspend fun cached(key: String, url: String): String {
        val now = System.currentTimeMillis()
        synchronized(provinceCache) {
            provinceCache[key]?.takeIf { now - it.at < CACHE_MS }?.let { return it.json }
        }
        val json = Http.get(url, timeoutMs = 20_000)
        synchronized(provinceCache) { provinceCache[key] = CacheEntry(json, now) }
        return json
    }

    /** Provincia (código INE) para un punto, usando el código postal o el nombre de la provincia. */
    private suspend fun provinceFor(p: LatLng): String? {
        // Rejilla de ~10 km para no repetir geocodificaciones inversas.
        val cell = Pair((p.lat * 10).toInt(), (p.lng * 10).toInt())
        synchronized(provinceOfPoint) { if (provinceOfPoint.containsKey(cell)) return provinceOfPoint[cell] }
        val address = reverse(p)
        val id = address?.let {
            Provinces.fromPostalCode(it.postalCode) ?: Provinces.fromName(it.subAdminArea) ?: Provinces.fromName(it.adminArea)
        }
        synchronized(provinceOfPoint) { provinceOfPoint[cell] = id }
        return id
    }

    @Suppress("DEPRECATION")
    private suspend fun reverse(p: LatLng): Address? = withContext(Dispatchers.IO) {
        runCatching { geocoder.getFromLocation(p.lat, p.lng, 1)?.firstOrNull() }.getOrNull()
    }

    override suspend fun restaurants(command: VoiceCommand.FindFood, center: LatLng, radiusM: Int): List<Restaurant> {
        val q = Overpass.query(center, radiusM, command.cuisine, command.fastFood)
        return Overpass.parse(Http.get("${Overpass.ENDPOINT}?data=${enc(q)}", timeoutMs = 20_000))
    }

    @Suppress("DEPRECATION")
    override suspend fun geocode(query: String, near: LatLng?): Place? = withContext(Dispatchers.IO) {
        val viaGeocoder = runCatching {
            val list = if (near != null) {
                // Primero se prefieren resultados cercanos (~150 km); luego en cualquier sitio.
                geocoder.getFromLocationName(query, 1, near.lat - 1.4, near.lng - 1.8, near.lat + 1.4, near.lng + 1.8)
                    ?.takeIf { it.isNotEmpty() } ?: geocoder.getFromLocationName(query, 1)
            } else {
                geocoder.getFromLocationName(query, 1)
            }
            list?.firstOrNull()?.let { a -> Place(placeName(a, query), LatLng(a.latitude, a.longitude)) }
        }.getOrNull()
        viaGeocoder ?: nominatim(query, near)
    }

    private suspend fun nominatim(query: String, near: LatLng?): Place? {
        val bias = near?.let { "&viewbox=${it.lng - 1.8},${it.lat + 1.4},${it.lng + 1.8},${it.lat - 1.4}" } ?: ""
        val json = Http.get("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=es&accept-language=es&q=${enc(query)}$bias")
        val arr = JSONArray(json)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        val name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') }
        return Place(name.ifBlank { query }, LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
    }

    private fun placeName(a: Address, query: String): String =
        a.featureName?.takeIf { it.isNotBlank() && !it.all { c -> c.isDigit() } && it != a.postalCode }
            ?: a.locality ?: a.getAddressLine(0)?.substringBefore(',') ?: query

    override suspend fun routes(from: LatLng, to: LatLng, avoidTolls: Boolean, avoidMotorways: Boolean): List<RouteOption> {
        val exclude = when {
            avoidTolls -> "toll"
            avoidMotorways -> "motorway"
            else -> null
        }
        return try {
            Osrm.parse(Http.get(Osrm.url(from, to, exclude), timeoutMs = 20_000), tollFree = avoidTolls)
        } catch (e: Exception) {
            // Si el servidor no admite la exclusión, al menos devolvemos las rutas normales.
            if (exclude != null) Osrm.parse(Http.get(Osrm.url(from, to, null), timeoutMs = 20_000)) else throw e
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val MINISTRY = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes"
        const val CACHE_MS = 30 * 60 * 1000L
    }
}
