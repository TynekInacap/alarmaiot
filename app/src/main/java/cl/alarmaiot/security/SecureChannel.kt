package cl.alarmaiot.security

import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado extremo a extremo de los mensajes MQTT entre la Central y el Panel.
 *
 * - La clave AES-256 se deriva del "código de vinculación" que se ingresa en
 *   ambos teléfonos (PBKDF2-HMAC-SHA256), así el broker público nunca ve los datos.
 * - AES-GCM entrega confidencialidad + integridad: si alguien altera o inventa
 *   un mensaje sin conocer el código, [open] devuelve null y se rechaza.
 * - El tópico se usa como "dato asociado" (AAD), por lo que un mensaje válido
 *   no puede reenviarse a otro tópico (p. ej. un estado reenviado como comando).
 */
class SecureChannel(pairingCode: String, homeId: String) {

    private val key: SecretKeySpec

    init {
        require(pairingCode.length >= MIN_CODE_LENGTH) { "El código de vinculación es muy corto" }
        val spec = PBEKeySpec(pairingCode.toCharArray(), "alarmaiot/v1/$homeId".toByteArray(UTF_8), ITERATIONS, 256)
        try {
            key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(plaintext: String, context: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(context.toByteArray(UTF_8))
        val ct = cipher.doFinal(plaintext.toByteArray(UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    fun open(sealed: String, context: String): String? = try {
        val all = Base64.getDecoder().decode(sealed.trim())
        if (all.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
            cipher.updateAAD(context.toByteArray(UTF_8))
            String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), UTF_8)
        }
    } catch (e: Exception) {
        null // clave incorrecta, mensaje alterado o formato inválido
    }

    companion object {
        const val MIN_CODE_LENGTH = 6
        // 210.000 iteraciones (recomendación OWASP para PBKDF2-SHA256): encarece
        // adivinar el código de vinculación a partir de mensajes capturados.
        private const val ITERATIONS = 210_000
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val random = SecureRandom()
    }
}
