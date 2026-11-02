// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.servidor

import com.capicua.apitosms.domain.api.ManejadorHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * El ciclo de vida del socket: que [ServidorHttps.escuchando] diga la verdad cuando el socket muere
 * sin [ServidorHttps.detener], que es lo que pasa en el teléfono al caer la wifi.
 */
class ServidorHttpsTest {

    private val caidas = AtomicInteger()
    private val caido = CountDownLatch(1)

    private fun servidor() = ServidorHttps(
        direccion = InetAddress.getLoopbackAddress(),
        puerto = 0,
        sslContext = null,
        manejador = ManejadorHttp { error("no se llama") },
        alCaer = { caidas.incrementAndGet(); caido.countDown() }
    ).also { it.iniciar() }

    /** Lo que hace Android al quitar la IP: cerrar el socket por debajo del servidor. */
    private fun destruirSocket(s: ServidorHttps) {
        val campo = ServidorHttps::class.java.getDeclaredField("socket").apply { isAccessible = true }
        (campo.get(s) as ServerSocket).close()
    }

    @Test
    fun `si el sistema destruye el socket, deja de escuchar y avisa`() {
        val s = servidor()
        assertTrue(s.escuchando)

        destruirSocket(s)

        assertTrue("alCaer no llegó", caido.await(5, TimeUnit.SECONDS))
        assertFalse(s.escuchando)
        assertEquals(1, caidas.get())
        s.detener()
    }

    @Test
    fun `detener no cuenta como caída`() {
        val s = servidor()
        s.detener()

        assertFalse(caido.await(500, TimeUnit.MILLISECONDS))
        assertFalse(s.escuchando)
        assertEquals(0, caidas.get())
    }
}
