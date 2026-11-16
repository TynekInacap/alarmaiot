package cl.alarmaiot.data

import android.content.Context
import cl.alarmaiot.alarm.AlarmState
import cl.alarmaiot.security.KeystoreCipher
import java.security.SecureRandom

/**
 * Configuración de la conexión. Los valores sensibles (código de vinculación y
 * contraseña del broker) se guardan cifrados con [KeystoreCipher].
 */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("alarmaiot_config", Context.MODE_PRIVATE)

    var brokerUri: String
        get() = prefs.getString(KEY_BROKER, DEFAULT_BROKER) ?: DEFAULT_BROKER
        set(value) = prefs.edit().putString(KEY_BROKER, value.trim()).apply()

    /** Identificador de la casa: forma parte del tópico MQTT. Ambos teléfonos deben usar el mismo. */
    var homeId: String
        get() = prefs.getString(KEY_HOME, null) ?: newHomeId().also { homeId = it }
        set(value) = prefs.edit().putString(KEY_HOME, value.trim().lowercase()).apply()

    var pairingCode: String?
        get() = prefs.getString(KEY_CODE, null)?.let(KeystoreCipher::decrypt)
        set(value) = prefs.edit().putString(KEY_CODE, value?.takeIf { it.isNotEmpty() }?.let(KeystoreCipher::encrypt)).apply()

    var mqttUser: String
        get() = prefs.getString(KEY_MQTT_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USER, value.trim()).apply()

    var mqttPassword: String?
        get() = prefs.getString(KEY_MQTT_PASS, null)?.let(KeystoreCipher::decrypt)
        set(value) = prefs.edit().putString(KEY_MQTT_PASS, value?.takeIf { it.isNotEmpty() }?.let(KeystoreCipher::encrypt)).apply()

    /** Último estado de la Central, para no "desarmarse" sola si la app se reinicia. */
    var lastCentralState: AlarmState
        get() = AlarmState.parse(prefs.getString(KEY_STATE, null)) ?: AlarmState.DESARMADA
        set(value) = prefs.edit().putString(KEY_STATE, value.name).apply()

    /** PIN de la alarma: solo lo necesita el teléfono Central para verificar Armar/Desarmar. */
    var alarmPin: String?
        get() = prefs.getString(KEY_PIN, null)?.let(KeystoreCipher::decrypt)
        set(value) = prefs.edit().putString(KEY_PIN, value?.takeIf { it.isNotEmpty() }?.let(KeystoreCipher::encrypt)).apply()

    val isPaired: Boolean get() = (pairingCode?.length ?: 0) >= 6
    val isPinSet: Boolean get() = !alarmPin.isNullOrEmpty()

    fun newHomeId(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return "casa-" + (1..6).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    companion object {
        const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"
        val HOME_ID_REGEX = Regex("^[a-z0-9-]{4,32}$")

        /** Solo se aceptan conexiones cifradas: MQTT sobre TLS (ssl://) o WebSocket seguro (wss://). */
        fun brokerError(uri: String): String? {
            val u = uri.trim()
            return when {
                !(u.startsWith("ssl://") || u.startsWith("wss://")) ->
                    "Solo se permiten conexiones cifradas (ssl:// o wss://)"
                !Regex("^(ssl|wss)://[A-Za-z0-9.-]+:\\d{2,5}(/.*)?$").matches(u) ->
                    "Formato: ssl://host:puerto  (ej. $DEFAULT_BROKER)"
                else -> null
            }
        }

        private const val KEY_BROKER = "broker"
        private const val KEY_HOME = "home_id"
        private const val KEY_CODE = "pairing_code_enc"
        private const val KEY_MQTT_USER = "mqtt_user"
        private const val KEY_MQTT_PASS = "mqtt_pass_enc"
        private const val KEY_STATE = "central_state"
        private const val KEY_PIN = "alarm_pin_enc"
    }
}
