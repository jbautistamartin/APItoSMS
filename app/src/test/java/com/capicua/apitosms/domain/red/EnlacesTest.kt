// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.red

import com.capicua.apitosms.domain.seguridad.ListaDeRedes
import com.capicua.apitosms.domain.seguridad.Red
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class EnlacesTest {

    private fun ip(texto: String): InetAddress = Red.literal(texto)!!
    private fun enlace(tipo: TipoEnlace, dir: String, prefijo: Int) =
        Enlace(tipo, ip(dir), Red.leer("$dir/$prefijo"))

    private val wifi = enlace(TipoEnlace.WIFI, "192.168.1.50", 24)
    private val usb = enlace(TipoEnlace.USB, "192.168.42.129", 24)
    private val sinLista = ListaDeRedes(emptyList())

    // ── Encontrar el anclaje USB ──────────────────────────────────────────────

    @Test
    fun `reconoce los nombres de anclaje USB y nada más`() {
        listOf("rndis0", "ncm0", "usb0", "ecm1").forEach { assertTrue(it, Enlaces.esInterfazDeAnclajeUsb(it)) }
        listOf("wlan0", "rmnet_data0", "lo", "ap0", "swlan0", "eth0", "rndis", "dummy0")
            .forEach { assertFalse(it, Enlaces.esInterfazDeAnclajeUsb(it)) }
    }

    @Test
    fun `el anclaje USB se busca entre las interfaces encendidas con IPv4`() {
        val interfaces = listOf(
            InterfazDelSistema("wlan0", true, listOf(ip("192.168.1.50") to 24)),
            InterfazDelSistema("rndis0", false, listOf(ip("192.168.99.1") to 24)),
            InterfazDelSistema("ncm0", true, listOf(ip("fe80::1") to 64, ip("192.168.42.129") to 24))
        )
        val encontrado = Enlaces.buscarAnclajeUsb(interfaces)!!
        assertEquals(TipoEnlace.USB, encontrado.tipo)
        assertEquals("192.168.42.129", encontrado.ip)
        assertTrue(encontrado.subred!!.contiene(ip("192.168.42.200")))
        assertFalse(encontrado.subred!!.contiene(ip("192.168.43.1")))
    }

    @Test
    fun `sin cable o con el anclaje apagado no hay enlace USB`() {
        assertNull(Enlaces.buscarAnclajeUsb(emptyList()))
        assertNull(Enlaces.buscarAnclajeUsb(listOf(InterfazDelSistema("rndis0", true, emptyList()))))
        assertNull(Enlaces.buscarAnclajeUsb(listOf(InterfazDelSistema("rndis0", false, listOf(ip("192.168.42.1") to 24)))))
    }

    // ── Qué enlaces se quieren ────────────────────────────────────────────────

    @Test
    fun `las opciones apagadas dejan solo la wifi, como siempre`() {
        assertEquals(listOf(wifi), Enlaces.deseados(wifi, usb, escucharPorUsb = false, escucharPorAdb = false))
        assertEquals(emptyList<Enlace>(), Enlaces.deseados(null, usb, escucharPorUsb = false, escucharPorAdb = false))
    }

    @Test
    fun `sin wifi, el cable basta`() {
        assertEquals(listOf(usb, Enlace.ADB), Enlaces.deseados(null, usb, escucharPorUsb = true, escucharPorAdb = true))
        assertEquals(listOf(Enlace.ADB), Enlaces.deseados(null, null, escucharPorUsb = true, escucharPorAdb = true))
    }

    @Test
    fun `la wifi va primero, que es la que se enseña`() {
        assertEquals(wifi, Enlaces.deseados(wifi, usb, escucharPorUsb = true, escucharPorAdb = true).first())
    }

    // ── El plan ───────────────────────────────────────────────────────────────

    @Test
    fun `sin cambios no se toca nada`() {
        val plan = Enlaces.planificar(setOf(wifi, usb), setOf(wifi, usb), listOf(wifi, usb))
        assertTrue(plan.vacio)
    }

    @Test
    fun `la misma red leída dos veces es el mismo enlace, y no se reata`() {
        val otraVez = enlace(TipoEnlace.WIFI, "192.168.1.50", 24)
        assertEquals(wifi, otraVez)
        assertTrue(Enlaces.planificar(setOf(wifi), setOf(wifi), listOf(otraVez)).vacio)
    }

    @Test
    fun `se para lo que sobra y se arranca lo que falta, sin tocar el resto`() {
        val plan = Enlaces.planificar(setOf(wifi, usb), setOf(wifi, usb), listOf(wifi, Enlace.ADB))
        assertEquals(setOf(usb), plan.parar)
        assertEquals(listOf(Enlace.ADB), plan.arrancar)
    }

    @Test
    fun `un servidor con el socket muerto se para y se vuelve a arrancar`() {
        val plan = Enlaces.planificar(setOf(wifi, usb), setOf(usb), listOf(wifi, usb))
        assertEquals(setOf(wifi), plan.parar)
        assertEquals(listOf(wifi), plan.arrancar)
    }

    @Test
    fun `si la wifi cambia de IP se reata`() {
        val otraWifi = enlace(TipoEnlace.WIFI, "10.0.0.7", 24)
        val plan = Enlaces.planificar(setOf(wifi), setOf(wifi), listOf(otraWifi))
        assertEquals(setOf(wifi), plan.parar)
        assertEquals(listOf(otraWifi), plan.arrancar)
    }

    // ── El filtro de conexiones ───────────────────────────────────────────────

    @Test
    fun `por la wifi, la subred si no hay lista y la lista si la hay`() {
        assertTrue(Enlaces.admitir(ip("192.168.1.20"), wifi, sinLista))
        assertFalse(Enlaces.admitir(ip("10.0.0.20"), wifi, sinLista))
        val lista = ListaDeRedes.leer(listOf("10.0.0.0/8")).first
        assertTrue(Enlaces.admitir(ip("10.0.0.20"), wifi, lista))
        assertFalse(Enlaces.admitir(ip("192.168.1.20"), wifi, lista))
    }

    @Test
    fun `por el anclaje USB, solo la subred del cable, haya lista o no`() {
        val lista = ListaDeRedes.leer(listOf("10.0.0.0/8")).first
        assertTrue(Enlaces.admitir(ip("192.168.42.200"), usb, sinLista))
        assertTrue(Enlaces.admitir(ip("192.168.42.200"), usb, lista))
        assertFalse(Enlaces.admitir(ip("10.0.0.20"), usb, lista))
        assertFalse(Enlaces.admitir(ip("192.168.1.20"), usb, sinLista))
    }

    @Test
    fun `por adb, solo de dentro del teléfono`() {
        val todo = ListaDeRedes.leer(listOf("0.0.0.0/0")).first
        assertTrue(Enlaces.admitir(ip("127.0.0.1"), Enlace.ADB, sinLista))
        assertFalse(Enlaces.admitir(ip("192.168.1.20"), Enlace.ADB, todo))
    }
}
