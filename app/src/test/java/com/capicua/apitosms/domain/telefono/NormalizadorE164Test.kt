// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.telefono

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizadorE164Test {

    private fun valido(entrada: String, pais: String = "") =
        (NormalizadorE164.normalizar(entrada, pais) as NormalizadorE164.Resultado.Valido).numero

    @Test
    fun `acepta mas y doble cero, con separadores`() {
        assertEquals("+34600123456", valido("+34 600 12 34 56"))
        assertEquals("+34600123456", valido("0034-600-123-456"))
        assertEquals("+5215512345678", valido("+52 (155) 1234-5678"))
    }

    @Test
    fun `sin prefijo usa el del país, y sin país lo rechaza`() {
        assertEquals("+34600123456", valido("600123456", "+34"))
        assertTrue(NormalizadorE164.normalizar("600123456") is NormalizadorE164.Resultado.Invalido)
    }

    @Test
    fun `rechaza lo que no es un número`() {
        listOf("", "+34", "+3460012345678901234", "hola", "+34 600 abc").forEach {
            assertTrue(it, NormalizadorE164.normalizar(it, "+34") is NormalizadorE164.Resultado.Invalido)
        }
    }

    @Test
    fun `el prefijo se normaliza siempre con mas`() {
        assertEquals("+52", NormalizadorE164.normalizarPrefijo("52"))
        assertEquals("+52", NormalizadorE164.normalizarPrefijo("0052"))
        assertEquals("", NormalizadorE164.normalizarPrefijo(" "))
    }

    @Test
    fun `los bloqueados ganan a los permitidos`() {
        val politica = PoliticaDeDestinos(listOf("+52"), listOf("+52900"))
        assertNull(politica.motivoDeRechazo("+5215512345678"))
        assertNotNull(politica.motivoDeRechazo("+52900123456"))
        assertNotNull(politica.motivoDeRechazo("+34600123456"))
        assertNull(PoliticaDeDestinos().motivoDeRechazo("+34600123456"))
    }

    @Test
    fun `la lista no parte por espacios`() {
        assertEquals(listOf("+52 900", "+34"), PoliticaDeDestinos.leerLista("+52 900, +34\n"))
    }
}
