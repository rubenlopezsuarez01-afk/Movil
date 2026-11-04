package com.copiloto.auto.data

import android.content.Context
import com.copiloto.core.dialog.Settings
import com.copiloto.core.fuel.FuelType

/** Preferencias compartidas entre el móvil (MainActivity) y Android Auto. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("copiloto", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        fuel = runCatching { FuelType.valueOf(sp.getString(KEY_FUEL, null) ?: "") }.getOrDefault(FuelType.GASOLINA_95),
        radiusKm = sp.getInt(KEY_RADIUS, 10),
        tankLitres = sp.getInt(KEY_TANK, 50),
        home = sp.getString(KEY_HOME, null)?.takeIf { it.isNotBlank() },
        work = sp.getString(KEY_WORK, null)?.takeIf { it.isNotBlank() },
    )

    fun save(s: Settings) {
        sp.edit()
            .putString(KEY_FUEL, s.fuel.name)
            .putInt(KEY_RADIUS, s.radiusKm)
            .putInt(KEY_TANK, s.tankLitres)
            .putString(KEY_HOME, s.home)
            .putString(KEY_WORK, s.work)
            .apply()
    }

    private companion object {
        const val KEY_FUEL = "fuel"
        const val KEY_RADIUS = "radius_km"
        const val KEY_TANK = "tank_litres"
        const val KEY_HOME = "home"
        const val KEY_WORK = "work"
    }
}
