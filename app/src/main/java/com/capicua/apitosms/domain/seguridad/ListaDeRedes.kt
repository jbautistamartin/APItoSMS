// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.seguridad

import java.net.InetAddress

/**
 * Una red en notación CIDR (`192.168.1.0/24`) o una IP suelta (`192.168.1.20`, que es un `/32`).
 *
 * Funciona igual con IPv4 que con IPv6 porque compara bytes. Una dirección IPv4 nunca pertenece a
 * una red IPv6 ni al revés, y una IPv4 mapeada en IPv6 (`::ffff:192.168.1.20`, como la entregan
 * algunos sockets) se trata como la IPv4 que es.
 */
class Red private constructor(private val base: ByteArray, private val longitud: Int, val texto: String) {

    fun contiene(direccion: InetAddress): Boolean {
        val bytes = sinMapeo(direccion.address)
        if (bytes.size != base.size) return false
        var bitsRestantes = longitud
        for (i in bytes.indices) {
            if (bitsRestantes <= 0) return true
            val mascara = if (bitsRestantes >= 8) 0xFF else (0xFF shl (8 - bitsRestantes)) and 0xFF
            if ((bytes[i].toInt() and mascara) != (base[i].toInt() and mascara)) return false
            bitsRestantes -= 8
        }
        return true
    }

    override fun toString(): String = texto

    /**
     * Igual si la dirección escrita y la longitud son las mismas. Hace falta para comparar enlaces
     * (`domain/red/Enlace`): el servidor vuelve a preguntar por la red cada pocos segundos, y sin
     * esto cada respuesta parecería una red nueva y lo reataría sin motivo.
     */
    override fun equals(other: Any?): Boolean =
        other is Red && longitud == other.longitud && base.contentEquals(other.base)

    override fun hashCode(): Int = 31 * base.contentHashCode() + longitud

    companion object {
        /**
         * Lee una red, o null si [texto] no lo es. Solo acepta direcciones literales: nunca
         * resuelve un nombre por DNS, que es lo que haría [InetAddress.getByName] con algo que no
         * parezca una IP.
         */
        fun leer(texto: String): Red? {
            val limpio = texto.trim()
            if (limpio.isEmpty()) return null
            val partes = limpio.split('/')
            if (partes.size > 2) return null
            val direccion = literal(partes[0]) ?: return null
            val bytes = sinMapeo(direccion.address)
            val maximo = bytes.size * 8
            val longitud = if (partes.size == 2) {
                partes[1].toIntOrNull()?.takeIf { it in 0..maximo } ?: return null
            } else {
                maximo
            }
            return Red(bytes, longitud, limpio)
        }

        /** Una dirección literal IPv4 o IPv6, o null. Sin DNS. */
        fun literal(texto: String): InetAddress? {
            val t = texto.trim().removePrefix("[").removeSuffix("]")
            val pareceIpv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(t)
            val pareceIpv6 = t.contains(':') && t.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
            if (!pareceIpv4 && !pareceIpv6) return null
            if (pareceIpv4 && t.split('.').any { it.toInt() > 255 }) return null
            return runCatching { InetAddress.getByName(t) }.getOrNull()
        }

        private fun sinMapeo(bytes: ByteArray): ByteArray {
            if (bytes.size != 16) return bytes
            val mapeada = (0 until 10).all { bytes[it] == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
            return if (mapeada) bytes.copyOfRange(12, 16) else bytes
        }
    }
}

/**
 * Una lista de [Red]. Vacía no deja pasar a nadie: quien la use decide qué significa «vacía»
 * (en la API, «la subred wifi actual»).
 */
class ListaDeRedes(val redes: List<Red>) {

    fun contiene(direccion: InetAddress): Boolean = redes.any { it.contiene(direccion) }

    val vacia: Boolean get() = redes.isEmpty()

    companion object {
        /** Lee una lista separada por comas o saltos de línea; devuelve también lo que no se entendió. */
        fun leer(textos: List<String>): Pair<ListaDeRedes, List<String>> {
            val validas = mutableListOf<Red>()
            val invalidas = mutableListOf<String>()
            textos.map { it.trim() }.filter { it.isNotEmpty() }.forEach { texto ->
                Red.leer(texto)?.let { validas += it } ?: run { invalidas += texto }
            }
            return ListaDeRedes(validas) to invalidas
        }

        fun separar(texto: String): List<String> =
            texto.split(',', ';', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
