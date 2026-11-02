// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la comparación de versiones en que se apoya el aviso de actualización.
 *
 * Es la única pieza del comprobador que se puede probar sin red, y también la única donde un
 * error se nota tarde: si la comparación se equivoca, la aplicación deja de avisar —o avisa de
 * una versión más vieja que la instalada— y nadie se entera hasta que alguien mira a mano.
 */
class VersionPublicadaTest {

    // ── Interpretación de la etiqueta ─────────────────────────────────────────

    @Test
    fun `interpreta la etiqueta con la v de los tags`() {
        assertEquals(VersionPublicada(1, 2, 3), VersionPublicada.interpretar("v1.2.3"))
    }

    @Test
    fun `interpreta la etiqueta sin v`() {
        assertEquals(VersionPublicada(1, 2, 3), VersionPublicada.interpretar("1.2.3"))
    }

    @Test
    fun `rellena con ceros lo que falta`() {
        assertEquals(VersionPublicada(2, 0, 0), VersionPublicada.interpretar("2"))
        assertEquals(VersionPublicada(2, 1, 0), VersionPublicada.interpretar("2.1"))
    }

    @Test
    fun `descarta lo que cuelga detras del guion o del mas`() {
        assertEquals(VersionPublicada(1, 4, 0), VersionPublicada.interpretar("v1.4.0-beta.2"))
        assertEquals(VersionPublicada(1, 4, 0), VersionPublicada.interpretar("1.4.0+a1b2c3"))
    }

    @Test
    fun `no interpreta lo que no es una version`() {
        assertNull(VersionPublicada.interpretar(null))
        assertNull(VersionPublicada.interpretar(""))
        assertNull(VersionPublicada.interpretar("ultima"))
        assertNull(VersionPublicada.interpretar("1.2.3.4"))
        assertNull(VersionPublicada.interpretar("1.x.3"))
    }

    // ── Comparación ───────────────────────────────────────────────────────────

    /**
     * El caso que justifica que exista esta clase: como texto, «1.10.0» es *menor* que «1.9.0»,
     * así que una comparación de cadenas dejaría de avisar en cuanto la *minor* pasara de nueve.
     */
    @Test
    fun `diez es mayor que nueve, que como texto no lo seria`() {
        val diez = VersionPublicada.interpretar("1.10.0")!!
        val nueve = VersionPublicada.interpretar("1.9.0")!!

        assertTrue(diez > nueve)
        assertTrue("1.10.0" < "1.9.0") // la comparación que NO se usa, para que conste
    }

    @Test
    fun `manda la mayor antes que la menor y el parche`() {
        val dosCero = VersionPublicada.interpretar("2.0.0")!!
        val unoNueveNueve = VersionPublicada.interpretar("1.99.99")!!

        assertTrue(dosCero > unoNueveNueve)
    }

    @Test
    fun `el parche decide cuando lo demas empata`() {
        assertTrue(VersionPublicada(1, 1, 2) > VersionPublicada(1, 1, 1))
    }

    @Test
    fun `la misma version no es mas nueva`() {
        val instalada = VersionPublicada.interpretar("1.1.0")!!
        val publicada = VersionPublicada.interpretar("v1.1.0")!!

        assertEquals(instalada, publicada)
        assertTrue(publicada <= instalada)
    }

    /**
     * Un release más viejo que lo instalado no debe avisar de nada: pasa al publicar un parche
     * de una rama anterior cuando ya hay una versión mayor fuera.
     */
    @Test
    fun `una publicada mas vieja que la instalada no supera a la instalada`() {
        val instalada = VersionPublicada.interpretar("1.1.0")!!
        val publicada = VersionPublicada.interpretar("1.0.9")!!

        assertTrue(publicada < instalada)
    }
}
