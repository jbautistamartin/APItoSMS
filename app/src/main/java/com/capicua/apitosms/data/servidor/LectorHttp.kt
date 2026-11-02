// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.servidor

import com.capicua.apitosms.domain.api.PeticionHttp
import com.capicua.apitosms.domain.api.RespuestaHttp
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URLDecoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Lee peticiones HTTP/1.1 de un flujo y escribe respuestas. Solo lo que hace falta para una API
 * JSON, y con límites en todo.
 *
 * Un servidor escrito a mano es superficie de ataque, y por eso este es pequeño y desconfiado:
 *
 * - **Límites de tamaño** en la línea de petición, en el número y el tamaño de las cabeceras y en
 *   el cuerpo. Nada se lee sin saber antes cuánto va a ocupar.
 * - **Solo `Content-Length`.** `Transfer-Encoding` se rechaza: un cuerpo troceado es la puerta
 *   clásica del *request smuggling*, y ningún cliente de esta API lo necesita.
 * - **Cabeceras duplicadas** de las que importan (`Content-Length`, `Authorization`, `Host`)
 *   se rechazan en lugar de elegir una.
 * - `Expect: 100-continue` se atiende, porque `curl` lo manda con cuerpos grandes y se quedaría
 *   esperando un segundo antes de enviar.
 */
