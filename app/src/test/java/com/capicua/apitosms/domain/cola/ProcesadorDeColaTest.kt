// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.cola

import com.capicua.apitosms.AlmacenEnMemoria
import com.capicua.apitosms.EnviadorFalso
import com.capicua.apitosms.RegistroEnMemoria
import com.capicua.apitosms.RelojFijo
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.MensajeSaliente
import com.capicua.apitosms.domain.transport.ResultadoEnvio
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcesadorDeColaTest {

    private val reloj = RelojFijo()
    private val almacen = AlmacenEnMemoria()
    private val registro = RegistroEnMemoria()
    private var ajustes = AjustesDeCola(maxIntentos = 3, intervaloReintentoSegundos = 30)

    private fun procesador(enviador: EnviadorFalso) =
        ProcesadorDeCola(almacen, enviador, { ajustes }, registro, reloj)

    private suspend fun encolar(id: String, segundosAntes: Long = 0, texto: String? = "hola", caduca: Long? = null): MensajeSaliente {
        val admitido = reloj.ahora().minusSeconds(segundosAntes)
        val m = MensajeSaliente(
            id = id, destino = "+34600000001", texto = texto, claveId = "k", claveNombre = "ERP",
            admitidoEn = admitido, caducaEn = caduca?.let { reloj.ahora().plusSeconds(it) }
        )
        almacen.insertarTodos(listOf(m))
        return m
    }

    @Test
    fun `manda por orden de llegada y deja el mensaje ENVIADO`() = runTest {
        encolar("b", segundosAntes = 5)
        encolar("a", segundosAntes = 10)
        val enviador = EnviadorFalso()
        val p = procesador(enviador)

        val primero = p.procesarSiguiente() as ProcesadorDeCola.Paso.Procesado
        assertEquals("a", primero.mensaje.id)
        assertEquals(EstadoMensaje.ENVIADO, almacen.filas["a"]!!.estado)
        assertEquals(1, almacen.filas["a"]!!.intentos)

        val segundo = p.procesarSiguiente() as ProcesadorDeCola.Paso.Procesado
        assertEquals("b", segundo.mensaje.id)
        assertEquals(ProcesadorDeCola.Paso.NadaPendiente, p.procesarSiguiente())
    }

    @Test
    fun `pausada no toca nada`() = runTest {
        encolar("a")
        ajustes = ajustes.copy(pausada = true)
        val enviador = EnviadorFalso()
        assertEquals(ProcesadorDeCola.Paso.Pausada, procesador(enviador).procesarSiguiente())
        assertTrue(enviador.enviados.isEmpty())
    }

    @Test
    fun `sin permiso se bloquea sin gastar intentos`() = runTest {
        encolar("a")
        val enviador = EnviadorFalso().apply { noDisponible = "falta el permiso SEND_SMS" }
        val paso = procesador(enviador).procesarSiguiente()
        assertTrue(paso is ProcesadorDeCola.Paso.Bloqueada)
        assertEquals(EstadoMensaje.EN_COLA, almacen.filas["a"]!!.estado)
        assertEquals(0, almacen.filas["a"]!!.intentos)
    }

    @Test
    fun `error transitorio reprograma con espera lineal y no bloquea la cola`() = runTest {
        encolar("a", segundosAntes = 10)
        encolar("b", segundosAntes = 5)
        val enviador = EnviadorFalso(ResultadoEnvio.ErrorTransitorio("sin cobertura"))
        val p = procesador(enviador)

        p.procesarSiguiente()
        val a = almacen.filas["a"]!!
        assertEquals(EstadoMensaje.EN_COLA, a.estado)
        assertEquals(reloj.ahora().plusSeconds(30), a.proximoIntentoEn)

        // Decisión B: el siguiente sale aunque el primero esté esperando su reintento.
        val siguiente = p.procesarSiguiente() as ProcesadorDeCola.Paso.Procesado
        assertEquals("b", siguiente.mensaje.id)
    }

    @Test
    fun `agotados los intentos queda FALLIDO`() = runTest {
        encolar("a")
        val enviador = EnviadorFalso(porDefecto = ResultadoEnvio.ErrorTransitorio("sin cobertura"))
        val p = procesador(enviador)
        repeat(3) {
            p.procesarSiguiente()
            reloj.avanzar(3600)
        }
        val a = almacen.filas["a"]!!
        assertEquals(EstadoMensaje.FALLIDO, a.estado)
        assertEquals(3, a.intentos)
    }

    @Test
    fun `la espera del medio gana si es mayor`() = runTest {
        encolar("a")
        procesador(EnviadorFalso(ResultadoEnvio.ErrorTransitorio("ocupado", esperaSegundos = 300))).procesarSiguiente()
        assertEquals(reloj.ahora().plusSeconds(300), almacen.filas["a"]!!.proximoIntentoEn)
    }

    @Test
    fun `error permanente queda FALLIDO al primer intento`() = runTest {
        encolar("a")
        procesador(EnviadorFalso(ResultadoEnvio.ErrorPermanente("número inválido"))).procesarSiguiente()
        assertEquals(EstadoMensaje.FALLIDO, almacen.filas["a"]!!.estado)
    }

    @Test
    fun `caducado no se envía`() = runTest {
        encolar("a", caduca = 60)
        reloj.avanzar(61)
        val enviador = EnviadorFalso()
        procesador(enviador).procesarSiguiente()
        assertEquals(EstadoMensaje.CADUCADO, almacen.filas["a"]!!.estado)
        assertTrue(enviador.enviados.isEmpty())
    }

    @Test
    fun `sin texto queda FALLIDO sin enviar`() = runTest {
        encolar("a", texto = null)
        val enviador = EnviadorFalso()
        procesador(enviador).procesarSiguiente()
        assertEquals(EstadoMensaje.FALLIDO, almacen.filas["a"]!!.estado)
        assertTrue(enviador.enviados.isEmpty())
    }

    @Test
    fun `una excepción del medio cuenta como transitoria`() = runTest {
        encolar("a")
        val enviador = object : com.capicua.apitosms.domain.transport.EnviadorDeMensajes {
            override suspend fun enviar(destino: String, texto: String, timeoutSegundos: Int, seguimiento: String?): ResultadoEnvio =
                throw IllegalStateException("radio apagada")
            override fun contarPartes(texto: String) = 1
            override suspend fun motivoNoDisponible(): String? = null
        }
        ProcesadorDeCola(almacen, enviador, { ajustes }, registro, reloj).procesarSiguiente()
        val a = almacen.filas["a"]!!
        assertEquals(EstadoMensaje.EN_COLA, a.estado)
        assertTrue(a.ultimoError!!.contains("radio apagada"))
    }

    @Test
    fun `rescatar devuelve a la cola los ENVIANDO`() = runTest {
        encolar("a")
        almacen.reclamar("a", reloj.ahora())
        assertEquals(1, procesador(EnviadorFalso()).rescatarHuerfanos())
        val a = almacen.filas["a"]!!
        assertEquals(EstadoMensaje.EN_COLA, a.estado)
        assertEquals(ProcesadorDeCola.MOTIVO_RESCATE, a.ultimoError)
    }

    @Test
    fun `la entrega se cuenta por partes`() {
        val ahora = reloj.ahora()
        val enviado = MensajeSaliente(
            id = "a", destino = "+34600000001", texto = "x", claveId = null, claveNombre = null,
            estado = EstadoMensaje.ENVIADO, partes = 2, admitidoEn = ahora
        )
        val una = ProcesadorDeCola.aplicarEntrega(enviado, true, ahora)!!
        assertEquals(EstadoMensaje.ENVIADO, una.estado)
        assertEquals(1, una.partesEntregadas)
        val dos = ProcesadorDeCola.aplicarEntrega(una, true, ahora)!!
        assertEquals(EstadoMensaje.ENTREGADO, dos.estado)

        assertEquals(EstadoMensaje.NO_ENTREGADO, ProcesadorDeCola.aplicarEntrega(enviado, false, ahora)!!.estado)
        assertNull(ProcesadorDeCola.aplicarEntrega(dos, true, ahora))
    }
}
