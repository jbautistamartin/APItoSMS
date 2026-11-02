// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.servidor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class LectorHttpTest {

    private val ip = InetAddress.getByName("192.168.1.10")

    private fun lector(texto: String, maxCuerpo: Int = LectorHttp.MAX_CUERPO) =
        LectorHttp(texto.replace("\n", "\r\n").byteInputStream(), ip, maxCuerpo)

    private fun estadoDe(texto: String): Int = try {
        lector(texto).leer()
        fail("tenía que rechazarse")
        0
    } catch (e: LectorHttp.ErrorDeProtocolo) {
        e.estado
    }

    @Test
    fun `lee la petición con cabeceras, query y cuerpo`() {
        val p = lector("POST /api/v1/mensajes?estado=EN_COLA&ref=a%20b HTTP/1.1\nHost: x\nContent-Type: application/json\nContent-Length: 2\n\n{}")
            .leer()!!
        assertEquals("POST", p.metodo)
        assertEquals("/api/v1/mensajes", p.ruta)
        assertEquals(mapOf("estado" to "EN_COLA", "ref" to "a b"), p.query)
        assertEquals("application/json", p.cabecera("content-type"))
        assertEquals("{}", String(p.cuerpo))
    }

    @Test
    fun `un POST sin Content-Length tiene cuerpo vacío`() {
        // La pausa de la cola es un POST sin cuerpo, y curl -X POST no manda la cabecera.
        val p = lector("POST /api/v1/cola/pausa HTTP/1.1\nHost: x\n\n").leer()!!
        assertEquals(0, p.cuerpo.size)
    }

    @Test
    fun `keep-alive lee dos peticiones seguidas y luego null`() {
        val l = lector("GET /a HTTP/1.1\nHost: x\n\nGET /b HTTP/1.1\nHost: x\n\n")
        assertEquals("/a", l.leer()!!.ruta)
        assertEquals("/b", l.leer()!!.ruta)
        assertNull(l.leer())
    }

    @Test
    fun `rechaza lo que abre la puerta al contrabando de peticiones`() {
        assertEquals(501, estadoDe("POST / HTTP/1.1\nTransfer-Encoding: chunked\n\n"))
        assertEquals(400, estadoDe("POST / HTTP/1.1\nContent-Length: 1\nContent-Length: 2\n\nab"))
        assertEquals(400, estadoDe("POST / HTTP/1.1\nContent-Length: -1\n\n"))
        assertEquals(400, estadoDe("GET / HTTP/1.1\n folded: x\n\n"))
    }

    @Test
    fun `rechaza lo mal formado y lo demasiado grande`() {
        assertEquals(400, estadoDe("GET\n\n"))
        assertEquals(505, estadoDe("GET / HTTP/2.0\n\n"))
        assertEquals(413, try {
            lector("POST / HTTP/1.1\nContent-Length: 11\n\n01234567890", maxCuerpo = 10).leer(); 0
        } catch (e: LectorHttp.ErrorDeProtocolo) { e.estado })
        assertEquals(400, estadoDe("POST / HTTP/1.1\nContent-Length: 10\n\ncorto"))
        val muchas = (1..100).joinToString("\n") { "X-$it: v" }
        assertEquals(431, estadoDe("GET / HTTP/1.1\n$muchas\n\n"))
    }

    @Test
    fun `100-continue se atiende solo si hay cuerpo`() {
        var avisos = 0
        lector("POST / HTTP/1.1\nExpect: 100-continue\nContent-Length: 2\n\n{}").leer { avisos++ }
        assertEquals(1, avisos)
    }

    @Test
    fun `la respuesta lleva las cabeceras de seguridad`() {
        val salida = ByteArrayOutputStream()
        LectorHttp.escribir(salida, com.capicua.apitosms.domain.api.RespuestaHttp(200, "application/json", "{}".toByteArray()), true)
        val texto = salida.toString()
        assertTrue(texto.startsWith("HTTP/1.1 200 OK\r\n"))
        listOf("Cache-Control: no-store", "X-Content-Type-Options: nosniff", "Content-Length: 2").forEach {
            assertTrue(it, texto.contains(it))
        }
    }
}
