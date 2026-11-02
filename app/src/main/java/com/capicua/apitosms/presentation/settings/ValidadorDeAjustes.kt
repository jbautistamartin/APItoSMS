// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.settings

import com.capicua.apitosms.data.config.AppConfig
import com.capicua.apitosms.domain.seguridad.ListaDeRedes
import com.capicua.apitosms.domain.telefono.NormalizadorE164
import com.capicua.apitosms.domain.telefono.PoliticaDeDestinos

/**
 * Valida lo que se escribe en Ajustes antes de guardarlo. Sin Android, para poder probarlo.
 *
 * Los rangos son los mismos con los que [com.capicua.apitosms.data.backup.CopiaDeAjustes] acota
 * una copia importada.
 */
object ValidadorDeAjustes {

    private val PREFIJO_PAIS = Regex("""\+[1-9]\d{0,3}""")

    /** Un prefijo de destino puede ser largo: `+52900` bloquea los números de tarificación. */
    private val PREFIJO_DESTINO = Regex("""\+\d{1,15}""")

    /** Los campos tal cual se escriben: texto. */
    data class Campos(
        val puerto: String,
        val prefijoPais: String,
        val pausaEntreMensajes: String,
        val maxIntentos: String,
        val intervaloReintento: String,
        val timeoutEnvio: String,
        val cuotaDiaria: String,
        val maxPartes: String,
        val maxLote: String,
        val prefijosPermitidos: String,
        val prefijosBloqueados: String,
        val redesPermitidas: String,
        val informesDeEntrega: Boolean,
        val retencionTextos: String,
        val retencionMensajes: String,
        val retencionRegistro: String,
        val subscriptionId: Int
    )

    sealed interface Resultado {
        data class Valido(val config: AppConfig) : Resultado
        data class Invalido(val errores: List<String>) : Resultado
    }

    fun validar(c: Campos, actual: AppConfig): Resultado {
        val errores = mutableListOf<String>()

        fun entero(texto: String, nombre: String, rango: IntRange): Int {
            val n = texto.trim().toIntOrNull()
            if (n == null || n !in rango) {
                errores += "$nombre: un número entre ${rango.first} y ${rango.last}."
                return rango.first
            }
            return n
        }

        val puerto = entero(c.puerto, "Puerto", 1024..65535)
        val pausa = entero(c.pausaEntreMensajes, "Pausa entre mensajes", 0..3600)
        val intentos = entero(c.maxIntentos, "Intentos", 1..50)
        val intervalo = entero(c.intervaloReintento, "Espera entre reintentos", 5..3600)
        val timeout = entero(c.timeoutEnvio, "Tiempo de espera del envío", 10..600)
        val cuota = entero(c.cuotaDiaria, "Cuota diaria", 0..1_000_000)
        val partes = entero(c.maxPartes, "Partes por mensaje", 1..10)
        val lote = entero(c.maxLote, "Mensajes por lote", 1..1000)
        val retTextos = entero(c.retencionTextos, "Días con el texto", 1..3650)
        val retMensajes = entero(c.retencionMensajes, "Días con el mensaje", 1..3650)
        val retRegistro = entero(c.retencionRegistro, "Días de registro", 1..3650)

        if (retMensajes < retTextos) {
            errores += "El mensaje no puede borrarse antes que su texto: los días con el mensaje tienen que ser al menos los días con el texto."
        }

        val prefijoPais = NormalizadorE164.normalizarPrefijo(c.prefijoPais)
        if (prefijoPais.isNotEmpty() && !PREFIJO_PAIS.matches(prefijoPais)) {
            errores += "Prefijo del país: de 1 a 4 cifras, como +34 o +52."
        }

        val permitidos = PoliticaDeDestinos.leerLista(c.prefijosPermitidos)
        val bloqueados = PoliticaDeDestinos.leerLista(c.prefijosBloqueados)
        val malos = (permitidos + bloqueados).filter { !PREFIJO_DESTINO.matches(NormalizadorE164.normalizarPrefijo(it)) }
        if (malos.isNotEmpty()) errores += "Prefijos que no se entienden: ${malos.joinToString()}"

        val (_, redesMalas) = ListaDeRedes.leer(ListaDeRedes.separar(c.redesPermitidas))
        if (redesMalas.isNotEmpty()) errores += "No son IP ni redes CIDR: ${redesMalas.joinToString()}"

        if (errores.isNotEmpty()) return Resultado.Invalido(errores)

        return Resultado.Valido(actual.copy(
            puerto = puerto,
            subscriptionId = c.subscriptionId,
            pausaEntreMensajesSegundos = pausa,
            maxIntentos = intentos,
            intervaloReintentoSegundos = intervalo,
            timeoutEnvioSegundos = timeout,
            cuotaDiariaGlobal = cuota,
            maxPartesPorMensaje = partes,
            maxMensajesPorLote = lote,
            prefijoPais = prefijoPais,
            prefijosPermitidos = c.prefijosPermitidos.trim(),
            prefijosBloqueados = c.prefijosBloqueados.trim(),
            redesPermitidas = c.redesPermitidas.trim(),
            informesDeEntrega = c.informesDeEntrega,
            retencionTextosDias = retTextos,
            retencionMensajesDias = retMensajes,
            retencionRegistroDias = retRegistro
        ))
    }
}
