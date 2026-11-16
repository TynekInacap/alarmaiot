package cl.alarmaiot

import cl.alarmaiot.alarm.AlarmState
import cl.alarmaiot.alarm.AlarmStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifica que la máquina de estados se comporte igual que el firmware del Arduino. */
class AlarmStateMachineTest {

    @Test
    fun arranca_desarmada() {
        assertEquals(AlarmState.DESARMADA, AlarmStateMachine().state)
    }

    @Test
    fun armar_y_desarmar_responden_como_el_firmware() {
        val m = AlarmStateMachine()
        assertEquals(listOf("OK ARMAR", "ESTADO ARMADA"), m.handleCommand('A').messages)
        assertEquals(listOf("ERR YA_ARMADA"), m.handleCommand('a').messages)
        assertEquals(listOf("OK DESARMAR", "ESTADO DESARMADA"), m.handleCommand('D').messages)
        assertEquals(listOf("ERR YA_DESARMADA"), m.handleCommand('D').messages)
        assertEquals(listOf("ESTADO DESARMADA"), m.handleCommand('E').messages)
        assertEquals(listOf("ERR COMANDO"), m.handleCommand('X').messages)
    }

    @Test
    fun objeto_cerca_estando_desarmada_no_dispara() {
        val m = AlarmStateMachine()
        assertFalse(m.onDistance(5f).intrusion)
        assertEquals(AlarmState.DESARMADA, m.state)
    }

    @Test
    fun objeto_que_se_acerca_estando_armada_dispara_alerta() {
        val m = AlarmStateMachine()
        m.handleCommand('A')
        m.onDistance(80f)
        val out = m.onDistance(10f)
        assertTrue(out.intrusion)
        assertEquals(AlarmState.ALERTA, m.state)
        assertEquals(listOf("EVENTO PROXIMIDAD", "ALERTA INTRUSION", "ESTADO ALERTA"), out.messages)
    }

    @Test
    fun solo_cuenta_el_flanco_no_un_objeto_que_ya_estaba_cerca() {
        val m = AlarmStateMachine()
        m.onDistance(10f)          // objeto ya cerca antes de armar
        m.handleCommand('A')
        assertFalse(m.onDistance(10f).intrusion) // sigue cerca: no es un acercamiento nuevo
        m.onDistance(60f)
        assertTrue(m.onDistance(15f).intrusion)  // se alejó y volvió a acercarse
    }

    @Test
    fun sin_eco_no_genera_alerta() {
        val m = AlarmStateMachine()
        m.handleCommand('A')
        assertFalse(m.onDistance(null).intrusion)
        assertEquals(AlarmState.ARMADA, m.state)
    }

    @Test
    fun umbral_exacto_de_20_cm_no_dispara() {
        val m = AlarmStateMachine()
        m.handleCommand('A')
        assertFalse(m.onDistance(20f).intrusion)
        assertTrue(m.onDistance(19.9f).intrusion)
    }

    @Test
    fun desde_alerta_solo_se_sale_desarmando() {
        val m = AlarmStateMachine()
        m.handleCommand('A')
        m.onDistance(5f)
        assertEquals(listOf("ERR YA_ARMADA"), m.handleCommand('A').messages)
        m.onDistance(80f)
        assertEquals(AlarmState.ALERTA, m.state)
        m.handleCommand('D')
        assertEquals(AlarmState.DESARMADA, m.state)
    }

    @Test
    fun restaura_el_estado_guardado() {
        val m = AlarmStateMachine()
        m.restore(AlarmState.ARMADA)
        assertEquals(AlarmState.ARMADA, m.state)
    }
}
