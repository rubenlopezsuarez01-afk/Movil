package com.copiloto.auto

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.copiloto.auto.data.Prefs
import com.copiloto.core.fuel.FuelType

/**
 * Pantalla del móvil: concede permisos y guarda preferencias
 * (combustible, radio de búsqueda, depósito, casa y trabajo).
 * El uso normal es desde Android Auto, solo con la voz.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val s = prefs.load()
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, pad, 0, pad / 3)
        }

        root.addView(label("Copiloto para Android Auto", 24f))
        root.addView(label(
            "En el coche di \"Ok Google, abre Copiloto\" y habla con normalidad:\n" +
                "• \"Gasolinera más barata\" / \"diésel más barato de camino\"\n" +
                "• \"Dónde comer\" / \"una pizzería cerca\"\n" +
                "• \"Rutas a Valencia sin peajes\" → \"la segunda\"\n" +
                "• \"Llévame a la primera\" · \"Continuar a mi destino\"", 14f,
        ))

        root.addView(Button(this).apply {
            text = "Conceder permisos (ubicación y micrófono)"
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO), 1)
            }
        })

        root.addView(label("Combustible de tu coche"))
        val fuels = RadioGroup(this)
        FuelType.entries.forEachIndexed { i, f ->
            fuels.addView(RadioButton(this).apply {
                id = 1000 + i
                text = f.spoken.replaceFirstChar { it.uppercase() }
                isChecked = f == s.fuel
            })
        }
        root.addView(fuels)

        fun number(hint: String, value: Int) = EditText(this).apply {
            this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER; setText(value.toString())
        }
        fun text(hint: String, value: String?) = EditText(this).apply {
            this.hint = hint; inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS; setText(value ?: "")
        }
        root.addView(label("Radio de búsqueda (km)"))
        val radius = number("10", s.radiusKm).also(root::addView)
        root.addView(label("Litros del depósito (para calcular el ahorro)"))
        val tank = number("50", s.tankLitres).also(root::addView)
        root.addView(label("Dirección de casa (\"llévame a casa\")"))
        val home = text("Calle, número, ciudad", s.home).also(root::addView)
        root.addView(label("Dirección del trabajo (\"llévame al trabajo\")"))
        val work = text("Calle, número, ciudad", s.work).also(root::addView)

        root.addView(Button(this).apply {
            text = "Guardar"
            gravity = Gravity.CENTER
            setOnClickListener {
                val fuel = FuelType.entries.getOrElse(fuels.checkedRadioButtonId - 1000) { s.fuel }
                prefs.save(
                    s.copy(
                        fuel = fuel,
                        radiusKm = radius.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: s.radiusKm,
                        tankLitres = tank.text.toString().toIntOrNull()?.coerceIn(10, 150) ?: s.tankLitres,
                        home = home.text.toString().trim().ifBlank { null },
                        work = work.text.toString().trim().ifBlank { null },
                    ),
                )
                Toast.makeText(this@MainActivity, "Guardado", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Toast.makeText(this, if (ok) "Listo. Ya puedes usarlo en Android Auto." else "Sin permisos no podré escucharte ni ubicarte.", Toast.LENGTH_LONG).show()
    }
}
