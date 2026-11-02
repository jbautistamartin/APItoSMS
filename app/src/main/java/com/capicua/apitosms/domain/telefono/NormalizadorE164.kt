// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.telefono

/**
 * Convierte el destino que manda un cliente en un número E.164 (`+523310257136`).
 *
 * Es estricto a propósito. La lista de prefijos permitidos es la defensa principal contra una
 * clave robada que mande SMS a números de tarificación especial, y una lista de prefijos solo
 * sirve si todos los números se escriben igual: `0052…`, `+52 33…` y `(33) 1025…` tienen que
 * acabar siendo el mismo texto antes de compararlos.
 *
 * ## Qué acepta
 * - `+` y de 8 a 15 dígitos (el máximo de E.164). Los separadores habituales —espacios, guiones,
 *   puntos y paréntesis— se quitan.
 * - `00` delante, que es la forma europea de escribir el `+`.
 * - Un número sin prefijo, **solo si** hay un prefijo de país por defecto configurado
 *   ([prefijoPais]). Sin él se rechaza: adivinar el país es justo lo que no debe hacer una
 *   pasarela de una empresa.
 *
 * No acepta números cortos de servicio (`4646`): no tienen forma E.164, y una pasarela de avisos
 * a clientes no los necesita.
 */
object NormalizadorE164 {

    /** Dígitos mínimos tras el `+`. Los números E.164 reales más cortos rondan los 8. */
    const val DIGITOS_MINIMOS = 8

    /** Dígitos máximos tras el `+`, por definición de E.164. */
    const val DIGITOS_MAXIMOS = 15

    private val SEPARADORES = Regex("[\\s\\-.()/]")

    sealed interface Resultado {
        data class Valido(val numero: String) : Resultado
        data class Invalido(val motivo: String) : Resultado
    }

    /**
     * @param prefijoPais prefijo que se antepone a un número sin él, como `+52`. Vacío: no se
     *   antepone nada y el número tiene que venir completo.
     */
    fun normalizar(entrada: String, prefijoPais: String = ""): Resultado {
        val limpio = entrada.trim().replace(SEPARADORES, "")
        if (limpio.isEmpty()) return Resultado.Invalido("el destino está vacío")

        val conMas = when {
            limpio.startsWith("+") -> limpio
            limpio.startsWith("00") -> "+" + limpio.drop(2)
            prefijoPais.isNotBlank() -> normalizarPrefijo(prefijoPais) + limpio
            else -> return Resultado.Invalido(
                "el destino tiene que ir en formato internacional, con + y el prefijo del país"
            )
        }

        val digitos = conMas.drop(1)
        if (digitos.isEmpty() || !digitos.all { it in '0'..'9' }) {
            return Resultado.Invalido("el destino solo puede llevar dígitos tras el +")
        }
        if (digitos.startsWith("0")) {
            return Resultado.Invalido("un prefijo de país no empieza por 0")
        }
        if (digitos.length !in DIGITOS_MINIMOS..DIGITOS_MAXIMOS) {
            return Resultado.Invalido(
                "el destino tiene ${digitos.length} dígitos; E.164 admite de $DIGITOS_MINIMOS a $DIGITOS_MAXIMOS"
            )
        }
        return Resultado.Valido(conMas)
    }

    /** `52`, `+52` y `0052` son el mismo prefijo; se devuelve siempre como `+52`. */
    fun normalizarPrefijo(prefijo: String): String {
        val limpio = prefijo.trim().replace(SEPARADORES, "")
        return when {
            limpio.isEmpty() -> ""
            limpio.startsWith("+") -> limpio
            limpio.startsWith("00") -> "+" + limpio.drop(2)
            else -> "+$limpio"
        }
    }
}

/**
 * La política de destinos: qué prefijos se admiten y cuáles no.
 *
 * Una lista de permitidos vacía no restringe; una de bloqueados se aplica siempre, y gana: un
 * número que empiece por un prefijo permitido y por otro bloqueado más largo (`+52` permitido,
 * `+52900` bloqueado) se rechaza.
 */
data class PoliticaDeDestinos(
    val permitidos: List<String> = emptyList(),
    val bloqueados: List<String> = emptyList()
) {
    private val permitidosNormalizados = permitidos.map(NormalizadorE164::normalizarPrefijo).filter { it.length > 1 }
    private val bloqueadosNormalizados = bloqueados.map(NormalizadorE164::normalizarPrefijo).filter { it.length > 1 }

    /** null si [numeroE164] se admite; si no, el motivo. */
    fun motivoDeRechazo(numeroE164: String): String? {
        bloqueadosNormalizados.firstOrNull { numeroE164.startsWith(it) }?.let {
            return "el prefijo $it está bloqueado en este teléfono"
        }
        if (permitidosNormalizados.isNotEmpty() && permitidosNormalizados.none { numeroE164.startsWith(it) }) {
            return "el destino no empieza por ninguno de los prefijos permitidos (${permitidosNormalizados.joinToString()})"
        }
        return null
    }

    companion object {
        /**
         * Lee una lista escrita a mano, separada por comas, puntos y coma o saltos de línea. Los
         * espacios no separan: `+52 900` es un prefijo, no dos.
         */
        fun leerLista(texto: String): List<String> =
            texto.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
