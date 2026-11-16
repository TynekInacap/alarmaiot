package cl.alarmaiot

import cl.alarmaiot.data.ConfigStore
import cl.alarmaiot.security.CommandAuth
import cl.alarmaiot.security.PasswordHasher
import cl.alarmaiot.security.RecoveryCode
import cl.alarmaiot.security.RateLimiter
import cl.alarmaiot.security.ReplayGuard
import cl.alarmaiot.security.SecureChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas de las medidas de seguridad (ISO/IEC 27400). */
class SecurityTest {

    // ----------------------------------------------------------- contraseñas

    @Test
    fun hash_de_contrasena_verifica_solo_la_correcta() {
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash("Clave2026", salt, 1_000)
        assertTrue(PasswordHasher.verify("Clave2026", salt, hash, 1_000))
        assertFalse(PasswordHasher.verify("clave2026", salt, hash, 1_000))
    }

    @Test
    fun misma_contrasena_con_distinta_sal_da_hash_distinto() {
        val a = PasswordHasher.hash("Clave2026", PasswordHasher.newSalt(), 1_000)
        val b = PasswordHasher.hash("Clave2026", PasswordHasher.newSalt(), 1_000)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun politica_de_contrasenas() {
        assertNotNull(PasswordHasher.policyError("Cort#1"))            // muy corta
        assertNotNull(PasswordHasher.policyError("alarma#2026x"))      // sin mayúscula
        assertNotNull(PasswordHasher.policyError("ALARMA#2026X"))      // sin minúscula
        assertNotNull(PasswordHasher.policyError("Alarma#Casa!"))      // sin número
        assertNotNull(PasswordHasher.policyError("Alarma2026x"))       // sin símbolo
        assertNotNull(PasswordHasher.policyError("Alarma #2026"))      // con espacio
        assertNotNull(PasswordHasher.policyError("Martin#2026", "martin")) // contiene el usuario
        assertNull(PasswordHasher.policyError("Alarma#2026"))
        assertNull(PasswordHasher.policyError("Alarma#2026", "admin"))
    }

    // ----------------------------------------------- código de recuperación

    @Test
    fun codigo_de_recuperacion_tiene_formato_y_se_normaliza() {
        val code = RecoveryCode.generate()
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(code))
        assertTrue(RecoveryCode.isWellFormed(code.lowercase().replace("-", " ")))
        assertEquals(RecoveryCode.normalize(code), RecoveryCode.normalize(code.lowercase()))
        assertFalse(RecoveryCode.isWellFormed("ABCD-EFGH"))
    }

    @Test
    fun codigos_de_recuperacion_no_se_repiten() {
        val codes = (1..500).map { RecoveryCode.generate() }.toSet()
        assertEquals(500, codes.size)
    }

    // ------------------------------------------------------ PIN de la alarma

    @Test
    fun politica_del_pin() {
        assertNotNull(CommandAuth.pinError("1234"))      // corto
        assertNotNull(CommandAuth.pinError("12a456"))    // no numérico
        assertNotNull(CommandAuth.pinError("111111"))    // repetido
        assertNotNull(CommandAuth.pinError("123456"))    // secuencia
        assertNotNull(CommandAuth.pinError("987654"))    // secuencia inversa
        assertNull(CommandAuth.pinError("482915"))
    }

    @Test
    fun comando_firmado_con_el_pin_correcto_se_acepta() {
        val key = CommandAuth.deriveKey("482915", "casa-abc123")
        val proof = CommandAuth.sign(key, 'D', "n1", 1_000L)
        assertTrue(CommandAuth.verify(key, 'D', "n1", 1_000L, proof))
    }

    @Test
    fun comando_con_pin_incorrecto_o_alterado_se_rechaza() {
        val central = CommandAuth.deriveKey("482915", "casa-abc123")
        val atacante = CommandAuth.deriveKey("000001", "casa-abc123")
        val proof = CommandAuth.sign(atacante, 'D', "n1", 1_000L)
        assertFalse(CommandAuth.verify(central, 'D', "n1", 1_000L, proof))
        val buena = CommandAuth.sign(central, 'A', "n1", 1_000L)
        assertFalse(CommandAuth.verify(central, 'D', "n1", 1_000L, buena)) // firma de "Armar" usada para "Desarmar"
    }

    // ------------------------------------------------------ canal cifrado

    @Test
    fun mensaje_cifrado_se_descifra_con_el_mismo_codigo() {
        val central = SecureChannel("Codigo2026", "casa-abc123")
        val panel = SecureChannel("Codigo2026", "casa-abc123")
        val sealed = panel.seal("""{"cmd":"A"}""", "alarmaiot/v1/casa-abc123/cmd")
        assertFalse(sealed.contains("\"cmd\"")) // el broker no ve el contenido
        assertEquals("""{"cmd":"A"}""", central.open(sealed, "alarmaiot/v1/casa-abc123/cmd"))
    }

    @Test
    fun codigo_incorrecto_es_rechazado() {
        val atacante = SecureChannel("OtroCodigo1", "casa-abc123")
        val central = SecureChannel("Codigo2026", "casa-abc123")
        val sealed = atacante.seal("""{"cmd":"D"}""", "t")
        assertNull(central.open(sealed, "t"))
    }

    @Test
    fun mensaje_alterado_es_rechazado() {
        val ch = SecureChannel("Codigo2026", "casa-abc123")
        val sealed = ch.seal("""{"cmd":"A"}""", "t")
        val bytes = java.util.Base64.getDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(ch.open(java.util.Base64.getEncoder().encodeToString(bytes), "t"))
    }

    @Test
    fun mensaje_reenviado_a_otro_topico_es_rechazado() {
        val ch = SecureChannel("Codigo2026", "casa-abc123")
        val sealed = ch.seal("""{"state":"DESARMADA"}""", "alarmaiot/v1/casa-abc123/state")
        assertNull(ch.open(sealed, "alarmaiot/v1/casa-abc123/cmd"))
    }

    @Test
    fun cada_cifrado_es_distinto_por_el_iv_aleatorio() {
        val ch = SecureChannel("Codigo2026", "casa-abc123")
        assertNotEquals(ch.seal("hola", "t"), ch.seal("hola", "t"))
    }

    // -------------------------------------------------- anti-repetición

    @Test
    fun nonce_repetido_es_rechazado() {
        var now = 1_000_000L
        val guard = ReplayGuard(windowMs = 60_000) { now }
        assertTrue(guard.accept("n1", now))
        assertFalse(guard.accept("n1", now))
        now += 1_000
        assertTrue(guard.accept("n2", now))
    }

    @Test
    fun mensaje_viejo_es_rechazado() {
        val now = 10_000_000L
        val guard = ReplayGuard(windowMs = 60_000) { now }
        assertFalse(guard.accept("n1", now - 120_000))
        assertTrue(guard.accept("n2", now - 30_000))
    }

    @Test
    fun limitador_bloquea_rafagas() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 3, windowMs = 1_000) { now }
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        now += 1_000
        assertTrue(limiter.tryAcquire())
    }

    // ------------------------------------------------------- configuración

    @Test
    fun solo_se_aceptan_brokers_cifrados() {
        assertNull(ConfigStore.brokerError("ssl://broker.hivemq.com:8883"))
        assertNull(ConfigStore.brokerError("wss://broker.hivemq.com:8884/mqtt"))
        assertNotNull(ConfigStore.brokerError("tcp://broker.hivemq.com:1883"))
        assertNotNull(ConfigStore.brokerError("ssl://"))
    }
}
