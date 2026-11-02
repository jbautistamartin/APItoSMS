// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.backup

import com.capicua.apitosms.data.config.AppConfig
import com.capicua.apitosms.domain.model.Ambito
import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.domain.seguridad.SecretoDeClave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CopiaDeAjustesTest {

    private val ahora = Instant.parse("2026-11-01T18:00:00Z")
    private val secreto = SecretoDeClave.generar()
    private val clave = ClaveApi(
        id = "c1", nombre = "ERP", prefijo = secreto.take(8), hash = SecretoDeClave.hash(secreto),
        ambitos = setOf(Ambito.ENVIAR, Ambito.CONSULTAR), redesPermitidas = listOf("10.0.0.0/8"),
        cuotaDiaria = 200, peticionesPorMinuto = 30, creadaEn = ahora
    )
    private val config = AppConfig(
        servidorActivo = true, colaPausada = true, puerto = 9443, prefijoPais = "+52",
        cuotaDiariaGlobal = 500, retencionTextosDias = 7, retencionMensajesDias = 30
    )

    @Test
    fun `la copia no lleva ni el secreto ni su hash`() {
        val texto = CopiaDeAjustes.exportar(config, listOf(clave), ahora)
        assertFalse(texto.contains(secreto))
        assertFalse(texto.contains(clave.hash))
        assertFalse(texto.contains(clave.prefijo))
    }

    @Test
    fun `ida y vuelta conserva los ajustes y no lo que no viaja`() {
        val lectura = CopiaDeAjustes.leer(CopiaDeAjustes.exportar(config, listOf(clave), ahora), ahora)
            as CopiaDeAjustes.Lectura.Valida
        val destino = lectura.config(AppConfig())
        assertEquals(9443, destino.puerto)
        assertEquals("+52", destino.prefijoPais)
        assertEquals(500, destino.cuotaDiariaGlobal)
        assertEquals(30, destino.retencionMensajesDias)
        // El interruptor y la pausa son del teléfono, no de la copia.
        assertFalse(destino.servidorActivo)
        assertFalse(destino.colaPausada)
    }

    @Test
    fun `las claves llegan revocadas y con un hash que no es el de ningun secreto conocido`() {
        val lectura = CopiaDeAjustes.leer(CopiaDeAjustes.exportar(config, listOf(clave), ahora), ahora)
            as CopiaDeAjustes.Lectura.Valida
        val importada = lectura.claves.single()
        assertEquals("ERP", importada.nombre)
        assertEquals(clave.ambitos, importada.ambitos)
        assertEquals(clave.redesPermitidas, importada.redesPermitidas)
        assertEquals(200, importada.cuotaDiaria)
        assertFalse(importada.activa)
        assertNotEquals(clave.hash, importada.hash)
        assertNotEquals(clave.id, importada.id)
    }

    @Test
    fun `los valores fuera de rango se acotan como en Ajustes`() {
        val texto = CopiaDeAjustes.exportar(config, emptyList(), ahora)
            .replace("\"retencionTextosDias\": 7", "\"retencionTextosDias\": 99999")
            .replace("\"retencionMensajesDias\": 30", "\"retencionMensajesDias\": 3")
            .replace("\"cuotaDiariaGlobal\": 500", "\"cuotaDiariaGlobal\": 2000000000")
        val destino = (CopiaDeAjustes.leer(texto, ahora) as CopiaDeAjustes.Lectura.Valida).config(AppConfig())
        assertEquals(3650, destino.retencionTextosDias)
        assertEquals(3650, destino.retencionMensajesDias) // nunca antes que el texto
        assertEquals(1_000_000, destino.cuotaDiariaGlobal)
    }

    @Test
    fun `lo que no es una copia, o es de una version futura, se rechaza`() {
        assertTrue(CopiaDeAjustes.leer("no es json", ahora) is CopiaDeAjustes.Lectura.Invalida)
        val otra = CopiaDeAjustes.exportar(config, emptyList(), ahora).replace("apitosms-ajustes", "smsrelay")
        assertTrue(CopiaDeAjustes.leer(otra, ahora) is CopiaDeAjustes.Lectura.Invalida)
        val futura = CopiaDeAjustes.exportar(config, emptyList(), ahora).replace("\"version\": 1", "\"version\": 2")
        assertTrue(CopiaDeAjustes.leer(futura, ahora) is CopiaDeAjustes.Lectura.Invalida)
    }
}
