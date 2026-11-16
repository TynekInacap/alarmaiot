package cl.alarmaiot.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import cl.alarmaiot.databinding.ActivityHomeBinding
import cl.alarmaiot.mqtt.MqttManager
import com.google.android.material.snackbar.Snackbar

/** Menú principal: elegir modo Central o Panel, historial, usuarios y configuración. */
class HomeActivity : SecureActivity() {

    private lateinit var b: ActivityHomeBinding

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Snackbar.make(b.root, "Sin permiso de notificaciones no recibirás avisos de intrusión", Snackbar.LENGTH_LONG).show()
            }
        }

    override fun onSecureCreate(savedInstanceState: Bundle?) {
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupScreen(b.root, b.toolbar, "Alarma IoT", back = false)

        b.greeting.text = "Hola, ${user.username}"
        b.roleChip.text = "Rol: ${user.role.label}"
        b.usersButton.visibility = if (user.role.canManage) View.VISIBLE else View.GONE

        b.centralCard.setOnClickListener { openMode(CentralActivity::class.java) }
        b.panelCard.setOnClickListener { openMode(PanelActivity::class.java) }
        b.historyButton.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        b.usersButton.setOnClickListener { startActivity(Intent(this, UsersActivity::class.java)) }
        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.pairingButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.logoutButton.setOnClickListener { logout() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.canNotify(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        b.pairingCard.visibility = if (app.config.isPaired) View.GONE else View.VISIBLE
        b.pairingButton.visibility = if (user.role.canManage) View.VISIBLE else View.GONE
        val panelActive = app.mqtt.mode == MqttManager.Mode.PANEL
        b.panelStatus.visibility = if (panelActive) View.VISIBLE else View.GONE
        b.panelStatus.text = "Panel activo · ${app.mqtt.link.label}"
    }

    private fun openMode(target: Class<*>) {
        if (!app.config.isPaired) {
            Snackbar.make(b.root, "Primero configura el ID de casa y el código de vinculación", Snackbar.LENGTH_LONG).show()
            return
        }
        if (target == CentralActivity::class.java && !app.config.isPinSet) {
            Snackbar.make(b.root, "Define el PIN de la alarma en Configuración: la Central lo usa para validar Armar y Desarmar", Snackbar.LENGTH_LONG)
                .setAction("Configurar") { startActivity(Intent(this, SettingsActivity::class.java)) }
                .show()
            return
        }
        startActivity(Intent(this, target))
    }
}
