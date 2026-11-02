// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.backup

import com.capicua.apitosms.data.config.AppConfig
import com.capicua.apitosms.domain.model.Ambito
import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.domain.seguridad.SecretoDeClave
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * La copia de seguridad de la configuración: un JSON para montar un teléfono de repuesto igual
 * que el que se estropeó.
 *
 * Lleva los ajustes y **la lista de claves sin secretos**. Los secretos no están en ningún sitio
 * —solo su hash, y el hash tampoco viaja—, así que las claves llegan al teléfono nuevo
 * **revocadas**: con su nombre, sus permisos, sus redes y sus límites, listas para regenerar una
 * a una y repartir el secreto nuevo a cada sistema. Es lo que se quiere: una copia que circula
 * por correo no debe poder mandar SMS.
 *
 * No viajan: el interruptor del servidor, la pausa de la cola ni el certificado. El certificado
 * generado vive en `AndroidKeyStore` y no se puede exportar; el importado, quien lo tiene es
 * quien lo generó.
 *
 * El texto de los mensajes y el registro no forman parte de la copia.
 */
object CopiaDeAjustes {

    const val FORMATO = "apitosms-ajustes"
    const val VERSION = 1

    @Serializable
    data class Copia(
        val formato: String = FORMATO,
        val version: Int = VERSION,
        @SerialName("exportadaEn") val exportadaEn: String,
        val ajustes: Ajustes,
        val claves: List<Clave> = emptyList()
    )

    @Serializable
    data class Ajustes(
        val puerto: Int,
        val pausaEntreMensajesSegundos: Int,
        val maxIntentos: Int,
        val intervaloReintentoSegundos: Int,
        val timeoutEnvioSegundos: Int,
        val cuotaDiariaGlobal: Int,
        val maxPartesPorMensaje: Int,
        val maxMensajesPorLote: Int,
        val prefijoPais: String,
        val prefijosPermitidos: String,
        val prefijosBloqueados: String,
        val redesPermitidas: String,
        val informesDeEntrega: Boolean,
        val retencionTextosDias: Int,
        val retencionMensajesDias: Int,
        val retencionRegistroDias: Int
    )

    @Serializable
    data class Clave(
        val nombre: String,
        val ambitos: List<String>,
        val redesPermitidas: List<String> = emptyList(),
        val cuotaDiaria: Int = 0,
        val peticionesPorMinuto: Int = ClaveApi.PETICIONES_POR_MINUTO_POR_DEFECTO
    )

    sealed interface Lectura {
        data class Valida(val config: (AppConfig) -> AppConfig, val claves: List<ClaveApi>) : Lectura
        data class Invalida(val motivo: String) : Lectura
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportar(config: AppConfig, claves: List<ClaveApi>, ahora: Instant = Instant.now()): String =
        json.encodeToString(Copia.serializer(), Copia(
            exportadaEn = ahora.toString(),
            ajustes = Ajustes(
                puerto = config.puerto,
                pausaEntreMensajesSegundos = config.pausaEntreMensajesSegundos,
                maxIntentos = config.maxIntentos,
                intervaloReintentoSegundos = config.intervaloReintentoSegundos,
                timeoutEnvioSegundos = config.timeoutEnvioSegundos,
                cuotaDiariaGlobal = config.cuotaDiariaGlobal,
                maxPartesPorMensaje = config.maxPartesPorMensaje,
                maxMensajesPorLote = config.maxMensajesPorLote,
                prefijoPais = config.prefijoPais,
                prefijosPermitidos = config.prefijosPermitidos,
                prefijosBloqueados = config.prefijosBloqueados,
                redesPermitidas = config.redesPermitidas,
                informesDeEntrega = config.informesDeEntrega,
                retencionTextosDias = config.retencionTextosDias,
                retencionMensajesDias = config.retencionMensajesDias,
                retencionRegistroDias = config.retencionRegistroDias
            ),
            claves = claves.map { c ->
                Clave(c.nombre, c.ambitos.map { it.name }.sorted(), c.redesPermitidas, c.cuotaDiaria, c.peticionesPorMinuto)
            }
        ))

    /**
     * Lee una copia. Devuelve una función que aplica los ajustes sobre la configuración actual
     * —para conservar lo que no viaja— y las claves ya construidas, revocadas y con un hash que
     * no corresponde a ningún secreto conocido.
     */
    fun leer(texto: String, ahora: Instant = Instant.now()): Lectura {
        val copia = try {
            json.decodeFromString(Copia.serializer(), texto)
        } catch (e: Exception) {
            return Lectura.Invalida("No es una copia de APItoSMS: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        if (copia.formato != FORMATO) return Lectura.Invalida("El fichero no es una copia de ajustes de APItoSMS.")
        if (copia.version > VERSION) {
            return Lectura.Invalida("La copia es de una versión más nueva de APItoSMS (formato ${copia.version}). Actualiza antes de importarla.")
        }

        val a = copia.ajustes
        if (a.puerto !in 1024..65535) return Lectura.Invalida("Puerto fuera de rango en la copia: ${a.puerto}.")

        val claves = copia.claves.mapNotNull { c ->
            val ambitos = c.ambitos.mapNotNull(Ambito::de).toSet()
            if (c.nombre.isBlank() || ambitos.isEmpty()) return@mapNotNull null
            // Un secreto al azar que nadie conoce: la clave no autentica hasta que se regenere.
            val inutil = SecretoDeClave.generar()
            ClaveApi(
                id = UUID.randomUUID().toString(),
                nombre = c.nombre.trim().take(60),
                prefijo = SecretoDeClave.PREFIJO + "····",
                hash = SecretoDeClave.hash(inutil),
                ambitos = ambitos,
                redesPermitidas = c.redesPermitidas,
                cuotaDiaria = c.cuotaDiaria.coerceAtLeast(0),
                peticionesPorMinuto = c.peticionesPorMinuto.coerceIn(1, 6000),
                creadaEn = ahora,
                revocadaEn = ahora
            )
        }

        return Lectura.Valida(
            config = { actual ->
                actual.copy(
                    puerto = a.puerto,
                    pausaEntreMensajesSegundos = a.pausaEntreMensajesSegundos.coerceIn(0, 3600),
                    maxIntentos = a.maxIntentos.coerceIn(1, 50),
                    intervaloReintentoSegundos = a.intervaloReintentoSegundos.coerceIn(5, 3600),
                    timeoutEnvioSegundos = a.timeoutEnvioSegundos.coerceIn(10, 600),
                    cuotaDiariaGlobal = a.cuotaDiariaGlobal.coerceAtLeast(0),
                    maxPartesPorMensaje = a.maxPartesPorMensaje.coerceIn(1, 10),
                    maxMensajesPorLote = a.maxMensajesPorLote.coerceIn(1, 1000),
                    prefijoPais = a.prefijoPais,
                    prefijosPermitidos = a.prefijosPermitidos,
                    prefijosBloqueados = a.prefijosBloqueados,
                    redesPermitidas = a.redesPermitidas,
                    informesDeEntrega = a.informesDeEntrega,
                    retencionTextosDias = a.retencionTextosDias.coerceAtLeast(1),
                    retencionMensajesDias = a.retencionMensajesDias.coerceAtLeast(1),
                    retencionRegistroDias = a.retencionRegistroDias.coerceAtLeast(1)
                )
            },
            claves = claves
        )
    }
}