class LectorHttp(
    private val entrada: InputStream,
    private val ip: InetAddress,
    private val maxCuerpo: Int = MAX_CUERPO
) {

    /** Una petición mal formada: se contesta con [estado] y se cierra la conexión. */
    class ErrorDeProtocolo(val estado: Int, mensaje: String) : Exception(mensaje)

    /**
     * Lee la siguiente petición. Devuelve null si el cliente cerró la conexión limpiamente entre
     * dos peticiones, que es como acaba una conexión *keep-alive*.
     *
     * @param continuar se llama si la petición pide `100-continue`, antes de leer el cuerpo.
     */
    fun leer(continuar: () -> Unit = {}): PeticionHttp? {
        val lineaInicial = leerLinea(MAX_LINEA, permitirFin = true) ?: return null
        val partes = lineaInicial.split(' ')
        if (partes.size != 3) throw ErrorDeProtocolo(400, "línea de petición mal formada")
        val (metodo, objetivo, version) = partes
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw ErrorDeProtocolo(505, "versión $version")
        if (!metodo.all { it in 'A'..'Z' }) throw ErrorDeProtocolo(400, "método mal formado")
        if (!objetivo.startsWith("/")) throw ErrorDeProtocolo(400, "objetivo mal formado")

        val cabeceras = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val linea = leerLinea(MAX_LINEA, permitirFin = false)!!
            if (linea.isEmpty()) break
            total += linea.length
            if (cabeceras.size >= MAX_CABECERAS || total > MAX_TOTAL_CABECERAS) {
                throw ErrorDeProtocolo(431, "demasiadas cabeceras")
            }
            val dosPuntos = linea.indexOf(':')
            if (dosPuntos <= 0 || linea[0] == ' ' || linea[0] == '\t') {
                throw ErrorDeProtocolo(400, "cabecera mal formada")
            }
            val nombre = linea.substring(0, dosPuntos).trim().lowercase()
            if (nombre.any { it == ' ' || it == '\t' }) throw ErrorDeProtocolo(400, "nombre de cabecera mal formado")
            val valor = linea.substring(dosPuntos + 1).trim()
            val previo = cabeceras[nombre]
            if (previo != null) {
                if (nombre in UNICAS) throw ErrorDeProtocolo(400, "cabecera $nombre repetida")
                cabeceras[nombre] = "$previo, $valor"
            } else {
                cabeceras[nombre] = valor
            }
        }

        if (cabeceras.containsKey("transfer-encoding")) {
            throw ErrorDeProtocolo(501, "Transfer-Encoding no admitido; usa Content-Length")
        }
        val longitud = cabeceras["content-length"]?.let {
            it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw ErrorDeProtocolo(400, "Content-Length no válido")
        } ?: 0L
        // Sin Content-Length ni Transfer-Encoding, el cuerpo de una petición mide cero (RFC 9112,
        // 6.3). No es un 411: `curl -X POST` sin datos no manda la cabecera, y la pausa de la cola
        // es justo un POST sin cuerpo. Exigirla hizo que una pausa fallara en la primera prueba
        // en un teléfono real y la cola siguiera enviando.
        if (longitud > maxCuerpo) throw ErrorDeProtocolo(413, "el cuerpo supera $maxCuerpo bytes")

        if (longitud > 0 && cabeceras["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            continuar()
        }
        val cuerpo = ByteArray(longitud.toInt())
        var leidos = 0
        while (leidos < cuerpo.size) {
            val n = entrada.read(cuerpo, leidos, cuerpo.size - leidos)
            if (n < 0) throw ErrorDeProtocolo(400, "el cuerpo terminó antes de Content-Length")
            leidos += n
        }

        val ruta = objetivo.substringBefore('?')
        val query = if ('?' in objetivo) leerQuery(objetivo.substringAfter('?')) else emptyMap()
        return PeticionHttp(
            metodo = metodo,
            ruta = decodificar(ruta),
            query = query,
            cabeceras = cabeceras,
            cuerpo = cuerpo,
            ip = ip,
            version = version
        )
    }

    private fun leerLinea(maximo: Int, permitirFin: Boolean): String? {
        val sb = StringBuilder()
        while (true) {
            val c = entrada.read()
            if (c < 0) {
                if (permitirFin && sb.isEmpty()) return null
                throw ErrorDeProtocolo(400, "conexión cerrada a mitad de la petición")
            }
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            // ISO-8859-1: las cabeceras HTTP son bytes, no UTF-8. La ruta se decodifica aparte.
            sb.append(c.toChar())
            if (sb.length > maximo) throw ErrorDeProtocolo(if (permitirFin) 414 else 431, "línea demasiado larga")
        }
    }

    private fun leerQuery(texto: String): Map<String, String> =
        texto.split('&').filter { it.isNotEmpty() }.associate { par ->
            val nombre = par.substringBefore('=')
            val valor = if ('=' in par) par.substringAfter('=') else ""
            decodificar(nombre) to decodificar(valor)
        }

    // La sobrecarga con Charset es de la API 33; con minSdk 26 hay que usar la del nombre.
    private fun decodificar(texto: String): String = try {
        URLDecoder.decode(texto, "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw ErrorDeProtocolo(400, "codificación % no válida")
    }

    companion object {
        const val MAX_LINEA = 8 * 1024
        const val MAX_CABECERAS = 64
        const val MAX_TOTAL_CABECERAS = 16 * 1024
        /** Un lote de 100 mensajes de 1.600 caracteres en UTF-8 cabe con holgura. */
        const val MAX_CUERPO = 1024 * 1024

        private val UNICAS = setOf("content-length", "authorization", "host", "content-type", "idempotency-key")

        private val FECHA_HTTP: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

        /** Escribe [respuesta] entera, con las cabeceras de seguridad comunes a todas. */
        fun escribir(salida: OutputStream, respuesta: RespuestaHttp, mantenerAbierta: Boolean) {
            val cabeceras = StringBuilder()
            cabeceras.append("HTTP/1.1 ${respuesta.estado} ${RespuestaHttp.textoDeEstado(respuesta.estado)}\r\n")
            cabeceras.append("Date: ${FECHA_HTTP.format(ZonedDateTime.now(ZoneOffset.UTC))}\r\n")
            cabeceras.append("Server: APItoSMS\r\n")
            respuesta.tipo?.let { cabeceras.append("Content-Type: $it\r\n") }
            cabeceras.append("Content-Length: ${respuesta.cuerpo.size}\r\n")
            cabeceras.append("Cache-Control: no-store\r\n")
            cabeceras.append("X-Content-Type-Options: nosniff\r\n")
            cabeceras.append("Connection: ${if (mantenerAbierta) "keep-alive" else "close"}\r\n")
            respuesta.cabeceras.forEach { (nombre, valor) ->
                // Un salto de línea en un valor partiría la respuesta: nunca debería llegar aquí,
                // pero si llega se quita en lugar de escribirlo.
                cabeceras.append("$nombre: ${valor.replace("\r", "").replace("\n", "")}\r\n")
            }
            cabeceras.append("\r\n")
            salida.write(cabeceras.toString().toByteArray(Charsets.ISO_8859_1))
            salida.write(respuesta.cuerpo)
            salida.flush()
        }

        fun escribirContinue(salida: OutputStream) {
            salida.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            salida.flush()
        }
    }
}
