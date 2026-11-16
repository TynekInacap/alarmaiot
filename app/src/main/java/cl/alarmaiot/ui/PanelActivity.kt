package cl.alarmaiot.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import cl.alarmaiot.R
import cl.alarmaiot.alarm.AlarmState
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.data.EventRecord
import cl.alarmaiot.databinding.ActivityPanelBinding
import cl.alarmaiot.databinding.DialogPasswordBinding
import cl.alarmaiot.databinding.ItemEventBinding
import cl.alarmaiot.mqtt.MqttManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * MODO PANEL: el teléfono del dueño de casa. Envía comandos a la Central,
 * muestra el estado y la distancia en tiempo real, guarda el historial y
 * permite llamar al 133 ante una intrusión.
 */
class PanelActivity : SecureActivity(), MqttManager.Listener {

    override val lockOnIdle = true

    private lateinit var b: ActivityPanelBinding
    private var state: AlarmState? = null
    private var lastUpdate = 0L

    override fun onSecureCreate(savedInstanceState: Bundle?) {
        b = ActivityPanelBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupScreen(b.root, b.toolbar, getString(R.string.mode_panel))
        b.toolbar.subtitle = "Casa: ${app.config.homeId}"

        b.armButton.setOnClickListener { send('A') }
        b.disarmButton.setOnClickListener { send('D') }
        b.queryButton.setOnClickListener { send('E') }
        b.callButton.setOnClickListener { call133() }
        b.historyButton.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        if (!user.role.canControlAlarm) {
            b.armButton.isEnabled = false
            b.disarmButton.isEnabled = false
            b.permissionNote.visibility = View.VISIBLE
            b.permissionNote.text = "Tu rol es ${user.role.label}: solo puedes monitorear y consultar el estado."
        }

        renderState()
        b.distanceText.text = formatDistance(null)
        b.linkChip.showLink(app.mqtt.link, app.mqtt.linkDetail)
        renderCentral(app.mqtt.centralOnline)

        app.mqtt.addListener(this)
        app.mqtt.connect(MqttManager.Mode.PANEL)
        loadFromDb()
    }

