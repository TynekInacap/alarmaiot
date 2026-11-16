package cl.alarmaiot.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cl.alarmaiot.AlarmaApp
import cl.alarmaiot.BuildConfig
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.data.LoginResult
import cl.alarmaiot.data.User
import cl.alarmaiot.databinding.DialogPasswordBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Base de todas las pantallas que requieren sesión iniciada (control de acceso).
 * - Sin sesión -> vuelve al login.
 * - Inactividad -> cierra sesión, o bien (Central/Panel) bloquea la pantalla
 *   con contraseña mientras el monitoreo sigue funcionando.
 * - En la versión release se bloquean capturas de pantalla (FLAG_SECURE).
 */
abstract class SecureActivity : AppCompatActivity() {

    protected val app: AlarmaApp get() = application as AlarmaApp
    protected val user: User get() = app.session.user ?: User(-1, "?", cl.alarmaiot.data.Role.OBSERVADOR)

    /** true: al expirar la sesión se bloquea la pantalla en lugar de cerrar sesión. */
    protected open val lockOnIdle: Boolean = false
    protected open val requiresAdmin: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var lockDialog: AlertDialog? = null
    private val idleCheck = object : Runnable {
        override fun run() {
            checkSession()
            handler.postDelayed(this, 10_000)
        }
    }

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        val u = app.session.user
        if (u == null || (requiresAdmin && !u.role.canManage)) {
            goToLogin(null)
            return
        }
        onSecureCreate(savedInstanceState)
    }

    protected abstract fun onSecureCreate(savedInstanceState: Bundle?)

    override fun onResume() {
        super.onResume()
        checkSession()
        handler.postDelayed(idleCheck, 10_000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(idleCheck)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (lockDialog?.isShowing != true) app.session.touch()
    }

    private fun checkSession() {
        if (app.session.user == null) {
            goToLogin(null)
        } else if (app.session.isExpired()) {
            if (lockOnIdle) showLockDialog() else goToLogin("Sesión cerrada por inactividad")
        }
    }

    protected fun logout() {
        val name = user.username
        app.io.execute { app.events.log(EventCategory.SEGURIDAD, "Cierre de sesión: $name", "APP", username = name) }
        goToLogin(null)
    }

    protected fun goToLogin(message: String?) {
        if (message != null && app.session.user != null) {
            val name = user.username
            app.io.execute { app.events.log(EventCategory.SEGURIDAD, "$message ($name)", "APP", username = name) }
        }
        app.logout()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_MESSAGE, message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun showLockDialog() {
        if (lockDialog?.isShowing == true || isFinishing) return
        val b = DialogPasswordBinding.inflate(LayoutInflater.from(this))
        val current = user
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Pantalla bloqueada")
            .setMessage("Por inactividad. El monitoreo sigue activo. Ingresa la contraseña de ${current.username} para continuar.")
            .setView(b.root)
            .setCancelable(false)
            .setPositiveButton("Desbloquear", null)
            .setNegativeButton("Cerrar sesión") { _, _ -> logout() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = b.password.text?.toString().orEmpty()
                b.passLayout.error = null
                app.io.execute {
                    val result = app.users.login(current.username, pw)
                    runOnUiThread {
                        when (result) {
                            is LoginResult.Success -> {
                                app.session.start(result.user)
                                dialog.dismiss()
                            }
                            is LoginResult.Invalid -> b.passLayout.error =
                                "Contraseña incorrecta" + (result.attemptsLeft?.let { " ($it intentos restantes)" } ?: "")
                            is LoginResult.Locked -> {
                                dialog.dismiss()
                                goToLogin("Cuenta bloqueada por intentos fallidos")
                            }
                        }
                    }
                }
            }
        }
        lockDialog = dialog
        dialog.show()
    }

    override fun onDestroy() {
        lockDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
