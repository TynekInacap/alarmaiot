package cl.alarmaiot.mqtt

import android.os.Handler
import android.os.Looper
import cl.alarmaiot.AlarmaApp
import cl.alarmaiot.alarm.AlarmState
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.data.Role
import cl.alarmaiot.data.User
import cl.alarmaiot.security.CommandAuth
import cl.alarmaiot.security.RateLimiter
import cl.alarmaiot.security.ReplayGuard
import cl.alarmaiot.security.SecureChannel
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs

/**
 * Comunicación inalámbrica entre los dos teléfonos mediante MQTT sobre Wi-Fi.
 *
 * Tópicos (todos bajo alarmaiot/v1/{homeId}/):
 *   cmd        Panel  -> Central  comandos A / D / E, A y D firmados con el PIN de la alarma
 *   state      Central -> Panel   estado actual (retenido: el panel lo recibe al conectarse)
 *   event      Central -> Panel   respuestas y eventos (OK ARMAR, ALERTA INTRUSION, ...)
 *   telemetry  Central -> Panel   distancia + estado cada 1 s (también sirve de "latido")
 *   status     Central -> Panel   "offline" del Last Will (aviso rápido de caída)
 *
 * Seguridad:
 *   - TLS hacia el broker + cifrado AES-GCM extremo a extremo con el código de vinculación.
 *   - Comandos Armar/Desarmar firmados con el PIN (HMAC) y verificados por la Central.
 *   - Anti-repetición (marca de tiempo + nonce) en comandos, eventos y telemetría;
 *     un estado más antiguo que el último recibido se descarta.
 *   - "Central en línea" solo se muestra si llegan mensajes AUTENTICADOS y recientes:
 *     el tópico status va sin cifrar y por eso nunca se usa para decir "en línea".
 */
class MqttManager(private val app: AlarmaApp) {

    enum class Mode { CENTRAL, PANEL }
    enum class Link(val label: String) {
        DESCONECTADO("Desconectado"),
        CONECTANDO("Conectando…"),
        CONECTADO("Conectado"),
        ERROR("Error de conexión"),
    }

    /** Datos para que la Central verifique la firma del PIN. */
    data class CommandProof(val nonce: String, val ts: Long, val proof: String?)

