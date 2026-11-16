package cl.alarmaiot.data

import android.content.ContentValues
import android.database.DatabaseUtils
import cl.alarmaiot.security.PasswordHasher
import cl.alarmaiot.security.RecoveryCode

sealed class LoginResult {
    data class Success(val user: User) : LoginResult()
    data class Invalid(val attemptsLeft: Int?) : LoginResult()
    data class Locked(val untilMs: Long) : LoginResult()
}

sealed class RecoveryResult {
    data class Success(val user: User, val newRecoveryCode: String) : RecoveryResult()
    data class Invalid(val attemptsLeft: Int?) : RecoveryResult()
    data class Locked(val untilMs: Long) : RecoveryResult()
    data class Rejected(val reason: String) : RecoveryResult()
    data object NoCode : RecoveryResult()
}

/** Cuenta recién creada: el código de recuperación se muestra una sola vez. */
data class NewAccount(val user: User, val recoveryCode: String)

/**
 * Autenticación local con SQLite.
 * - Contraseñas y códigos de recuperación con PBKDF2 + sal (ver [PasswordHasher]).
 * - Bloqueo temporal tras [MAX_ATTEMPTS] intentos fallidos, compartido entre
 *   el login y la recuperación (anti fuerza bruta).
 * - Mensaje genérico si el usuario no existe (no se revela qué cuentas existen).
 */
