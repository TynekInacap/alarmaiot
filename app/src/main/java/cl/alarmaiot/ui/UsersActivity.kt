package cl.alarmaiot.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.data.Role
import cl.alarmaiot.data.User
import cl.alarmaiot.databinding.ActivityUsersBinding
import cl.alarmaiot.databinding.DialogPasswordBinding
import cl.alarmaiot.databinding.DialogUserBinding
import cl.alarmaiot.databinding.ItemUserBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** Gestión de usuarios (solo Administrador): crear, cambiar rol, desbloquear y eliminar. */
class UsersActivity : SecureActivity() {

    override val requiresAdmin = true

    private lateinit var b: ActivityUsersBinding
    private val adapter = UserAdapter { showOptions(it) }

    override fun onSecureCreate(savedInstanceState: Bundle?) {
        b = ActivityUsersBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupScreen(b.root, b.toolbar, "Usuarios y roles")
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.addButton.setOnClickListener { showCreateDialog() }
        load()
    }

    private fun load() {
        app.io.execute {
            val users = app.users.list()
            runOnUiThread { if (!isDestroyed) adapter.submit(users) }
        }
    }

    private fun audit(text: String) {
        val admin = user.username
        app.io.execute { app.events.log(EventCategory.SEGURIDAD, text, "APP", username = admin) }
    }

    private fun showCreateDialog() {
        val d = DialogUserBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Nuevo usuario")
            .setView(d.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Crear", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = d.username.text?.toString().orEmpty()
                val pw = d.password.text?.toString().orEmpty()
                val role = when (d.roleGroup.checkedRadioButtonId) {
                    d.roleAdmin.id -> Role.ADMIN
                    d.roleObservador.id -> Role.OBSERVADOR
                    else -> Role.OPERADOR
                }
                d.userLayout.error = null
                app.io.execute {
                    val result = app.users.create(name, pw, role)
                    runOnUiThread {
                        result.onSuccess { acc ->
                            audit("Usuario creado: ${acc.user.username} (${role.label})")
                            dialog.dismiss()
                            load()
                            showRecoveryCode(acc.recoveryCode, acc.user.username) {}
                        }.onFailure { d.userLayout.error = it.message }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showOptions(target: User) {
        val isSelf = target.id == user.id
        val options = mutableListOf("Cambiar rol", "Restablecer contraseña", "Nuevo código de recuperación")
        if (target.isLocked() || target.failedAttempts > 0) options += "Desbloquear"
        if (!isSelf) options += "Eliminar"
        MaterialAlertDialogBuilder(this)
            .setTitle(target.username)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Cambiar rol" -> changeRole(target, isSelf)
                    "Restablecer contraseña" -> resetPassword(target)
                    "Nuevo código de recuperación" -> app.io.execute {
                        val code = app.users.regenerateRecoveryCode(target.id)
                        runOnUiThread {
                            audit("Nuevo código de recuperación para ${target.username}")
                            showRecoveryCode(code, target.username) {}
                        }
                    }
                    "Desbloquear" -> app.io.execute {
                        app.users.unlock(target.id)
                        runOnUiThread { audit("Usuario desbloqueado: ${target.username}"); load() }
                    }
                    "Eliminar" -> confirmDelete(target)
                }
            }
            .show()
    }

    /** El administrador fija una contraseña nueva (debe cumplir la política) y entrega el nuevo código. */
    private fun resetPassword(target: User) {
        val d = DialogPasswordBinding.inflate(LayoutInflater.from(this))
        d.passLayout.hint = getString(cl.alarmaiot.R.string.new_password)
        d.passLayout.helperText = getString(cl.alarmaiot.R.string.password_policy)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Restablecer contraseña de ${target.username}")
            .setView(d.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Restablecer", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = d.password.text?.toString().orEmpty()
                d.passLayout.error = null
                app.io.execute {
                    val result = app.users.adminResetPassword(target, pw)
                    runOnUiThread {
                        result.onSuccess { code ->
                            audit("Contraseña restablecida por el administrador: ${target.username}")
                            dialog.dismiss()
                            load()
                            showRecoveryCode(code, target.username) {}
                        }.onFailure { d.passLayout.error = it.message }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun changeRole(target: User, isSelf: Boolean) {
        val roles = Role.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("Rol de ${target.username}")
            .setSingleChoiceItems(roles.map { it.label }.toTypedArray(), roles.indexOf(target.role)) { dlg, which ->
                val newRole = roles[which]
                dlg.dismiss()
                if (newRole == target.role) return@setSingleChoiceItems
                app.io.execute {
                    val lastAdmin = target.role == Role.ADMIN && app.users.countAdmins() <= 1
                    if (!lastAdmin) app.users.setRole(target.id, newRole)
                    runOnUiThread {
                        if (lastAdmin) {
                            Snackbar.make(b.root, "Debe existir al menos un administrador", Snackbar.LENGTH_LONG).show()
                        } else {
                            audit("Rol de ${target.username}: ${target.role.label} → ${newRole.label}")
                            if (isSelf) goToLogin("Tu rol cambió, inicia sesión nuevamente") else load()
                        }
                    }
                }
            }
            .show()
    }

    private fun confirmDelete(target: User) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar ${target.username}")
            .setMessage("El usuario ya no podrá ingresar a la aplicación.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                app.io.execute {
                    val lastAdmin = target.role == Role.ADMIN && app.users.countAdmins() <= 1
                    if (!lastAdmin) app.users.delete(target.id)
                    runOnUiThread {
                        if (lastAdmin) {
                            Snackbar.make(b.root, "No puedes eliminar al último administrador", Snackbar.LENGTH_LONG).show()
                        } else {
                            audit("Usuario eliminado: ${target.username}")
                            load()
                        }
                    }
                }
            }
            .show()
    }

    private class UserAdapter(private val onClick: (User) -> Unit) : RecyclerView.Adapter<UserAdapter.Holder>() {
        class Holder(val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root)

        private var items: List<User> = emptyList()

        fun submit(list: List<User>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val u = items[position]
            holder.b.name.text = u.username
            holder.b.role.text = buildString {
                append(u.role.label)
                if (u.isLocked()) append(" · bloqueado")
                else if (u.failedAttempts > 0) append(" · ${u.failedAttempts} intentos fallidos")
            }
            holder.b.lockIcon.visibility = if (u.isLocked()) View.VISIBLE else View.GONE
            holder.b.root.setOnClickListener { onClick(u) }
        }
    }
}
