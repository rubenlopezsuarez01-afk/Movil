package com.copiloto.core

import java.util.Locale
import kotlin.math.roundToInt

/** Formateadores pensados para ser leídos en voz alta por el TTS en español. */
object Speech {
    private val ORDINALS = listOf("primera", "segunda", "tercera", "cuarta", "quinta", "sexta")
    private val ORDINALS_M = listOf("primero", "segundo", "tercero", "cuarto", "quinto", "sexto")

    fun ordinal(index: Int, feminine: Boolean = true): String =
        (if (feminine) ORDINALS else ORDINALS_M).getOrElse(index) { "número ${index + 1}" }

    fun distance(meters: Double): String = when {
        meters < 950 -> "${((meters / 50).roundToInt() * 50).coerceAtLeast(50)} metros"
        meters < 10_000 -> {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == km.toInt().toDouble()) "${km.toInt()} ${if (km.toInt() == 1) "kilómetro" else "kilómetros"}"
            else "${decimal(km, 1)} kilómetros"
        }
        else -> "${(meters / 1000).roundToInt()} kilómetros"
    }

    fun shortDistance(meters: Double): String =
        if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else "${decimal(meters / 1000, 1)} km"

    fun duration(seconds: Double): String {
        val totalMin = (seconds / 60).roundToInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        val minutes = if (m == 1) "1 minuto" else "$m minutos"
        return when {
            h == 0 -> minutes
            m == 0 -> if (h == 1) "1 hora" else "$h horas"
            else -> "${if (h == 1) "1 hora" else "$h horas"} y $minutes"
        }
    }

    fun shortDuration(seconds: Double): String {
        val totalMin = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (totalMin < 60) "$totalMin min" else "${totalMin / 60} h ${totalMin % 60} min"
    }

    fun price(eurosPerLitre: Double): String = "${decimal(eurosPerLitre, 3)} euros"

    fun shortPrice(eurosPerLitre: Double): String = "${decimal(eurosPerLitre, 3)} €/l"

    fun euros(amount: Double): String {
        val cents = (amount * 100).roundToInt()
        return if (cents % 100 == 0) "${cents / 100} euros" else "${decimal(amount, 2)} euros"
    }

    fun decimal(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value).replace('.', ',')

    /** "a, b y c" */
    fun join(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " y " + parts.last()
    }
}
