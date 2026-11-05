package com.copiloto.core.apps

import com.copiloto.core.normalizeEs

/** Notificación capturada del móvil (correo, Flashscore, banco, cualquier app). */
data class AppNotification(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timeMillis: Long,
)

/** Qué hacer cuando el usuario dice "abre X". */
enum class AppIntent {
    /** Apps de gasolina (GasofApp, etc.): usamos los mismos precios oficiales. */
    FUEL,
    /** Gmail, Outlook…: se leen los correos que han llegado como notificación. */
    MAIL,
    /** Flashscore, resultados…: se consultan los partidos del día. */
    SPORTS,
    /** Cualquier otra app: se leen sus notificaciones. */
    OTHER,
}

object Apps {
    private val FUEL_APPS = listOf("gasofapp", "gasof app", "gasolina barata", "gasolineras", "ahorro gasolina", "clickgasoil", "waylet", "repsol")
    private val MAIL_APPS = listOf("correo", "correos", "email", "emails", "e mail", "mail", "mails", "gmail", "outlook", "hotmail", "yahoo", "bandeja de entrada")
    private val SPORTS_APPS = listOf("flashscore", "flash score", "flashcore", "resultados", "marcador", "marcadores", "sofascore", "onefootball", "livescore")

    /** Palabras clave (en minúsculas y sin tildes) del nombre visible de las apps de correo. */
    val MAIL_LABELS = listOf("gmail", "outlook", "correo", "mail", "email", "yahoo", "proton", "spark")

    fun classify(spokenName: String): AppIntent {
        val n = " ${spokenName.normalizeEs()} "
        return when {
            FUEL_APPS.any { n.contains(" $it ") } -> AppIntent.FUEL
            MAIL_APPS.any { n.contains(" $it ") } -> AppIntent.MAIL
            SPORTS_APPS.any { n.contains(" $it ") } -> AppIntent.SPORTS
            else -> AppIntent.OTHER
        }
    }

    /** Filtra notificaciones por el nombre de la app tal como lo dice el usuario ("flashscore", "el banco"). */
    fun filter(all: List<AppNotification>, spokenName: String?): List<AppNotification> {
        if (spokenName == null) return all
        if (classify(spokenName) == AppIntent.MAIL) return all.filter { isMail(it) }
        val wanted = spokenName.normalizeEs().removePrefix("la ").removePrefix("el ").replace(" ", "")
        if (wanted.isBlank()) return all
        return all.filter {
            val label = it.appName.normalizeEs().replace(" ", "")
            label.contains(wanted) || (label.length >= 4 && wanted.contains(label)) ||
                it.packageName.lowercase().contains(wanted)
        }
    }

    fun isMail(n: AppNotification): Boolean {
        val label = n.appName.normalizeEs()
        return MAIL_LABELS.any { label.contains(it) } || n.packageName == "com.google.android.gm"
    }

    /** Texto breve para leer en voz alta: "Gmail: Amazon, Tu pedido ha sido enviado". */
    fun spoken(n: AppNotification, includeApp: Boolean): String {
        val body = n.text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 160) it.take(157).substringBeforeLast(' ') + "…" else it }
        val head = if (includeApp) "${n.appName}: " else ""
        return when {
            n.title.isBlank() -> "$head$body"
            body.isBlank() -> "$head${n.title}"
            else -> "$head${n.title}, $body"
        }
    }
}
