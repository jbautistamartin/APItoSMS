// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.api

import java.net.InetAddress

/**
 * Una petición HTTP ya leída del socket.
 *
 * Es lo único que la API ve del servidor: así la API se prueba en la JVM sin sockets, y el
 * servidor se prueba sin la API.
 *
 * @property ruta la ruta sin la query (`/api/v1/mensajes/abc`).
 * @property cabeceras con el nombre **en minúsculas**: HTTP no distingue mayúsculas en los nombres.
 */
class PeticionHttp(
    val metodo: String,
    val ruta: String,
    val query: Map<String, String>,
    val cabeceras: Map<String, String>,
    val cuerpo: ByteArray,
    val ip: InetAddress,
    /** `HTTP/1.1` o `HTTP/1.0`. Con la 1.0 la conexión se cierra tras la respuesta. */
    val version: String = "HTTP/1.1"
) {
    fun cabecera(nombre: String): String? = cabeceras[nombre.lowercase()]
}

class RespuestaHttp(
    val estado: Int,
    val tipo: String? = null,
    val cuerpo: ByteArray = ByteArray(0),
    val cabeceras: Map<String, String> = emptyMap()
) {
    companion object {
        const val JSON = "application/json; charset=utf-8"
        const val PROBLEMA = "application/problem+json; charset=utf-8"

        fun textoDeEstado(estado: Int): String = when (estado) {
            100 -> "Continue"
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Content Too Large"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Content"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}

/** Lo que el servidor sabe hacer con una petición. Lo implementa [ApiDeMensajes]. */
fun interface ManejadorHttp {
    suspend fun manejar(peticion: PeticionHttp): RespuestaHttp
}