    interface Listener {
        fun onLinkChanged(link: Link, detail: String) {}
        fun onCommand(command: Char, username: String, role: Role, proof: CommandProof) {}
        fun onState(state: AlarmState, distance: Float?, ts: Long) {}
        fun onEvent(message: String, state: AlarmState?, username: String?, ts: Long) {}
        fun onTelemetry(distance: Float?, state: AlarmState?, ts: Long) {}
        fun onCentralStatus(online: Boolean) {}
        fun onRejected(reason: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val random = SecureRandom()
    private val replayGuard = ReplayGuard(windowMs = 60_000)
    private val commandLimiter = RateLimiter(maxEvents = 10, windowMs = 10_000)
    private val rejectLogLimiter = RateLimiter(maxEvents = 3, windowMs = 30_000)
    private val deviceId = "aiot-" + randomHex(4)

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var channel: SecureChannel? = null
    @Volatile private var base = ""
    @Volatile private var generation = 0
    @Volatile private var lastStateTs = 0L
    @Volatile private var lastAuthenticAt = 0L

    @Volatile var mode: Mode? = null
        private set
    @Volatile var link: Link = Link.DESCONECTADO
        private set
    @Volatile var linkDetail: String = ""
        private set
    @Volatile var centralOnline: Boolean? = null
        private set

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    val isConnected: Boolean get() = client?.isConnected == true

    fun connect(newMode: Mode) {
        if (mode == newMode && (link == Link.CONECTADO || link == Link.CONECTANDO)) return
        disconnect()
        mode = newMode
        lastStateTs = 0L
        lastAuthenticAt = 0L
        val gen = ++generation
        val cfg = app.config
        val code = cfg.pairingCode
        if (code == null || code.length < SecureChannel.MIN_CODE_LENGTH) {
            setLink(Link.ERROR, "Falta el código de vinculación (Configuración)")
            return
        }
        setLink(Link.CONECTANDO, cfg.brokerUri)
        app.io.execute {
            try {
                val homeId = cfg.homeId
                channel = SecureChannel(code, homeId) // PBKDF2: fuera del hilo principal
                base = "alarmaiot/v1/$homeId"
                val c = MqttAsyncClient(cfg.brokerUri, "$deviceId-${newMode.name.lowercase()}", MemoryPersistence())
                c.setCallback(Callback(c, newMode))
                if (gen != generation) return@execute
                client = c
                doConnect(c, newMode, gen)
            } catch (e: Exception) {
                setLink(Link.ERROR, e.message ?: "No se pudo iniciar MQTT")
            }
        }
    }

    private fun buildOptions(m: Mode) = MqttConnectOptions().apply {
        isCleanSession = true
        isAutomaticReconnect = true
        connectionTimeout = 15
        keepAliveInterval = 30
        maxInflight = 50
        socketFactory = SSLSocketFactory.getDefault()  // TLS con certificados del sistema
        isHttpsHostnameVerificationEnabled = true      // verifica que el certificado sea del broker
        val cfg = app.config
        if (cfg.mqttUser.isNotBlank()) {
            userName = cfg.mqttUser
            password = (cfg.mqttPassword ?: "").toCharArray()
        }
        if (m == Mode.CENTRAL) setWill("$base/status", "offline".toByteArray(), 1, true)
    }

    private fun doConnect(c: MqttAsyncClient, m: Mode, gen: Int) {
        try {
            c.connect(buildOptions(m), null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit // connectComplete se encarga
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (gen != generation) return
                    setLink(Link.ERROR, friendlyError(exception))
                    // Reintento automático del primer intento (Paho solo reconecta tras una conexión exitosa).
                    main.postDelayed({
                        if (gen == generation && client === c && !c.isConnected) {
                            setLink(Link.CONECTANDO, "Reintentando…")
                            app.io.execute { doConnect(c, m, gen) }
                        }
                    }, RETRY_MS)
                }
            })
        } catch (e: Exception) {
            setLink(Link.ERROR, friendlyError(e))
        }
    }

    fun disconnect() {
        generation++
        main.removeCallbacksAndMessages(null)
        val c = client
        val wasCentral = mode == Mode.CENTRAL
        val b = base
        client = null
        mode = null
        centralOnline = null
        if (c != null) {
            app.io.execute {
                try {
                    if (c.isConnected) {
                        if (wasCentral) c.publish("$b/status", "offline".toByteArray(), 1, true).waitForCompletion(2_000)
                        c.disconnect().waitForCompletion(2_000)
                    }
                } catch (_: Exception) {
                } finally {
                    try { c.close() } catch (_: Exception) {}
                }
            }
        }
        setLink(Link.DESCONECTADO, "")
    }

    // ---------------------------------------------------------------- envío

    /**
     * Envía un comando. Armar/Desarmar se firman con el PIN de la alarma
     * (PBKDF2 + HMAC, por eso se calcula fuera del hilo principal).
     * [done] se llama en el hilo principal con true si el mensaje salió.
     */
    fun sendCommand(command: Char, user: User, pin: String?, done: (Boolean) -> Unit) {
        val homeId = app.config.homeId
        app.io.execute {
            val nonce = randomHex(12)
            val ts = System.currentTimeMillis()
            val body = JSONObject()
                .put("cmd", command.toString())
                .put("user", user.username)
                .put("role", user.role.name)
                .put("ts", ts)
                .put("nonce", nonce)
            if (pin != null) {
                body.put("proof", CommandAuth.sign(CommandAuth.deriveKey(pin, homeId), command, nonce, ts))
            }
            val ok = publish(TOPIC_CMD, body)
            main.post { done(ok) }
        }
    }

    fun publishState(state: AlarmState, distance: Float?): Boolean =
        publish(TOPIC_STATE, JSONObject().put("state", state.name).putDistance(distance), retained = true)

