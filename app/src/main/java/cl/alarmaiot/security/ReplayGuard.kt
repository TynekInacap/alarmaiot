package cl.alarmaiot.security

import kotlin.math.abs

/**
 * Protección contra repetición: un comando capturado ("Desarmar") no se puede
 * volver a enviar más tarde. Cada mensaje lleva marca de tiempo y un nonce único;
 * se rechaza si está fuera de la ventana o si el nonce ya se usó.
 */
class ReplayGuard(
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun accept(nonce: String, timestampMs: Long): Boolean {
        val now = clock()
        if (nonce.isBlank() || abs(now - timestampMs) > windowMs) return false
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > windowMs) it.remove() else break
        }
        if (seen.containsKey(nonce)) return false
        seen[nonce] = now
        return true
    }
}

/** Limita la cantidad de eventos en una ventana de tiempo (anti-flooding). */
class RateLimiter(
    private val maxEvents: Int,
    private val windowMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= maxEvents) return false
        times.addLast(now)
        return true
    }
}
