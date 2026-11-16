package cl.alarmaiot.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import cl.alarmaiot.R
import cl.alarmaiot.alarm.AlarmState
import cl.alarmaiot.alarm.AlarmStateMachine
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.data.Role
import cl.alarmaiot.databinding.ActivityCentralBinding
import cl.alarmaiot.mqtt.MqttManager
import cl.alarmaiot.security.CommandAuth
import com.google.android.material.snackbar.Snackbar

/**
 * MODO CENTRAL: el teléfono reemplaza al Arduino UNO de la Unidad 1.
 *  - Sensor: proximidad del teléfono, o distancia simulada con el deslizador (HC-SR04).
 *  - Actuadores: LEDs verde/amarillo/rojo en pantalla y sirena (tono + vibración).
 *  - Lee el sensor cada 200 ms, igual que el firmware.
 *  - Recibe comandos A/D/E por MQTT y publica estado, eventos y telemetría.
 */
class CentralActivity : SecureActivity(), SensorEventListener, MqttManager.Listener {

    override val lockOnIdle = true

    private lateinit var b: ActivityCentralBinding
    private val machine = AlarmStateMachine()
    private val handler = Handler(Looper.getMainLooper())

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityCm: Float? = null
    private var currentDistance: Float? = null
    private var tick = 0
    private val serialLines = ArrayDeque<String>()

    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var sirenOn = false

    /** Clave derivada del PIN de la alarma, para verificar los comandos del Panel. */
    private var pinKey: ByteArray? = null
    private var pinFailures = 0
    private var remoteLockedUntil = 0L

