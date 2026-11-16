package cl.alarmaiot.alarm

/** Los tres estados del prototipo Arduino de la Unidad 1. */
enum class AlarmState(val label: String) {
    DESARMADA("Desarmada"),
    ARMADA("Armada"),
    ALERTA("¡Intrusión!");

    companion object {
        fun parse(value: String?): AlarmState? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Máquina de estados de la alarma. Es una traducción directa del firmware
 * del Arduino UNO (Unidad 1): mismos comandos (A, D, E), mismos mensajes
 * de respuesta y la misma detección por "flanco" (solo cuenta el momento en
 * que el objeto se acerca a menos de 20 cm, no que se mantenga cerca).
 *
 * No depende de Android, por lo que se prueba con tests unitarios.
 */
class AlarmStateMachine(private val thresholdCm: Float = DEFAULT_THRESHOLD_CM) {

    data class Outcome(
        val messages: List<String>,
        val stateChanged: Boolean,
        val accepted: Boolean = true,
        val intrusion: Boolean = false,
    )

    var state: AlarmState = AlarmState.DESARMADA
        private set

    private var wasNear = false

    /** Restaura el último estado guardado (una alarma armada no debe desarmarse al reiniciar la app). */
    fun restore(saved: AlarmState) {
        state = saved
    }

    fun handleCommand(command: Char): Outcome = when (command.uppercaseChar()) {
        'A' -> arm()
        'D' -> disarm()
        'E' -> Outcome(listOf(stateMessage()), stateChanged = false)
        else -> Outcome(listOf("ERR COMANDO"), stateChanged = false, accepted = false)
    }

    /** Nueva lectura del sensor. `null` = sin eco (no hay objeto detectable). */
    fun onDistance(distanceCm: Float?): Outcome {
        val near = distanceCm != null && distanceCm >= 0f && distanceCm < thresholdCm
        val risingEdge = near && !wasNear
        wasNear = near
        if (state == AlarmState.ARMADA && risingEdge) {
            state = AlarmState.ALERTA
            return Outcome(
                listOf("EVENTO PROXIMIDAD", "ALERTA INTRUSION", stateMessage()),
                stateChanged = true,
                intrusion = true,
            )
        }
        return Outcome(emptyList(), stateChanged = false)
    }

    fun stateMessage(): String = "ESTADO ${state.name}"

    private fun arm(): Outcome {
        if (state != AlarmState.DESARMADA) {
            return Outcome(listOf("ERR YA_ARMADA"), stateChanged = false, accepted = false)
        }
        state = AlarmState.ARMADA
        return Outcome(listOf("OK ARMAR", stateMessage()), stateChanged = true)
    }

    private fun disarm(): Outcome {
        if (state == AlarmState.DESARMADA) {
            return Outcome(listOf("ERR YA_DESARMADA"), stateChanged = false, accepted = false)
        }
        state = AlarmState.DESARMADA
        return Outcome(listOf("OK DESARMAR", stateMessage()), stateChanged = true)
    }

    companion object {
        const val DEFAULT_THRESHOLD_CM = 20f
    }
}
