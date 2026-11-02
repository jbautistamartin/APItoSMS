// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/openapi.yaml` es lo que leen los integradores; [ApiDeMensajes.RUTAS] es lo que el teléfono
 * atiende. Una ruta nueva en un sitio y no en el otro rompe este test.
 *
 * El YAML se lee con expresiones regulares, sin biblioteca: basta con la sangría fija del fichero
 * (dos espacios para la ruta, cuatro para el método).
 */
class ContratoOpenApiTest {

    private val fichero = listOf(File("../docs/openapi.yaml"), File("docs/openapi.yaml")).first { it.exists() }

    private fun rutasDelYaml(): Set<ApiDeMensajes.Ruta> {
        val lineas = fichero.readLines()
        val inicio = lineas.indexOf("paths:")
        val fin = lineas.indexOf("components:")
        assertTrue("openapi.yaml sin paths/components", inicio >= 0 && fin > inicio)

        val rutas = mutableSetOf<ApiDeMensajes.Ruta>()
        var actual: String? = null
        for (linea in lineas.subList(inicio + 1, fin)) {
            RUTA.matchEntire(linea)?.let { actual = it.groupValues[1] }
            METODO.matchEntire(linea)?.let { m ->
                rutas += ApiDeMensajes.Ruta(m.groupValues[1].uppercase(), checkNotNull(actual))
            }
        }
        return rutas
    }

    @Test
    fun `las rutas de openapi yaml son las de la API`() {
        assertEquals(ApiDeMensajes.RUTAS.toSet(), rutasDelYaml())
    }

    @Test
    fun `los estados del yaml son los del enum`() {
        val enumLinea = fichero.readLines().first { it.trim().startsWith("enum: [EN_COLA") }
        val delYaml = enumLinea.substringAfter('[').substringBefore(']').split(',').map { it.trim() }
        assertEquals(com.capicua.apitosms.domain.model.EstadoMensaje.entries.map { it.name }, delYaml)
    }

    private companion object {
        val RUTA = Regex("""^ {2}(/\S+):\s*$""")
        val METODO = Regex("""^ {4}(get|post|put|patch|delete):\s*$""")
    }
}