    fun publishEvent(message: String, state: AlarmState, username: String?): Boolean =
        publish(TOPIC_EVENT, JSONObject().put("msg", message).put("state", state.name).put("user", username ?: ""))

    fun publishTelemetry(distance: Float?, state: AlarmState): Boolean =
        publish(TOPIC_TELE, JSONObject().putDistance(distance).put("state", state.name), qos = 0)

    private fun publish(sub: String, body: JSONObject, retained: Boolean = false, qos: Int = 1): Boolean {
        val c = client ?: return false
        val ch = channel ?: return false
        if (!c.isConnected) return false
        return try {
            if (!body.has("ts")) body.put("ts", System.currentTimeMillis())
            if (!body.has("nonce")) body.put("nonce", randomHex(12))
            body.put("from", deviceId)
            val topic = "$base/$sub"
            c.publish(topic, ch.seal(body.toString(), topic).toByteArray(), qos, retained)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------- recepción

    private inner class Callback(private val c: MqttAsyncClient, private val m: Mode) : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String) {
            if (client !== c) return
            try {
                if (m == Mode.CENTRAL) {
                    c.subscribe("$base/$TOPIC_CMD", 1)
                    // "online" se publica solo para limpiar el "offline" retenido; el Panel no lo usa para decidir.
                    c.publish("$base/status", "online".toByteArray(), 1, true)
                } else {
                    c.subscribe(
                        arrayOf("$base/$TOPIC_STATE", "$base/$TOPIC_EVENT", "$base/$TOPIC_TELE", "$base/status"),
                        intArrayOf(1, 1, 0, 1),
                    )
                    main.removeCallbacks(onlineWatchdog)
                    main.postDelayed(onlineWatchdog, WATCHDOG_MS)
                }
            } catch (_: Exception) {
            }
            setLink(Link.CONECTADO, serverURI)
            app.io.execute {
                app.events.log(EventCategory.CONEXION, if (reconnect) "Reconectado al broker" else "Conectado al broker (TLS)", m.name)
            }
        }

        override fun connectionLost(cause: Throwable?) {
            if (client !== c) return
            setLink(Link.CONECTANDO, "Conexión perdida, reconectando…")
            app.io.execute { app.events.log(EventCategory.CONEXION, "Conexión perdida con el broker", m.name) }
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            if (client !== c) return
            handleMessage(topic, message.payload, m)
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    /** Marca la Central fuera de línea si deja de enviar mensajes autenticados. */
    private val onlineWatchdog = object : Runnable {
        override fun run() {
            if (mode != Mode.PANEL) return
            if (centralOnline == true && System.currentTimeMillis() - lastAuthenticAt > OFFLINE_AFTER_MS) {
                setCentralOnline(false)
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun setCentralOnline(online: Boolean) {
        if (centralOnline == online) return
        centralOnline = online
        post { it.onCentralStatus(online) }
    }

    private fun handleMessage(topic: String, payload: ByteArray, m: Mode) {
        val sub = topic.removePrefix("$base/")
        if (sub == "status") {
            // Sin cifrar: solo se acepta como aviso de CAÍDA. "online" nunca se cree sin mensajes autenticados.
            if (m == Mode.PANEL && String(payload) == "offline") {
                lastAuthenticAt = 0L
                main.post { setCentralOnline(false) }
            }
            return
        }
        if (payload.isEmpty()) return
        val plain = channel?.open(String(payload), topic)
        if (plain == null) {
            reject("Mensaje rechazado en '$sub': no se pudo autenticar (código de vinculación distinto o mensaje alterado)")
            return
        }
        val json = try { JSONObject(plain) } catch (e: Exception) { reject("Mensaje con formato inválido"); return }
        val ts = json.optLong("ts", 0L)
        val nonce = json.optString("nonce")
        when (sub) {
            TOPIC_CMD -> {
                if (m != Mode.CENTRAL) return
                if (!replayGuard.accept(nonce, ts)) {
                    reject("Comando rechazado: repetido o fuera de tiempo (posible ataque de repetición)")
                    return
                }
                if (!commandLimiter.tryAcquire()) {
                    reject("Comando rechazado: demasiados comandos en poco tiempo")
                    return
                }
                val cmd = json.optString("cmd").firstOrNull() ?: return
                val user = json.optString("user").take(20)
                val role = Role.parse(json.optString("role"))
                val proof = CommandProof(nonce, ts, json.optString("proof").takeIf { it.isNotEmpty() })
                post { it.onCommand(cmd, user, role, proof) }
            }
            TOPIC_STATE -> {
                if (m != Mode.PANEL) return
                if (ts <= lastStateTs) return // estado igual o más antiguo que el último conocido: se ignora
                lastStateTs = ts
                val state = AlarmState.parse(json.optString("state")) ?: return
                val d = json.distanceOrNull()
                markAuthentic(ts)
                post { it.onState(state, d, ts) }
            }
            TOPIC_EVENT -> {
                if (m != Mode.PANEL) return
                if (!replayGuard.accept(nonce, ts)) {
                    reject("Evento rechazado: repetido o fuera de tiempo (posible ataque de repetición)")
                    return
                }
                val msg = json.optString("msg").take(120)
                val state = AlarmState.parse(json.optString("state"))
                val user = json.optString("user").takeIf { it.isNotBlank() }
                markAuthentic(ts)
                post { it.onEvent(msg, state, user, ts) }
            }
            TOPIC_TELE -> {
                if (m != Mode.PANEL) return
                if (!replayGuard.accept(nonce, ts)) return // telemetría repetida: se descarta en silencio
                val d = json.distanceOrNull()
                val state = AlarmState.parse(json.optString("state"))
                if (state != null && ts > lastStateTs) lastStateTs = ts
                markAuthentic(ts)
                post { it.onTelemetry(d, state, ts) }
            }
        }
    }

    /** Un mensaje autenticado y reciente demuestra que la Central está en línea. */
    private fun markAuthentic(ts: Long) {
        val now = System.currentTimeMillis()
        if (abs(now - ts) > FRESH_MS) return
        lastAuthenticAt = now
        main.post { setCentralOnline(true) }
    }

    private fun reject(reason: String) {
        if (rejectLogLimiter.tryAcquire()) {
            app.io.execute { app.events.log(EventCategory.SEGURIDAD, reason, mode?.name ?: "MQTT") }
        }
        post { it.onRejected(reason) }
    }

    private fun setLink(l: Link, detail: String) {
        link = l
        linkDetail = detail
        post { it.onLinkChanged(l, detail) }
    }

    private fun post(block: (Listener) -> Unit) {
        main.post { listeners.forEach(block) }
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun friendlyError(e: Throwable?): String {
        val msg = e?.message ?: ""
        return when {
            msg.contains("Unable to connect", true) || msg.contains("UnknownHost", true) ->
                "No se pudo llegar al broker. Revisa Wi-Fi/Internet"
            msg.contains("Not authorized", true) || msg.contains("Bad user name", true) ->
                "El broker rechazó usuario/contraseña"
            msg.contains("SSL", true) || msg.contains("certificate", true) ->
                "Error TLS: certificado del broker no válido"
            msg.isNotBlank() -> msg
            else -> "No se pudo conectar"
        }
    }

    private fun JSONObject.putDistance(d: Float?): JSONObject =
        if (d == null) put("distance", JSONObject.NULL) else put("distance", d.toDouble())

    private fun JSONObject.distanceOrNull(): Float? =
        if (isNull("distance")) null else optDouble("distance", Double.NaN).takeIf { !it.isNaN() }?.toFloat()

    companion object {
        const val TOPIC_CMD = "cmd"
        const val TOPIC_STATE = "state"
        const val TOPIC_EVENT = "event"
        const val TOPIC_TELE = "telemetry"
        private const val RETRY_MS = 5_000L
        private const val WATCHDOG_MS = 2_000L
        private const val OFFLINE_AFTER_MS = 6_000L
        private const val FRESH_MS = 15_000L
    }
}
