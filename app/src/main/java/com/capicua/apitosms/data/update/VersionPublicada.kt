// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.update

/**
 * Una versión `mayor.menor.parche`, comparable **como números**.
 *
 * Existe por una razón concreta: comparar las versiones como texto da resultados falsos en
 * cuanto un número pasa de nueve —`"1.10.0" < "1.9.0"` alfabéticamente—, y eso en un
 * comprobador de actualizaciones significa dejar de avisar justo cuando empieza a hacer falta.
 *
 * No pretende cubrir todo SemVer: de `1.2.3-beta.1+sha` se queda con `1.2.3`, porque los tags
 * de este repositorio son `vX.Y.Z` y lo que cuelga detrás no decide quién es más nuevo aquí.
 */
data class VersionPublicada(
    val mayor: Int,
    val menor: Int,
    val parche: Int
) : Comparable<VersionPublicada> {

    override fun compareTo(other: VersionPublicada): Int = when {
        mayor != other.mayor -> mayor - other.mayor
        menor != other.menor -> menor - other.menor
        else -> parche - other.parche
    }

    override fun toString(): String = "$mayor.$menor.$parche"

    companion object {
        /**
         * Interpreta `v1.2.3`, `1.2.3`, `1.2` o `1` y devuelve null si no hay por dónde:
         * una etiqueta que no se entiende no puede decidir que hay una versión nueva.
         *
         * Lo que falta se rellena con cero (`1.2` es `1.2.0`), y lo que venga detrás de `-` o
         * `+` se descarta.
         */
        fun interpretar(texto: String?): VersionPublicada? {
            if (texto.isNullOrBlank()) return null

            var limpia = texto.trim()
            if (limpia.startsWith("v", ignoreCase = true)) limpia = limpia.substring(1)

            val corte = limpia.indexOfFirst { it == '-' || it == '+' }
            if (corte > 0) limpia = limpia.substring(0, corte)

            val partes = limpia.split('.')
            if (partes.isEmpty() || partes.size > 3) return null

            val numeros = partes.map { it.toIntOrNull() ?: return null }
            if (numeros.any { it < 0 }) return null

            return VersionPublicada(
                mayor = numeros[0],
                menor = numeros.getOrElse(1) { 0 },
                parche = numeros.getOrElse(2) { 0 }
            )
        }
    }
}
