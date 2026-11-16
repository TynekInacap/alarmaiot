package cl.alarmaiot.data

/**
 * Roles para el control de acceso (principio de mínimo privilegio):
 * - ADMIN: todo, incluida la gestión de usuarios y la configuración.
 * - OPERADOR: arma, desarma y consulta.
 * - OBSERVADOR: solo monitorea (no puede armar ni desarmar).
 */
enum class Role(val label: String) {
    ADMIN("Administrador"),
    OPERADOR("Operador"),
    OBSERVADOR("Observador");

    val canControlAlarm: Boolean get() = this != OBSERVADOR
    val canManage: Boolean get() = this == ADMIN

    companion object {
        fun parse(value: String?): Role = entries.firstOrNull { it.name == value } ?: OBSERVADOR
    }
}

data class User(
    val id: Long,
    val username: String,
    val role: Role,
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0,
) {
    fun isLocked(now: Long = System.currentTimeMillis()): Boolean = lockedUntil > now
}

enum class EventCategory(val label: String) {
    ALERTA("Alerta"),
    COMANDO("Comando"),
    ESTADO("Estado"),
    SEGURIDAD("Seguridad"),
    CONEXION("Conexión");

    companion object {
        fun parse(value: String?): EventCategory = entries.firstOrNull { it.name == value } ?: ESTADO
    }
}

data class EventRecord(
    val id: Long,
    val ts: Long,
    val category: EventCategory,
    val detail: String,
    val state: String?,
    val distance: Float?,
    val source: String,
    val username: String?,
)

data class Reading(val ts: Long, val distance: Float)

data class ReadingStats(val count: Int, val min: Float?, val avg: Float?, val nearCount: Int)
