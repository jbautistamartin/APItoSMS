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
import java.util.Base64
import java.util.UUID

/**
 * La copia de seguridad de la configuración: un JSON para montar un teléfono de repuesto igual
 * que el que se estropeó.
 *
 * Lleva los ajustes y la lista de claves, y **los datos confidenciales solo si quien exporta lo
 * pide** (la casilla de Ajustes, como «Incluir las credenciales» en SMSRelay):
 *
 * - **Sin ellos**, las claves llegan al teléfono nuevo **revocadas**, con un hash al azar: con su
 *   nombre, sus permisos, sus redes y sus límites, listas para regenerar una a una y repartir el
 *   secreto nuevo a cada sistema. Una copia así puede circular sin poder mandar ningún SMS.
 * - **Con ellos**, viajan el **hash** de cada clave y si estaba activa, y el **`.p12` del
 *   certificado importado** con su contraseña. El teléfono nuevo acepta las mismas claves y
 *   enseña la misma huella, así que los sistemas de la empresa no notan el cambio. El secreto de
 *   las claves sigue sin viajar —no está en ningún sitio—, pero el `.p12` lleva la clave privada
 *   del servidor: el fichero hay que guardarlo como una contraseña.
 *
 * Un certificado **generado** en el teléfono no viaja nunca: su clave nació en `AndroidKeyStore`
 * y de ahí no sale. El teléfono nuevo generará el suyo, con otra huella.
 *
 * No viajan nunca: el interruptor del servidor, la pausa de la cola, la SIM (es de este
 * teléfono), el texto de los mensajes ni el registro.
 */
object CopiaDeAjustes {

    const val FORMATO = "apitosms-ajustes"

    /** 2: los datos confidenciales, opcionales. Una copia 1 se lee como una 2 sin ellos. */
    const val VERSION = 2

    @Serializable
    data class Copia(
        val formato: String = FORMATO,
        val version: Int = VERSION,
        @SerialName("exportadaEn") val exportadaEn: String,
        val ajustes: Ajustes,
        val claves: List<Clave> = emptyList(),
        /** Solo en una copia con datos confidenciales, y si el certificado se importó. */
        val certificado: Certificado? = null
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
        val retencionRegistroDias: Int,
        /** Posteriores a la 1.0.0: una copia de entonces no los trae, y quedan apagados. */
        val escucharPorUsb: Boolean = false,
        val escucharPorAdb: Boolean = false
    )

    @Serializable
    data class Clave(
        val nombre: String,
        val ambitos: List<String>,
        val redesPermitidas: List<String> = emptyList(),
        val cuotaDiaria: Int = 0,
        val peticionesPorMinuto: Int = ClaveApi.PETICIONES_POR_MINUTO_POR_DEFECTO,
        /** Los tres siguientes solo van en una copia con datos confidenciales. */
        val hash: String? = null,
        val prefijo: String? = null,
        val activa: Boolean? = null
    )

    /** El `.p12` tal como se importó, en Base64, y su contraseña. */
    @Serializable
    data class Certificado(val p12: String, val contrasena: String)

    /**
     * Lo confidencial que se exporta. Pasar uno, aunque sea sin certificado, es lo que pone los
     * hashes de las claves en la copia.
     *
     * @property p12 el `.p12` importado, si se conserva; null si no hay ninguno que pueda viajar.
     */
    class Confidencial(val p12: ByteArray? = null, val contrasena: CharArray? = null)

    /** Un certificado sacado de la copia, aún sin validar: eso lo hace `CertificadoDelServidor.leer`. */
    class CertificadoLeido(val p12: ByteArray, val contrasena: CharArray)

    sealed interface Lectura {
        data class Valida(
            val config: (AppConfig) -> AppConfig,
            val claves: List<ClaveApi>,
            val certificado: CertificadoLeido? = null,
            /** La copia traía datos confidenciales: el hash de alguna clave, o el certificado. */
            val conConfidenciales: Boolean = false
        ) : Lectura

        data class Invalida(val motivo: String) : Lectura
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** SHA-256 en hexadecimal y en minúsculas, que es como lo escribe `SecretoDeClave.hash`. */
    private val HASH = Regex("^[0-9a-f]{64}$")

