package cl.alarmaiot.data

/**
 * Sesión del usuario. Se mantiene solo en memoria (no se guarda en disco) y
 * expira tras [TIMEOUT_MS] sin actividad: si alguien toma el teléfono
 * desbloqueado, no queda una sesión abierta indefinidamente.
 */
class SessionManager(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile
    var user: User? = null
        private set

    @Volatile
    private var lastActivity = 0L

    fun start(u: User) {
        user = u
        lastActivity = clock()
    }

    fun touch() {
        if (user != null) lastActivity = clock()
    }

    fun isExpired(): Boolean = user == null || clock() - lastActivity > TIMEOUT_MS

    fun end() {
        user = null
        lastActivity = 0
    }

    companion object {
        const val TIMEOUT_MS = 5 * 60_000L
    }
}
