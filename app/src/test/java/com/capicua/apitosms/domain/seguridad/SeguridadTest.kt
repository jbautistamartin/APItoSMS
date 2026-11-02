// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.seguridad

import com.capicua.apitosms.RelojFijo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SeguridadTest {

    // ── Secretos ──────────────────────────────────────────────────────────────

    @Test
    fun `el secreto tiene el formato y no se repite`() {
        val a = SecretoDeClave.generar()
        val b = SecretoDeClave.generar()
        assertTrue(SecretoDeClave.tieneFormato(a))
        assertEquals(SecretoDeClave.PREFIJO.length + SecretoDeClave.LONGITUD_CUERPO, a.length)
        assertNotEquals(a, b)
        assertFalse(SecretoDeClave.tieneFormato("ats_corto"))
        assertFalse(SecretoDeClave.tieneFormato("xyz_" + a.drop(4)))
    }

    @Test
    fun `el hash es estable y se compara bien`() {
        val s = SecretoDeClave.generar()
        val h = SecretoDeClave.hash(s)
        assertEquals(h, SecretoDeClave.hash(s))
        assertTrue(SecretoDeClave.mismoHash(h, SecretoDeClave.hash(s)))
        assertFalse(SecretoDeClave.mismoHash(h, SecretoDeClave.hash(SecretoDeClave.generar())))
        assertEquals(SecretoDeClave.CARACTERES_VISIBLES, SecretoDeClave.prefijoVisible(s).length)
    }

    // ── Redes ─────────────────────────────────────────────────────────────────

    private fun ip(texto: String) = InetAddress.getByName(texto)

    @Test
    fun `una red CIDR contiene sus direcciones y no las de fuera`() {
        val red = Red.leer("192.168.100.0/24")!!
        assertTrue(red.contiene(ip("192.168.100.67")))
        assertFalse(red.contiene(ip("192.168.101.1")))
        assertTrue(Red.leer("10.0.0.5")!!.contiene(ip("10.0.0.5")))
        assertFalse(Red.leer("10.0.0.5")!!.contiene(ip("10.0.0.6")))
    }

    @Test
    fun `una IPv4 mapeada en IPv6 cuenta como IPv4`() {
        assertTrue(Red.leer("192.168.1.0/24")!!.contiene(ip("::ffff:192.168.1.20")))
    }

    @Test
    fun `no se resuelven nombres ni se aceptan prefijos imposibles`() {
        assertNull(Red.leer("intranet.local"))
        assertNull(Red.leer("192.168.1.0/33"))
        assertNull(Red.leer("192.168.1.0/x"))
        val (lista, malas) = ListaDeRedes.leer(listOf("10.0.0.0/8", "basura"))
        assertEquals(1, lista.redes.size)
        assertEquals(listOf("basura"), malas)
    }

    // ── Ritmo ─────────────────────────────────────────────────────────────────

    @Test
    fun `el cubo deja pasar el ritmo y luego deniega`() {
        val reloj = RelojFijo()
        val limitador = LimitadorDeRitmo(reloj)
        repeat(5) { assertEquals(LimitadorDeRitmo.Decision.Permitida, limitador.consumir("k", 5)) }
        assertTrue(limitador.consumir("k", 5) is LimitadorDeRitmo.Decision.Denegada)
        // Otra clave tiene su propio cubo.
        assertEquals(LimitadorDeRitmo.Decision.Permitida, limitador.consumir("otra", 5))
        reloj.avanzar(12)
        assertEquals(LimitadorDeRitmo.Decision.Permitida, limitador.consumir("k", 5))
    }

    // ── Fuerza bruta ──────────────────────────────────────────────────────────

    @Test
    fun `diez fallos bloquean la IP quince minutos`() {
        val reloj = RelojFijo()
        val bloqueo = BloqueoPorFallos(reloj)
        repeat(9) { assertFalse(bloqueo.registrarFallo("1.2.3.4")) }
        assertTrue(bloqueo.registrarFallo("1.2.3.4"))
        assertTrue(bloqueo.estaBloqueada("1.2.3.4"))
        assertFalse(bloqueo.estaBloqueada("1.2.3.5"))
        reloj.avanzar(15 * 60 + 1)
        assertFalse(bloqueo.estaBloqueada("1.2.3.4"))
    }

    @Test
    fun `los fallos viejos caducan y un acierto limpia`() {
        val reloj = RelojFijo()
        val bloqueo = BloqueoPorFallos(reloj)
        repeat(9) { bloqueo.registrarFallo("1.2.3.4") }
        reloj.avanzar(5 * 60 + 1)
        assertFalse(bloqueo.registrarFallo("1.2.3.4"))
        repeat(8) { bloqueo.registrarFallo("1.2.3.4") }
        bloqueo.registrarAcierto("1.2.3.4")
        assertFalse(bloqueo.registrarFallo("1.2.3.4"))
    }
}
