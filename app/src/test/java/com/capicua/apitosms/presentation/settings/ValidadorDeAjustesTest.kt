// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.settings

import com.capicua.apitosms.data.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidadorDeAjustesTest {

    private val buenos = ValidadorDeAjustes.Campos(
        puerto = "8443", prefijoPais = "+52", pausaEntreMensajes = "3", maxIntentos = "5",
        intervaloReintento = "30", timeoutEnvio = "60", cuotaDiaria = "0", maxPartes = "3",
        maxLote = "100", prefijosPermitidos = "+52", prefijosBloqueados = "+52900",
        redesPermitidas = "192.168.1.0/24, 10.0.0.5", informesDeEntrega = true,
        retencionTextos = "7", retencionMensajes = "30", retencionRegistro = "30", subscriptionId = -1
    )

    private fun errores(c: ValidadorDeAjustes.Campos) =
        (ValidadorDeAjustes.validar(c, AppConfig()) as ValidadorDeAjustes.Resultado.Invalido).errores

    @Test
    fun `unos ajustes buenos se aplican sobre los actuales`() {
        val actual = AppConfig(servidorActivo = true)
        val r = ValidadorDeAjustes.validar(buenos.copy(puerto = " 9443 "), actual) as ValidadorDeAjustes.Resultado.Valido
        assertEquals(9443, r.config.puerto)
        assertEquals("+52", r.config.prefijoPais)
        assertEquals("192.168.1.0/24, 10.0.0.5", r.config.redesPermitidas)
        assertTrue(r.config.servidorActivo) // lo que no está en el formulario se conserva
    }

    @Test
    fun `los numeros fuera de rango dan un error cada uno`() {
        assertEquals(3, errores(buenos.copy(puerto = "80", maxPartes = "11", maxLote = "abc")).size)
    }

    @Test
    fun `el mensaje no puede borrarse antes que su texto`() {
        assertEquals(1, errores(buenos.copy(retencionTextos = "30", retencionMensajes = "7")).size)
    }

    @Test
    fun `prefijos y redes que no se entienden`() {
        assertEquals(1, errores(buenos.copy(prefijoPais = "+05")).size)
        assertEquals(1, errores(buenos.copy(prefijosBloqueados = "+52900, hola")).size)
        assertEquals(1, errores(buenos.copy(redesPermitidas = "192.168.1.0/33")).size)
    }

    @Test
    fun `el prefijo del pais puede quedar vacio`() {
        assertTrue(ValidadorDeAjustes.validar(buenos.copy(prefijoPais = ""), AppConfig()) is ValidadorDeAjustes.Resultado.Valido)
    }
}