    private fun loadFromDb() {
        app.io.execute {
            val events = app.events.recent(limit = 6)
            val readings = app.events.recentReadings(60)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                renderEvents(events)
                if (b.chart.isEmpty()) b.chart.setData(readings.map { it.distance })
            }
        }
    }

    private fun send(c: Char) {
        if (c == 'E') {
            dispatch(c, null)
            return
        }
        if (!user.role.canControlAlarm) return
        askPin(if (c == 'A') "Armar la alarma" else "Desarmar la alarma") { pin -> dispatch(c, pin) }
    }

    /** Como el teclado de una alarma real: Armar/Desarmar exigen el PIN, que el Panel no guarda. */
    private fun askPin(title: String, onPin: (String) -> Unit) {
        val d = DialogPasswordBinding.inflate(LayoutInflater.from(this))
        d.passLayout.hint = getString(R.string.alarm_pin)
        d.password.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        d.password.filters = arrayOf(InputFilter.LengthFilter(8))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("Ingresa el PIN de la alarma configurado en la Central.")
            .setView(d.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = d.password.text?.toString().orEmpty()
                if (pin.length !in 6..8) {
                    d.passLayout.error = "El PIN tiene entre 6 y 8 dígitos"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onPin(pin)
            }
        }
        dialog.show()
    }

    private fun dispatch(c: Char, pin: String?) {
        val name = when (c) { 'A' -> "Armar"; 'D' -> "Desarmar"; else -> "Consultar estado" }
        val u = user
        app.mqtt.sendCommand(c, u, pin) { ok ->
            if (isDestroyed) return@sendCommand
            if (!ok) {
                Snackbar.make(b.root, "Sin conexión con el broker. Revisa la red.", Snackbar.LENGTH_LONG).show()
                return@sendCommand
            }
            Snackbar.make(b.root, "Comando enviado: $name", Snackbar.LENGTH_SHORT).show()
            app.io.execute {
                app.events.log(EventCategory.COMANDO, "Enviado comando $c ($name)", "PANEL", state?.name, username = u.username)
            }
            loadFromDb()
        }
    }

    private fun call133() {
        // ACTION_DIAL abre el marcador con el número listo: no requiere el permiso CALL_PHONE.
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:133")))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(b.root, "Este dispositivo no puede realizar llamadas", Snackbar.LENGTH_LONG).show()
        }
    }

    // --------------------------------------------------------------- MQTT

    override fun onLinkChanged(link: MqttManager.Link, detail: String) {
        b.linkChip.showLink(link, detail)
    }

    override fun onCentralStatus(online: Boolean) = renderCentral(online)

    override fun onState(state: AlarmState, distance: Float?, ts: Long) {
        this.state = state
        lastUpdate = if (ts > 0) ts else System.currentTimeMillis()
        renderState()
    }

    override fun onTelemetry(distance: Float?, state: AlarmState?, ts: Long) {
        b.distanceText.text = formatDistance(distance)
        distance?.let { b.chart.add(it) }
        // La telemetría (autenticada y sin repetición) confirma el estado cada segundo.
        if (state != null) {
            lastUpdate = ts
            if (state != this.state) this.state = state
            renderState()
        }
    }

    override fun onEvent(message: String, state: AlarmState?, username: String?, ts: Long) {
        if (state != null) {
            this.state = state
            lastUpdate = System.currentTimeMillis()
            renderState()
        }
        when {
            message == "ALERTA INTRUSION" -> Snackbar.make(b.root, "¡INTRUSIÓN DETECTADA!", Snackbar.LENGTH_LONG)
                .setAction("Llamar 133") { call133() }.show()
            message.startsWith("ERR") -> Snackbar.make(b.root, "La central respondió: $message", Snackbar.LENGTH_LONG).show()
            message.startsWith("OK") -> Snackbar.make(b.root, "La central confirmó: $message", Snackbar.LENGTH_SHORT).show()
        }
        loadFromDb()
    }

    override fun onRejected(reason: String) {
        Snackbar.make(b.root, reason, Snackbar.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ UI

    private fun renderState() {
        val s = state
        b.stateCard.setCardBackgroundColor(stateColor(s))
        b.stateText.text = s?.label ?: "Sin datos"
        b.stateDetail.text = if (s == null) "Esperando a la central…" else "Actualizado ${formatShortTime(lastUpdate)}"
        val alert = s == AlarmState.ALERTA
        val red = ContextCompat.getColor(this, R.color.emergencia)
        b.callButton.backgroundTintList = ColorStateList.valueOf(if (alert) red else android.graphics.Color.TRANSPARENT)
        b.callButton.setTextColor(if (alert) android.graphics.Color.WHITE else red)
        b.callButton.iconTint = ColorStateList.valueOf(if (alert) android.graphics.Color.WHITE else red)
    }

    private fun renderCentral(online: Boolean?) {
        b.centralChip.text = when (online) {
            true -> "Central en línea"
            false -> "Central fuera de línea"
            null -> "Central: sin datos"
        }
        b.centralChip.chipIcon = ContextCompat.getDrawable(this, R.drawable.led_circle)
        b.centralChip.chipIconTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (online == true) R.color.estado_desarmada else R.color.estado_desconocido)
        )
    }

    private fun renderEvents(events: List<EventRecord>) {
        b.eventsContainer.removeAllViews()
        if (events.isEmpty()) {
            val empty = ItemEventBinding.inflate(LayoutInflater.from(this), b.eventsContainer, false)
            empty.detail.text = "Aún no hay eventos"
            empty.meta.text = ""
            empty.dot.visibility = View.INVISIBLE
            b.eventsContainer.addView(empty.root)
            return
        }
        events.forEach { e ->
            val row = ItemEventBinding.inflate(LayoutInflater.from(this), b.eventsContainer, false)
            EventAdapter.bind(row, e)
            b.eventsContainer.addView(row.root)
        }
    }

    override fun onDestroy() {
        if (::b.isInitialized) app.mqtt.removeListener(this)
        super.onDestroy()
    }
}
