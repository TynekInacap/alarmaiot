package cl.alarmaiot.ui

import android.os.Bundle
import android.view.View
import cl.alarmaiot.data.ConfigStore
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.databinding.ActivitySettingsBinding
import cl.alarmaiot.security.CommandAuth
import cl.alarmaiot.security.PasswordHasher
import com.google.android.material.snackbar.Snackbar

/**
 * Vinculación de los teléfonos y datos del broker. Solo el Administrador
 * puede modificarla. El código de vinculación se guarda cifrado y nunca se
 * muestra de vuelta en pantalla.
 */
class SettingsActivity : SecureActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onSecureCreate(savedInstanceState: Bundle?) {
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupScreen(b.root, b.toolbar, "Configuración")

        val cfg = app.config
        b.homeId.setText(cfg.homeId)
        b.broker.setText(cfg.brokerUri)
        b.brokerUser.setText(cfg.mqttUser)
        b.codeLayout.helperText = if (cfg.isPaired) "Guardado. Déjalo vacío para mantenerlo." else CODE_HINT
        b.pinLayout.helperText = if (cfg.isPinSet) "Guardado. Déjalo vacío para mantenerlo." else PIN_HINT
        b.brokerPassLayout.helperText = if (cfg.mqttPassword != null) "Guardada. Déjala vacía para mantenerla." else null

        val editable = user.role.canManage
        if (!editable) {
            b.readonlyNote.visibility = View.VISIBLE
            listOf(b.homeId, b.pairingCode, b.alarmPin, b.broker, b.brokerUser, b.brokerPass).forEach { it.isEnabled = false }
            b.generateButton.isEnabled = false
            b.saveButton.visibility = View.GONE
        }

        b.generateButton.setOnClickListener { b.homeId.setText(cfg.newHomeId()) }
        b.saveButton.setOnClickListener { save() }
    }

    private fun save() {
        val cfg = app.config
        val home = b.homeId.text?.toString()?.trim()?.lowercase().orEmpty()
        val code = b.pairingCode.text?.toString().orEmpty()
        val pin = b.alarmPin.text?.toString().orEmpty()
        val broker = b.broker.text?.toString()?.trim().orEmpty()
        val brokerUser = b.brokerUser.text?.toString()?.trim().orEmpty()
        val brokerPass = b.brokerPass.text?.toString().orEmpty()

        b.homeLayout.error = null
        b.codeLayout.error = null
        b.pinLayout.error = null
        b.brokerLayout.error = null

        var ok = true
        if (!ConfigStore.HOME_ID_REGEX.matches(home)) {
            b.homeLayout.error = "4 a 32 caracteres: letras minúsculas, números y guion"
            ok = false
        }
        if (code.isNotEmpty()) {
            PasswordHasher.policyError(code)?.let { b.codeLayout.error = it.replace("contraseña", "código"); ok = false }
        } else if (!cfg.isPaired) {
            b.codeLayout.error = "Ingresa un código de vinculación"
            ok = false
        }
        if (pin.isNotEmpty()) {
            CommandAuth.pinError(pin)?.let { b.pinLayout.error = it; ok = false }
        }
        ConfigStore.brokerError(broker)?.let { b.brokerLayout.error = it; ok = false }
        if (!ok) return

        cfg.homeId = home
        cfg.brokerUri = broker
        cfg.mqttUser = brokerUser
        if (code.isNotEmpty()) cfg.pairingCode = code
        if (pin.isNotEmpty()) cfg.alarmPin = pin
        if (brokerPass.isNotEmpty()) cfg.mqttPassword = brokerPass
        if (brokerUser.isEmpty()) cfg.mqttPassword = null

        b.pairingCode.text = null
        b.alarmPin.text = null
        b.brokerPass.text = null
        b.codeLayout.helperText = "Guardado. Déjalo vacío para mantenerlo."
        if (cfg.isPinSet) b.pinLayout.helperText = "Guardado. Déjalo vacío para mantenerlo."

        // Se fuerza una reconexión con la nueva configuración.
        app.mqtt.disconnect()
        val name = user.username
        app.io.execute {
            app.events.log(
                EventCategory.SEGURIDAD,
                "Configuración modificada (casa $home, broker $broker" +
                    (if (code.isNotEmpty()) ", nuevo código" else "") +
                    (if (pin.isNotEmpty()) ", nuevo PIN" else "") + ")",
                "APP", username = name,
            )
        }
        Snackbar.make(b.root, "Configuración guardada", Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        private val CODE_HINT = PasswordHasher.POLICY_HINT + ". Debe ser igual en ambos teléfonos."
        private val PIN_HINT = CommandAuth.PIN_HINT + ". Solo es obligatorio en el teléfono Central."
    }
}
