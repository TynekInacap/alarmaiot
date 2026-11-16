package cl.alarmaiot.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cl.alarmaiot.AlarmaApp
import cl.alarmaiot.BuildConfig
import cl.alarmaiot.R
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.data.LoginResult
import cl.alarmaiot.data.RecoveryResult
import cl.alarmaiot.data.Role
import cl.alarmaiot.data.User
import cl.alarmaiot.databinding.ActivityLoginBinding
import cl.alarmaiot.databinding.DialogRecoverBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Pantalla de autenticación (usuario + contraseña contra SQLite).
 * La primera vez que se abre la app pide crear la cuenta de administrador.
 * Incluye la recuperación de contraseña con el código de recuperación.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private val app get() = application as AlarmaApp
    private var setupMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupScreen(b.root, null, null, back = false)

        intent.getStringExtra(EXTRA_MESSAGE)?.let(::showError)

        b.loginButton.setOnClickListener { submit() }
        b.forgotButton.setOnClickListener { showRecoverDialog() }
        b.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && !setupMode) { submit(); true } else false
        }

        setBusy(true)
        app.io.execute {
            val hasUsers = app.users.hasUsers()
            runOnUiThread {
                setBusy(false)
                setSetupMode(!hasUsers)
            }
        }
    }

    private fun setSetupMode(enabled: Boolean) {
        setupMode = enabled
        b.setupCard.visibility = if (enabled) View.VISIBLE else View.GONE
        b.confirmLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        b.forgotButton.visibility = if (enabled) View.GONE else View.VISIBLE
        b.passLayout.helperText = if (enabled) getString(R.string.password_policy) else null
        b.loginButton.setText(if (enabled) R.string.create_admin else R.string.login)
    }

    private fun submit() {
        val username = b.username.text?.toString()?.trim().orEmpty()
        val password = b.password.text?.toString().orEmpty()
        b.userLayout.error = null
        b.passLayout.error = null
        b.confirmLayout.error = null
        b.errorText.visibility = View.GONE

        if (username.isEmpty()) { b.userLayout.error = "Ingresa tu usuario"; return }
        if (password.isEmpty()) { b.passLayout.error = "Ingresa tu contraseña"; return }

        if (setupMode) {
            if (password != b.confirm.text?.toString()) { b.confirmLayout.error = "Las contraseñas no coinciden"; return }
            setBusy(true)
            app.io.execute {
                val result = app.users.create(username, password, Role.ADMIN)
                result.onSuccess {
                    app.events.log(EventCategory.SEGURIDAD, "Cuenta de administrador creada: ${it.user.username}", "APP", username = it.user.username)
                }
                runOnUiThread {
                    setBusy(false)
                    result.onSuccess { acc -> showRecoveryCode(acc.recoveryCode, acc.user.username) { enter(acc.user) } }
                        .onFailure { showError(it.message ?: "No se pudo crear la cuenta") }
                }
            }
            return
        }

        setBusy(true)
        app.io.execute {
            val result = app.users.login(username, password)
            when (result) {
                is LoginResult.Success -> app.events.log(EventCategory.SEGURIDAD, "Inicio de sesión: ${result.user.username}", "APP", username = result.user.username)
                is LoginResult.Invalid -> app.events.log(EventCategory.SEGURIDAD, "Intento de acceso fallido para '$username'", "APP")
                is LoginResult.Locked -> app.events.log(EventCategory.SEGURIDAD, "Cuenta '$username' bloqueada temporalmente", "APP")
            }
            runOnUiThread {
                setBusy(false)
                when (result) {
                    is LoginResult.Success -> enter(result.user)
                    is LoginResult.Invalid -> {
                        b.password.text = null
                        showError(
                            "Usuario o contraseña incorrectos" +
                                (result.attemptsLeft?.let { ". Te quedan $it intentos antes del bloqueo." } ?: "")
                        )
                    }
                    is LoginResult.Locked -> showError(lockedMessage(result.untilMs))
                }
            }
        }
    }

    /** Recuperar la contraseña con el código de recuperación (sin servidor ni correo). */
    private fun showRecoverDialog() {
        val d = DialogRecoverBinding.inflate(LayoutInflater.from(this))
        d.username.setText(b.username.text)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recover_title)
            .setView(d.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Cambiar contraseña", null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val name = d.username.text?.toString()?.trim().orEmpty()
                val code = d.code.text?.toString().orEmpty()
                val pw = d.password.text?.toString().orEmpty()
                listOf(d.userLayout, d.codeLayout, d.passLayout, d.confirmLayout).forEach { it.error = null }
                when {
                    name.isEmpty() -> { d.userLayout.error = "Ingresa tu usuario"; return@setOnClickListener }
                    code.isBlank() -> { d.codeLayout.error = "Ingresa el código"; return@setOnClickListener }
                    pw != d.confirm.text?.toString() -> { d.confirmLayout.error = "Las contraseñas no coinciden"; return@setOnClickListener }
                }
                ok.isEnabled = false
                app.io.execute {
                    val result = app.users.recover(name, code, pw)
                    when (result) {
                        is RecoveryResult.Success -> app.events.log(EventCategory.SEGURIDAD, "Contraseña recuperada con código: ${result.user.username}", "APP", username = result.user.username)
                        is RecoveryResult.Invalid, is RecoveryResult.Locked ->
                            app.events.log(EventCategory.SEGURIDAD, "Intento de recuperación fallido para '$name'", "APP")
                        else -> Unit
                    }
                    runOnUiThread {
                        ok.isEnabled = true
                        when (result) {
                            is RecoveryResult.Success -> {
                                dialog.dismiss()
                                b.username.setText(result.user.username)
                                b.password.text = null
                                showRecoveryCode(result.newRecoveryCode, result.user.username) {
                                    showError("Contraseña cambiada. Ingresa con la nueva contraseña.")
                                }
                            }
                            is RecoveryResult.Invalid -> d.codeLayout.error = "Usuario o código incorrectos" +
                                (result.attemptsLeft?.let { " ($it intentos antes del bloqueo)" } ?: "")
                            is RecoveryResult.Locked -> { dialog.dismiss(); showError(lockedMessage(result.untilMs)) }
                            is RecoveryResult.Rejected -> d.passLayout.error = result.reason
                            RecoveryResult.NoCode -> d.codeLayout.error =
                                "Esta cuenta no tiene código de recuperación. Pide al administrador que restablezca tu contraseña."
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun lockedMessage(untilMs: Long): String {
        val min = ((untilMs - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
        return "Cuenta bloqueada por demasiados intentos. Intenta en $min min."
    }

    private fun enter(user: User) {
        app.session.start(user)
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        b.errorText.text = message
        b.errorText.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        b.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        b.loginButton.isEnabled = !busy
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
    }
}
