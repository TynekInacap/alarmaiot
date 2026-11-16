package cl.alarmaiot.ui

import cl.alarmaiot.AlarmaApp
import cl.alarmaiot.alarm.AlarmState
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.mqtt.MqttManager

/**
 * Cuando el teléfono está en modo Panel, guarda en SQLite todo lo que llega
 * de la Central y lanza la notificación de intrusión. Vive a nivel de
 * aplicación para que funcione aunque el usuario esté en otra pantalla.
 */
class PanelRecorder(private val app: AlarmaApp) : MqttManager.Listener {

    private var lastOnline: Boolean? = null

    private val isPanel get() = app.mqtt.mode == MqttManager.Mode.PANEL

    override fun onEvent(message: String, state: AlarmState?, username: String?, ts: Long) {
        if (!isPanel) return
        val category = when {
            message.startsWith("ALERTA") || message.startsWith("EVENTO") -> EventCategory.ALERTA
            message in SECURITY_ERRORS -> EventCategory.SEGURIDAD
            message.startsWith("OK") || message.startsWith("ERR") -> EventCategory.COMANDO
            else -> EventCategory.ESTADO
        }
        app.io.execute { app.events.log(category, message, "CENTRAL", state?.name, null, username) }
        if (message == "ALERTA INTRUSION") {
            Notifier.intrusion(app, "La central detectó un objeto a menos de 20 cm (${formatShortTime(System.currentTimeMillis())})")
        }
    }

    override fun onTelemetry(distance: Float?, state: AlarmState?, ts: Long) {
        if (!isPanel || distance == null) return
        app.io.execute { app.events.addReading(distance, "PANEL") }
    }

    override fun onCentralStatus(online: Boolean) {
        if (!isPanel || online == lastOnline) return
        lastOnline = online
        app.io.execute {
            app.events.log(EventCategory.CONEXION, if (online) "Central en línea" else "Central fuera de línea", "PANEL")
        }
    }

    override fun onLinkChanged(link: MqttManager.Link, detail: String) {
        if (link == MqttManager.Link.DESCONECTADO) lastOnline = null
    }

    companion object {
        private val SECURITY_ERRORS = setOf("ERR PIN_INVALIDO", "ERR BLOQUEADO", "ERR SIN_PERMISO", "ERR SIN_PIN")
    }
}