class UserRepository(
    private val db: DbHelper,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Row(
        val user: User,
        val hash: ByteArray,
        val salt: ByteArray,
        val iterations: Int,
        val recoveryHash: ByteArray?,
        val recoverySalt: ByteArray?,
    )

    fun hasUsers(): Boolean = DatabaseUtils.queryNumEntries(db.readableDatabase, "users") > 0

    fun create(username: String, password: String, role: Role): Result<NewAccount> {
        val name = username.trim()
        if (!USERNAME_REGEX.matches(name)) {
            return Result.failure(IllegalArgumentException("Usuario: 3 a 20 caracteres (letras, números, punto, guion)"))
        }
        PasswordHasher.policyError(password, name)?.let { return Result.failure(IllegalArgumentException(it)) }

        val code = RecoveryCode.generate()
        val values = passwordValues(password).apply {
            putAll(recoveryValues(code))
            put("username", name)
            put("role", role.name)
            put("created_at", clock())
        }
        val id = db.writableDatabase.insert("users", null, values)
        if (id == -1L) return Result.failure(IllegalArgumentException("Ese nombre de usuario ya existe"))
        return Result.success(NewAccount(User(id, name, role), code))
    }

    fun login(username: String, password: String): LoginResult {
        val row = findRow(username.trim())
        if (row == null) {
            // Se calcula igual un hash para que el tiempo de respuesta no delate si el usuario existe.
            PasswordHasher.hash(password, DUMMY_SALT)
            return LoginResult.Invalid(null)
        }
        val now = clock()
        if (row.user.lockedUntil > now) return LoginResult.Locked(row.user.lockedUntil)

        if (PasswordHasher.verify(password, row.salt, row.hash, row.iterations)) {
            updateLock(row.user.id, attempts = 0, lockedUntil = 0)
            return LoginResult.Success(row.user.copy(failedAttempts = 0, lockedUntil = 0))
        }
        return when (val until = registerFailure(row.user, now)) {
            null -> LoginResult.Invalid(MAX_ATTEMPTS - (row.user.failedAttempts + 1))
            else -> LoginResult.Locked(until)
        }
    }

    /**
     * Recuperación con el código de recuperación: si es correcto se guarda la
     * nueva contraseña y se emite un código NUEVO (el anterior deja de servir).
     */
    fun recover(username: String, code: String, newPassword: String): RecoveryResult {
        val row = findRow(username.trim())
        if (row == null) {
            PasswordHasher.hash(code, DUMMY_SALT)
            return RecoveryResult.Invalid(null)
        }
        val now = clock()
        if (row.user.lockedUntil > now) return RecoveryResult.Locked(row.user.lockedUntil)
        if (row.recoveryHash == null || row.recoverySalt == null) return RecoveryResult.NoCode
        PasswordHasher.policyError(newPassword, row.user.username)?.let { return RecoveryResult.Rejected(it) }

        val ok = RecoveryCode.isWellFormed(code) &&
            PasswordHasher.verify(RecoveryCode.normalize(code), row.recoverySalt, row.recoveryHash, PasswordHasher.DEFAULT_ITERATIONS)
        if (!ok) {
            return when (val until = registerFailure(row.user, now)) {
                null -> RecoveryResult.Invalid(MAX_ATTEMPTS - (row.user.failedAttempts + 1))
                else -> RecoveryResult.Locked(until)
            }
        }
        val newCode = RecoveryCode.generate()
        val values = passwordValues(newPassword).apply {
            putAll(recoveryValues(newCode))
            put("failed_attempts", 0)
            put("locked_until", 0)
        }
        db.writableDatabase.update("users", values, "id = ?", arrayOf(row.user.id.toString()))
        return RecoveryResult.Success(row.user.copy(failedAttempts = 0, lockedUntil = 0), newCode)
    }

    /** El Administrador fija una contraseña nueva a otro usuario. Devuelve el nuevo código de recuperación. */
    fun adminResetPassword(target: User, newPassword: String): Result<String> {
        PasswordHasher.policyError(newPassword, target.username)?.let { return Result.failure(IllegalArgumentException(it)) }
        val code = RecoveryCode.generate()
        val values = passwordValues(newPassword).apply {
            putAll(recoveryValues(code))
            put("failed_attempts", 0)
            put("locked_until", 0)
        }
        db.writableDatabase.update("users", values, "id = ?", arrayOf(target.id.toString()))
        return Result.success(code)
    }

    /** Emite un código de recuperación nuevo (el anterior deja de servir). */
    fun regenerateRecoveryCode(id: Long): String {
        val code = RecoveryCode.generate()
        db.writableDatabase.update("users", recoveryValues(code), "id = ?", arrayOf(id.toString()))
        return code
    }

    fun list(): List<User> {
        val out = mutableListOf<User>()
        db.readableDatabase.rawQuery(
            "SELECT id, username, role, failed_attempts, locked_until FROM users ORDER BY username COLLATE NOCASE",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += User(c.getLong(0), c.getString(1), Role.parse(c.getString(2)), c.getInt(3), c.getLong(4))
            }
        }
        return out
    }

    fun countAdmins(): Long =
        DatabaseUtils.queryNumEntries(db.readableDatabase, "users", "role = ?", arrayOf(Role.ADMIN.name))

    fun setRole(id: Long, role: Role) {
        db.writableDatabase.update("users", ContentValues().apply { put("role", role.name) }, "id = ?", arrayOf(id.toString()))
    }

    fun unlock(id: Long) = updateLock(id, attempts = 0, lockedUntil = 0)

    fun delete(id: Long) {
        db.writableDatabase.delete("users", "id = ?", arrayOf(id.toString()))
    }

    /** Suma un intento fallido. Devuelve la hora de desbloqueo si la cuenta quedó bloqueada. */
    private fun registerFailure(user: User, now: Long): Long? {
        val attempts = user.failedAttempts + 1
        if (attempts >= MAX_ATTEMPTS) {
            val until = now + LOCK_MS
            updateLock(user.id, attempts = 0, lockedUntil = until)
            return until
        }
        updateLock(user.id, attempts = attempts, lockedUntil = 0)
        return null
    }

    private fun passwordValues(password: String): ContentValues {
        val salt = PasswordHasher.newSalt()
        return ContentValues().apply {
            put("pass_hash", PasswordHasher.hash(password, salt))
            put("salt", salt)
            put("iterations", PasswordHasher.DEFAULT_ITERATIONS)
        }
    }

    private fun recoveryValues(code: String): ContentValues {
        val salt = PasswordHasher.newSalt()
        return ContentValues().apply {
            put("recovery_hash", PasswordHasher.hash(RecoveryCode.normalize(code), salt))
            put("recovery_salt", salt)
        }
    }

    private fun updateLock(id: Long, attempts: Int, lockedUntil: Long) {
        val values = ContentValues().apply {
            put("failed_attempts", attempts)
            put("locked_until", lockedUntil)
        }
        db.writableDatabase.update("users", values, "id = ?", arrayOf(id.toString()))
    }

    private fun findRow(username: String): Row? =
        db.readableDatabase.rawQuery(
            "SELECT id, username, role, failed_attempts, locked_until, pass_hash, salt, iterations, recovery_hash, recovery_salt " +
                "FROM users WHERE username = ? COLLATE NOCASE",
            arrayOf(username),
        ).use { c ->
            if (!c.moveToFirst()) return null
            Row(
                user = User(c.getLong(0), c.getString(1), Role.parse(c.getString(2)), c.getInt(3), c.getLong(4)),
                hash = c.getBlob(5),
                salt = c.getBlob(6),
                iterations = c.getInt(7),
                recoveryHash = if (c.isNull(8)) null else c.getBlob(8),
                recoverySalt = if (c.isNull(9)) null else c.getBlob(9),
            )
        }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCK_MS = 5 * 60_000L
        private val USERNAME_REGEX = Regex("^[A-Za-z0-9._-]{3,20}$")
        private val DUMMY_SALT = ByteArray(16)
    }
}
