package cl.alarmaiot.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hash de contraseñas con PBKDF2-HMAC-SHA256 + sal aleatoria por usuario.
 *
 * ISO/IEC 27400 / 27002 (control 8.5 "Autenticación segura"): las contraseñas
 * nunca se guardan en texto plano, solo su hash derivado, de modo que si
 * alguien copia la base de datos no puede leer las credenciales.
 */
object PasswordHasher {

    const val DEFAULT_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun hash(password: String, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Comparación en tiempo constante para no filtrar información por tiempos de respuesta. */
    fun verify(password: String, salt: ByteArray, expectedHash: ByteArray, iterations: Int): Boolean =
        MessageDigest.isEqual(hash(password, salt, iterations), expectedHash)

    const val MIN_LENGTH = 10
    const val POLICY_HINT = "Mínimo 10 caracteres, con mayúscula, minúscula, número y símbolo (ej. Alarma#2026)"

    /**
     * Política de contraseñas. Devuelve null si cumple, o el motivo del rechazo.
     * Exige largo mínimo, mayúscula, minúscula, número y símbolo, sin espacios
     * y sin contener el nombre de usuario.
     */
    fun policyError(password: String, username: String? = null): String? = when {
        password.length < MIN_LENGTH -> "La contraseña debe tener al menos $MIN_LENGTH caracteres"
        password.length > 64 -> "La contraseña no puede superar 64 caracteres"
        password.any { it.isWhitespace() } -> "La contraseña no puede tener espacios"
        password.none { it.isUpperCase() } -> "La contraseña debe incluir al menos una mayúscula"
        password.none { it.isLowerCase() } -> "La contraseña debe incluir al menos una minúscula"
        password.none { it.isDigit() } -> "La contraseña debe incluir al menos un número"
        password.all { it.isLetterOrDigit() } -> "La contraseña debe incluir al menos un símbolo (# ! @ $ % …)"
        username != null && username.length >= 3 && password.contains(username.trim(), ignoreCase = true) ->
            "La contraseña no puede contener el nombre de usuario"
        else -> null
    }
}
