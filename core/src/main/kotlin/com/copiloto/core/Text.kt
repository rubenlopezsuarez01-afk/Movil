package com.copiloto.core

import java.text.Normalizer
import java.util.Locale

private val MARKS = Regex("\\p{M}+")
private val NON_ALNUM = Regex("[^a-z0-9 ]")
private val SPACES = Regex("\\s+")

/** Minúsculas, sin tildes ni signos de puntuación y con los espacios colapsados. */
fun String.normalizeEs(): String {
    val lower = lowercase(Locale.ROOT)
    val noMarks = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    return SPACES.replace(NON_ALNUM.replace(noMarks, " "), " ").trim()
}

private val NUMBER_WORDS = mapOf(
    "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
    "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11,
    "doce" to 12, "quince" to 15, "veinte" to 20, "veinticinco" to 25, "treinta" to 30,
    "cuarenta" to 40, "cincuenta" to 50, "cien" to 100,
)

/** Convierte un número escrito como cifra o como palabra ("diez") a entero. */
fun parseSpanishNumber(token: String): Int? = token.toIntOrNull() ?: NUMBER_WORDS[token]

/** "REPSOL" -> "Repsol", "AVENIDA DE LA PAZ, 12" -> "Avenida de la Paz, 12". */
fun String.prettyName(): String {
    val small = setOf("de", "del", "la", "las", "los", "el", "y", "en", "a")
    return lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }.mapIndexed { i, w ->
        if (i > 0 && w in small) w else w.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }.joinToString(" ")
}
