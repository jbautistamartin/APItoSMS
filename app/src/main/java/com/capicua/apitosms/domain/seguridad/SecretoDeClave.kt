// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.seguridad

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Los secretos de las claves de la API: cómo se generan, cómo se guardan y cómo se comparan.
 *
 * ## Formato
 * `ats_` seguido de 43 caracteres base62: 256 bits de [SecureRandom]. El prefijo no es adorno:
 * los escáneres de secretos (GitHub, GitLab, los de los IDE) buscan prefijos conocidos, y una
 * clave que acaba en un repositorio por error se detecta antes si tiene uno. También permite a
 * la API rechazar sin consultar la base de datos algo que ni siquiera tiene forma de clave.
 *
 * ## Por qué SHA-256 y no bcrypt
 * Un hash lento protege **contraseñas**, que tienen poca entropía y se pueden adivinar. Un secreto
 * de 256 bits aleatorios no se adivina con ningún número de intentos, así que el hash solo tiene
 * que ser de un sentido. Con bcrypt cada petición a la API pagaría decenas de milisegundos de CPU
 * en el teléfono sin ganar nada.
 */
object SecretoDeClave {

    const val PREFIJO = "ats_"

    /** Longitud del cuerpo en base62: 43 caracteres llevan algo más de 256 bits. */
    const val LONGITUD_CUERPO = 43

    /** Caracteres del secreto que se guardan en claro para reconocer la clave: `ats_7Kq2`. */
    const val CARACTERES_VISIBLES = PREFIJO.length + 4

    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val aleatorio = SecureRandom()

    /** Un secreto nuevo. Se enseña una vez y no se guarda: se guarda su [hash]. */
    fun generar(): String = buildString(PREFIJO.length + LONGITUD_CUERPO) {
        append(PREFIJO)
        // Rechazo de los bytes >= 248 (4 × 62): así cada carácter es exactamente equiprobable.
        // Con un módulo directo los ocho primeros caracteres saldrían algo más a menudo.
        while (length < PREFIJO.length + LONGITUD_CUERPO) {
            val b = aleatorio.nextInt(256)
            if (b < 248) append(BASE62[b % 62])
        }
    }

    /** true si [texto] tiene la forma de un secreto. No dice si existe. */
    fun tieneFormato(texto: String): Boolean =
        texto.length == PREFIJO.length + LONGITUD_CUERPO &&
            texto.startsWith(PREFIJO) &&
            texto.drop(PREFIJO.length).all { it in BASE62 }

    /** SHA-256 del secreto, en hexadecimal. Es lo único que se persiste. */
    fun hash(secreto: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(secreto.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Comparación en tiempo constante de dos hashes, para no filtrar por el tiempo cuánto coinciden. */
    fun mismoHash(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    /** La parte visible: `ats_7Kq2`. */
    fun prefijoVisible(secreto: String): String = secreto.take(CARACTERES_VISIBLES)
}
