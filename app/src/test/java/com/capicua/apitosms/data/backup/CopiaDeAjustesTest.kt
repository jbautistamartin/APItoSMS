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
        val futura = CopiaDeAjustes.exportar(config, emptyList(), ahora).replace("\"version\": 2", "\"version\": 3")
        assertTrue(CopiaDeAjustes.leer(futura, ahora) is CopiaDeAjustes.Lectura.Invalida)
    }

    // ── Con datos confidenciales ──────────────────────────────────────────────

    private val revocada = clave.copy(
        id = "c2", nombre = "Viejo", hash = SecretoDeClave.hash(SecretoDeClave.generar()), revocadaEn = ahora
    )

    @Test
    fun `con datos confidenciales las claves llegan como estaban, con su hash`() {
        val texto = CopiaDeAjustes.exportar(config, listOf(clave, revocada), ahora, CopiaDeAjustes.Confidencial())
        assertFalse("el secreto no está en ningún sitio", texto.contains(secreto))
        val lectura = CopiaDeAjustes.leer(texto, ahora) as CopiaDeAjustes.Lectura.Valida
        assertTrue(lectura.conConfidenciales)
        val (activa, vieja) = lectura.claves
        assertEquals(clave.hash, activa.hash)
        assertEquals(clave.prefijo, activa.prefijo)
        assertTrue(activa.activa)
        assertEquals(revocada.hash, vieja.hash)
        assertFalse(vieja.activa)
        assertEquals(null, lectura.certificado)
    }

    @Test
    fun `sin datos confidenciales la lectura lo dice`() {
        val lectura = CopiaDeAjustes.leer(CopiaDeAjustes.exportar(config, listOf(clave), ahora), ahora)
            as CopiaDeAjustes.Lectura.Valida
        assertFalse(lectura.conConfidenciales)
    }

    @Test
    fun `el p12 y su contrasena viajan tal cual`() {
        val p12 = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x00, 0x7f, 0x00)
        val texto = CopiaDeAjustes.exportar(
            config, emptyList(), ahora, CopiaDeAjustes.Confidencial(p12, "clave ñ".toCharArray())
        )
        val lectura = CopiaDeAjustes.leer(texto, ahora) as CopiaDeAjustes.Lectura.Valida
        val certificado = lectura.certificado!!
        assertTrue(p12.contentEquals(certificado.p12))
        assertEquals("clave ñ", String(certificado.contrasena))
        assertTrue(lectura.conConfidenciales)
    }

    @Test
    fun `un hash sin forma de SHA-256 no se acepta y la clave llega revocada`() {
        val texto = CopiaDeAjustes.exportar(config, listOf(clave), ahora, CopiaDeAjustes.Confidencial())
            .replace(clave.hash, "no-es-un-hash")
        val importada = (CopiaDeAjustes.leer(texto, ahora) as CopiaDeAjustes.Lectura.Valida).claves.single()
        assertFalse(importada.activa)
        assertNotEquals("no-es-un-hash", importada.hash)
    }

    @Test
    fun `una copia del formato 1 se sigue leyendo`() {
        val texto = CopiaDeAjustes.exportar(config, listOf(clave), ahora).replace("\"version\": 2", "\"version\": 1")
        val lectura = CopiaDeAjustes.leer(texto, ahora) as CopiaDeAjustes.Lectura.Valida
        assertFalse(lectura.claves.single().activa)
    }

    @Test
    fun `un certificado mal codificado invalida la copia`() {
        val texto = CopiaDeAjustes.exportar(
            config, emptyList(), ahora, CopiaDeAjustes.Confidencial(byteArrayOf(1, 2, 3), "x".toCharArray())
        ).replace("\"p12\": \"AQID\"", "\"p12\": \"no es base64!\"")
        assertTrue(CopiaDeAjustes.leer(texto, ahora) is CopiaDeAjustes.Lectura.Invalida)
    }
}
