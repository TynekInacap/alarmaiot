package cl.alarmaiot.security

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * PIN de la alarma: como el teclado de una alarma real, armar o desarmar a
 * distancia exige conocer el PIN.
 *
 * El Panel NO guarda el PIN: el usuario lo escribe en cada Armar/Desarmar y
 * con él se firma el comando (HMAC-SHA256 sobre comando + nonce + hora).
 * La Central guarda el PIN cifrado y verifica la firma. Así, aunque alguien
 * tenga el código de vinculación o manipule el Panel, sin el PIN no puede
 * armar ni desarmar.
 */
object CommandAuth {

    const val ITERATIONS = 100_000
    const val PIN_HINT = "6 a 8 dígitos, sin repetir ni secuencias (ej. 482915)"

    fun deriveKey(pin: String, homeId: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), "alarmaiot/pin/v1/$homeId".toByteArray(UTF_8), ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun sign(key: ByteArray, command: Char, nonce: String, ts: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal("$command|$nonce|$ts".toByteArray(UTF_8)))
    }

    fun verify(key: ByteArray, command: Char, nonce: String, ts: Long, proof: String): Boolean {
        val expected = sign(key, command, nonce, ts).toByteArray(UTF_8)
        return MessageDigest.isEqual(expected, proof.toByteArray(UTF_8))
    }

    /** Devuelve null si el PIN es aceptable, o el motivo del rechazo. */
    fun pinError(pin: String): String? = when {
        pin.length !in 6..8 || pin.any { !it.isDigit() } -> "El PIN debe tener entre 6 y 8 dígitos"
        pin.toSet().size == 1 -> "El PIN no puede ser el mismo dígito repetido"
        isSequence(pin) -> "El PIN no puede ser una secuencia (123456, 654321)"
        else -> null
    }

    private fun isSequence(pin: String): Boolean {
        val diffs = pin.zipWithNext { a, b -> b - a }.toSet()
        return diffs == setOf(1) || diffs == setOf(-1)
    }
}
