package com.copiloto.auto.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Cliente HTTP mínimo (sin dependencias). Android descomprime gzip automáticamente. */
object Http {
    private const val USER_AGENT = "Copiloto-AndroidAuto/1.0 (https://github.com/rubenlopezsuarez01-afk/Movil)"

    suspend fun get(url: String, timeoutMs: Int = 15_000): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs * 2
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", "es-ES,es")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code en $url")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
