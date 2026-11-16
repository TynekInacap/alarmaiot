package cl.alarmaiot.security

import java.security.SecureRandom

/**
 * Código de recuperación de contraseña (como los "códigos de respaldo" de la
 * verificación en dos pasos). Se muestra UNA sola vez al crear la cuenta y se
 * guarda solo su hash PBKDF2, igual que una contraseña.
 *
 * Formato: 12 caracteres en grupos de 4 (ej. K7M2-QX9P-4TRA), sin letras
 * ambiguas (O, 0, I, 1, L): unos 59 bits de azar.
 */
object RecoveryCode {

    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 12
    private val random = SecureRandom()

    fun generate(): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }
            .chunked(4)
            .joinToString("-") { it.joinToString("") }

    /** Ignora guiones, espacios y minúsculas para que el usuario pueda escribirlo como quiera. */
    fun normalize(input: String): String = input.uppercase().filter { it in ALPHABET }

    fun isWellFormed(input: String): Boolean = normalize(input).length == LENGTH
}