    fun exportar(
        config: AppConfig,
        claves: List<ClaveApi>,
        ahora: Instant = Instant.now(),
        confidencial: Confidencial? = null
    ): String {
        val conConfidenciales = confidencial != null
        return json.encodeToString(Copia.serializer(), Copia(
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
                retencionRegistroDias = config.retencionRegistroDias,
                escucharPorUsb = config.escucharPorUsb,
                escucharPorAdb = config.escucharPorAdb
            ),
            claves = claves.map { c ->
                Clave(
                    nombre = c.nombre,
                    ambitos = c.ambitos.map { it.name }.sorted(),
                    redesPermitidas = c.redesPermitidas,
                    cuotaDiaria = c.cuotaDiaria,
                    peticionesPorMinuto = c.peticionesPorMinuto,
                    hash = c.hash.takeIf { conConfidenciales },
                    prefijo = c.prefijo.takeIf { conConfidenciales },
                    activa = c.activa.takeIf { conConfidenciales }
                )
            },
            certificado = confidencial?.p12?.let { p12 ->
                Certificado(
                    p12 = Base64.getEncoder().encodeToString(p12),
                    contrasena = String(confidencial.contrasena ?: CharArray(0))
                )
            }
        ))
    }

    /**
     * Lee una copia. Devuelve una función que aplica los ajustes sobre la configuración actual
     * —para conservar lo que no viaja—, las claves ya construidas y el certificado, si venía.
     *
     * Una clave sin hash, o con uno que no tiene forma de SHA-256, llega revocada y con un hash
     * que no corresponde a ningún secreto conocido. Con un hash válido llega como estaba: activa
     * o revocada.
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

        val certificado = copia.certificado?.let { c ->
            val bytes = runCatching { Base64.getDecoder().decode(c.p12) }.getOrNull()
                ?: return Lectura.Invalida("El certificado de la copia no está bien codificado.")
            CertificadoLeido(bytes, c.contrasena.toCharArray())
        }

        val claves = copia.claves.mapNotNull { c ->
            val ambitos = c.ambitos.mapNotNull(Ambito::de).toSet()
            if (c.nombre.isBlank() || ambitos.isEmpty()) return@mapNotNull null
            val hash = c.hash?.takeIf { HASH.matches(it) }
            ClaveApi(
                id = UUID.randomUUID().toString(),
                nombre = c.nombre.trim().take(60),
                prefijo = c.prefijo?.takeIf { hash != null && it.startsWith(SecretoDeClave.PREFIJO) }
                    ?.take(SecretoDeClave.CARACTERES_VISIBLES)
                    ?: (SecretoDeClave.PREFIJO + "····"),
                // Sin hash, el de un secreto al azar que nadie conoce: no autentica hasta regenerarla.
                hash = hash ?: SecretoDeClave.hash(SecretoDeClave.generar()),
                ambitos = ambitos,
                redesPermitidas = c.redesPermitidas,
                cuotaDiaria = c.cuotaDiaria.coerceAtLeast(0),
                peticionesPorMinuto = c.peticionesPorMinuto.coerceIn(1, 6000),
                creadaEn = ahora,
                revocadaEn = if (hash != null && c.activa == true) null else ahora
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
                    cuotaDiariaGlobal = a.cuotaDiariaGlobal.coerceIn(0, 1_000_000),
                    maxPartesPorMensaje = a.maxPartesPorMensaje.coerceIn(1, 10),
                    maxMensajesPorLote = a.maxMensajesPorLote.coerceIn(1, 1000),
                    prefijoPais = a.prefijoPais,
                    prefijosPermitidos = a.prefijosPermitidos,
                    prefijosBloqueados = a.prefijosBloqueados,
                    redesPermitidas = a.redesPermitidas,
                    informesDeEntrega = a.informesDeEntrega,
                    retencionTextosDias = a.retencionTextosDias.coerceIn(1, 3650),
                    // Como en Ajustes: el mensaje no se borra antes que su texto.
                    retencionMensajesDias = a.retencionMensajesDias.coerceIn(1, 3650)
                        .coerceAtLeast(a.retencionTextosDias.coerceIn(1, 3650)),
                    retencionRegistroDias = a.retencionRegistroDias.coerceIn(1, 3650),
                    escucharPorUsb = a.escucharPorUsb,
                    escucharPorAdb = a.escucharPorAdb
                )
            },
            claves = claves,
            certificado = certificado,
            conConfidenciales = certificado != null || copia.claves.any { it.hash != null }
        )
    }
}
