package cl.alarmaiot.ui

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import cl.alarmaiot.alarm.AlarmStateMachine
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.databinding.ActivityHistoryBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Consulta de los datos almacenados en SQLite: eventos filtrables y estadísticas de lecturas. */
class HistoryActivity : SecureActivity() {

    private lateinit var b: ActivityHistoryBinding
    private val adapter = EventAdapter()
    private var filter: EventCategory? = null

    override fun onSecureCreate(savedInstanceState: Bundle?) {
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupScreen(b.root, b.toolbar, "Historial")

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        addChip("Todos", null, checked = true)
        EventCategory.entries.forEach { addChip(it.label, it) }

        if (user.role.canManage) {
            b.toolbar.menu.add("Borrar historial").setOnMenuItemClickListener {
                confirmClear()
                true
            }
        }
        load()
    }

    private fun addChip(label: String, category: EventCategory?, checked: Boolean = false) {
        val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    filter = category
                    load()
                }
            }
        }
        b.filterGroup.addView(chip)
    }

    private fun load() {
        val f = filter
        app.io.execute {
            val events = app.events.recent(limit = 300, category = f)
            val stats = app.events.readingStats(AlarmStateMachine.DEFAULT_THRESHOLD_CM)
            val readings = app.events.recentReadings(60)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                adapter.submit(events)
                b.emptyText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
                b.statsText.text = if (stats.count == 0) {
                    "Aún no hay lecturas guardadas."
                } else {
                    String.format(
                        Locale.getDefault(),
                        "%d lecturas · mínima %.0f cm · promedio %.0f cm · %d bajo 20 cm",
                        stats.count, stats.min ?: 0f, stats.avg ?: 0f, stats.nearCount,
                    )
                }
                b.chart.setData(readings.map { it.distance })
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Borrar historial")
            .setMessage("Se eliminarán todos los eventos y lecturas guardadas en este teléfono. Esta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                val name = user.username
                app.io.execute {
                    app.events.clearAll()
                    app.events.log(EventCategory.SEGURIDAD, "Historial borrado por $name", "APP", username = name)
                }
                load()
            }
            .show()
    }
}
