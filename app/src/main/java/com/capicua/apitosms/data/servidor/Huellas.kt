// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.servidor

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Las dos huellas de un certificado, que es lo que un cliente fija en lugar de confiar en una CA.
 *
 * - [certificado]: SHA-256 del certificado entero, en hexadecimal con dos puntos
 *   (`1C:6C:F6:…`). Es la que muestra Windows, la que da `openssl x509 -fingerprint -sha256` y la
 *   que compara `Capicua.ApiToSms.Cliente` con `X509Certificate2.GetCertHashString(SHA256)`.
 * - [clavePublica]: SHA-256 de la clave pública (SPKI), en base64. Es la que pide `curl
 *   --pinnedpubkey sha256//…`. Sobrevive a renovar el certificado con la misma clave, pero aquí
 *   no se renueva nada: regenerar es otra clave.
 */
data class Huellas(val certificado: String, val clavePublica: String) {

    /** El argumento de `curl --pinnedpubkey`. */
    val paraCurl: String get() = "sha256//$clavePublica"

    companion object {
        fun de(cert: X509Certificate): Huellas {
            val sha = MessageDigest.getInstance("SHA-256")
            val delCert = sha.digest(cert.encoded).joinToString(":") { "%02X".format(it) }
            val deLaClave = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            )
            return Huellas(delCert, deLaClave)
        }

        /** Para comparar huellas escritas a mano: sin separadores y en mayúsculas. */
        fun normalizar(huella: String): String =
            huella.filter { it.isLetterOrDigit() }.uppercase()
    }
}