    override fun onSecureCreate(savedInstanceState: Bundle?) {
        b = ActivityCentralBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupScreen(b.root, b.toolbar, getString(R.string.mode_central))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        machine.restore(app.config.lastCentralState)
        b.homeIdText.text = "Casa: ${app.config.homeId}"

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) {
            b.proximitySwitch.isEnabled = false
            b.proximitySwitch.text = "Este teléfono no tiene sensor de proximidad"
        }
        b.proximitySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                proximitySensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            } else {
                sensorManager?.unregisterListener(this)
                proximityCm = null
            }
            b.distanceSlider.isEnabled = !checked
            b.sliderLabel.alpha = if (checked) 0.4f else 1f
        }

        b.armButton.setOnClickListener { localCommand('A') }
        b.disarmButton.setOnClickListener { localCommand('D') }
        b.queryButton.setOnClickListener { localCommand('E') }
        if (!user.role.canControlAlarm) {
            b.armButton.isEnabled = false
            b.disarmButton.isEnabled = false
        }

        tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) { null }
        vibrator = getSystemService(Vibrator::class.java)

        serial("ALARMA LISTA (${machine.stateMessage()})")
        renderState()
        if (machine.state == AlarmState.ALERTA) startSiren()

        val pin = app.config.alarmPin
        val homeId = app.config.homeId
        app.io.execute {
            val key = pin?.let { CommandAuth.deriveKey(it, homeId) }
            runOnUiThread { pinKey = key }
        }

        app.mqtt.addListener(this)
        app.mqtt.connect(MqttManager.Mode.CENTRAL)
        b.linkChip.showLink(app.mqtt.link, app.mqtt.linkDetail)
        handler.post(sensorLoop)
    }

    // ------------------------------------------------------------- sensor

    /** Bucle principal: equivale a loop() del Arduino, cada 200 ms. */
    private val sensorLoop = object : Runnable {
        override fun run() {
            val distance = readDistance()
            currentDistance = distance
            val outcome = machine.onDistance(distance)
            if (outcome.messages.isNotEmpty()) applyOutcome(outcome, command = null, username = null, origin = "SENSOR")
            b.distanceText.text = formatDistance(distance)
            tick++
            if (tick % 5 == 0) app.mqtt.publishTelemetry(distance, machine.state) // 1 vez por segundo
            if (tick % 25 == 0 && distance != null) {                   // cada 5 s a SQLite
                app.io.execute { app.events.addReading(distance, "CENTRAL") }
            }
            handler.postDelayed(this, MEASURE_MS)
        }
    }

    private fun readDistance(): Float? =
        if (b.proximitySwitch.isChecked && proximitySensor != null) {
            b.sourceText.text = "Fuente: sensor de proximidad del teléfono (acerca la mano)"
            proximityCm
        } else {
            b.sourceText.text = "Fuente: distancia simulada"
            b.distanceSlider.value
        }

    override fun onSensorChanged(event: SensorEvent) {
        val max = event.sensor.maximumRange
        val v = event.values[0]
        // El sensor de proximidad suele entregar solo "cerca" (0 cm) o "lejos" (rango máximo).
        proximityCm = if (v < max) v else FAR_CM
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------- comandos

    private fun localCommand(c: Char) {
        if (c != 'E' && !user.role.canControlAlarm) {
            Snackbar.make(b.root, "Tu rol (${user.role.label}) no permite armar o desarmar", Snackbar.LENGTH_SHORT).show()
            return
        }
        applyOutcome(machine.handleCommand(c), command = c, username = user.username, origin = "LOCAL")
    }

    /**
     * Comando recibido desde el Panel por MQTT (ya descifrado y sin repetición).
     * Armar y Desarmar además deben venir firmados con el PIN de la alarma:
     * aunque alguien manipule el Panel o tenga el código de vinculación, sin
     * el PIN la Central no obedece. Tras 5 PIN incorrectos bloquea el control
     * remoto 5 minutos.
     */
    override fun onCommand(command: Char, username: String, role: Role, proof: MqttManager.CommandProof) {
        serial("> $command  (de $username, ${role.label})")
        val c = command.uppercaseChar()
        if (c == 'A' || c == 'D') {
            val now = System.currentTimeMillis()
            if (now < remoteLockedUntil) {
                remoteReject("ERR BLOQUEADO", "Comando $c ignorado: control remoto bloqueado por PIN incorrectos", username)
                return
            }
            if (!role.canControlAlarm) {
                remoteReject("ERR SIN_PERMISO", "Comando $c rechazado: $username es ${role.label}", username)
                return
            }
            val key = pinKey
            if (key == null) {
                remoteReject("ERR SIN_PIN", "Comando $c rechazado: la Central no tiene PIN configurado", username)
                return
            }
            val valid = proof.proof != null && CommandAuth.verify(key, c, proof.nonce, proof.ts, proof.proof)
            if (!valid) {
                pinFailures++
                if (pinFailures >= MAX_PIN_FAILURES) {
                    pinFailures = 0
                    remoteLockedUntil = now + PIN_LOCK_MS
                    remoteReject("ERR BLOQUEADO", "PIN incorrecto $MAX_PIN_FAILURES veces: control remoto bloqueado 5 min", username)
                } else {
                    remoteReject("ERR PIN_INVALIDO", "Comando $c rechazado: PIN incorrecto (de $username)", username)
                }
                return
            }
            pinFailures = 0
        }
        applyOutcome(machine.handleCommand(command), command = command, username = username, origin = "PANEL")
    }

    private fun remoteReject(response: String, detail: String, username: String) {
        serial(response)
        app.mqtt.publishEvent(response, machine.state, username)
        app.io.execute {
            app.events.log(EventCategory.SEGURIDAD, detail, "CENTRAL", machine.state.name, username = username)
        }
    }

    private fun applyOutcome(outcome: AlarmStateMachine.Outcome, command: Char?, username: String?, origin: String) {
        val state = machine.state
        val distance = currentDistance
        outcome.messages.forEach {
            serial(it)
            app.mqtt.publishEvent(it, state, username)
        }
        if (outcome.stateChanged || command == 'E') app.mqtt.publishState(state, distance)
        if (outcome.stateChanged) {
            app.config.lastCentralState = state
            renderState()
            if (state == AlarmState.ALERTA) startSiren() else stopSiren()
        }
        val first = outcome.messages.firstOrNull().orEmpty()
        app.io.execute {
            when {
                outcome.intrusion -> app.events.log(
                    EventCategory.ALERTA, "Intrusión detectada a ${formatDistance(distance)}", "CENTRAL", state.name, distance,
                )
                command != null -> app.events.log(
                    EventCategory.COMANDO, "Comando $command ($origin) → $first", "CENTRAL", state.name, distance, username,
                )
            }
            if (outcome.stateChanged && !outcome.intrusion) {
                app.events.log(EventCategory.ESTADO, "Estado: ${state.name}", "CENTRAL", state.name, distance, username)
            }
        }
    }

    // --------------------------------------------------------------- MQTT

    override fun onLinkChanged(link: MqttManager.Link, detail: String) {
        b.linkChip.showLink(link, detail)
        if (link == MqttManager.Link.CONECTADO) {
            serial("MQTT CONECTADO")
            app.mqtt.publishState(machine.state, currentDistance)
        }
    }

    override fun onRejected(reason: String) {
        serial("RECHAZADO: $reason")
    }

    // ------------------------------------------------------------------ UI

    private fun renderState() {
        val state = machine.state
        b.stateCard.setCardBackgroundColor(stateColor(state))
        b.stateText.text = state.label
        val off = ContextCompat.getColor(this, R.color.led_off)
        b.ledGreen.tint(if (state == AlarmState.DESARMADA) ContextCompat.getColor(this, R.color.estado_desarmada) else off)
        b.ledYellow.tint(if (state == AlarmState.ARMADA) ContextCompat.getColor(this, R.color.led_amarillo) else off)
        b.ledRed.tint(if (state == AlarmState.ALERTA) ContextCompat.getColor(this, R.color.estado_alerta) else off)
        b.ledGreen.contentDescription = "LED verde " + if (state == AlarmState.DESARMADA) "encendido" else "apagado"
        b.ledYellow.contentDescription = "LED amarillo " + if (state == AlarmState.ARMADA) "encendido" else "apagado"
        b.ledRed.contentDescription = "LED rojo " + if (state == AlarmState.ALERTA) "encendido" else "apagado"
    }

    private fun serial(line: String) {
        serialLines.addLast("${formatShortTime(System.currentTimeMillis())}  $line")
        while (serialLines.size > 12) serialLines.removeFirst()
        b.serialLog.text = serialLines.joinToString("\n")
    }

    // -------------------------------------------------------------- sirena

    private val sirenLoop = object : Runnable {
        override fun run() {
            if (!sirenOn) return
            tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 350)
            b.ledRed.visibility = if (b.ledRed.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            handler.postDelayed(this, SIREN_PERIOD_MS)
        }
    }

    private fun startSiren() {
        if (sirenOn) return
        sirenOn = true
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0))
        handler.post(sirenLoop)
    }

    private fun stopSiren() {
        sirenOn = false
        handler.removeCallbacks(sirenLoop)
        tone?.stopTone()
        vibrator?.cancel()
        b.ledRed.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        if (::b.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            sensorManager?.unregisterListener(this)
            stopSiren()
            tone?.release()
            app.mqtt.removeListener(this)
            if (isFinishing) app.mqtt.disconnect()
        }
        super.onDestroy()
    }

    companion object {
        private const val MEASURE_MS = 200L
        private const val SIREN_PERIOD_MS = 700L
        private const val FAR_CM = 100f
        private const val MAX_PIN_FAILURES = 5
        private const val PIN_LOCK_MS = 5 * 60_000L
    }
}
