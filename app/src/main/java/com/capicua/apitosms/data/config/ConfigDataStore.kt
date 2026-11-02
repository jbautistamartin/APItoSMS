// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper sobre DataStore<Preferences> para los ajustes.
 *
 * - Las lecturas son reactivas (Flow).
 * - Las escrituras son atómicas (DataStore.edit usa transacción).
 * - Los valores por defecto están declarados una sola vez, en [AppConfig].
 *
 * Los interruptores que se aplican al momento —el servidor, la pausa de la cola, el origen del
 * certificado— tienen su propia función y escriben **una sola clave**, para no guardar de rebote
 * los campos a medio editar de la pantalla de Ajustes.
 */
@Singleton
class ConfigDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val config: Flow<AppConfig> = dataStore.data.map { p ->
        val d = AppConfig()
        AppConfig(
            servidorActivo = p[Keys.SERVIDOR_ACTIVO] ?: d.servidorActivo,
            puerto = p[Keys.PUERTO] ?: d.puerto,
            subscriptionId = p[Keys.SUBSCRIPTION_ID] ?: d.subscriptionId,
            colaPausada = p[Keys.COLA_PAUSADA] ?: d.colaPausada,
            pausaEntreMensajesSegundos = p[Keys.PAUSA_ENTRE_MENSAJES] ?: d.pausaEntreMensajesSegundos,
            maxIntentos = p[Keys.MAX_INTENTOS] ?: d.maxIntentos,
            intervaloReintentoSegundos = p[Keys.INTERVALO_REINTENTO] ?: d.intervaloReintentoSegundos,
            timeoutEnvioSegundos = p[Keys.TIMEOUT_ENVIO] ?: d.timeoutEnvioSegundos,
            cuotaDiariaGlobal = p[Keys.CUOTA_DIARIA] ?: d.cuotaDiariaGlobal,
            maxPartesPorMensaje = p[Keys.MAX_PARTES] ?: d.maxPartesPorMensaje,
            maxMensajesPorLote = p[Keys.MAX_LOTE] ?: d.maxMensajesPorLote,
            prefijoPais = p[Keys.PREFIJO_PAIS] ?: d.prefijoPais,
            prefijosPermitidos = p[Keys.PREFIJOS_PERMITIDOS] ?: d.prefijosPermitidos,
            prefijosBloqueados = p[Keys.PREFIJOS_BLOQUEADOS] ?: d.prefijosBloqueados,
            redesPermitidas = p[Keys.REDES_PERMITIDAS] ?: d.redesPermitidas,
            escucharPorUsb = p[Keys.ESCUCHAR_POR_USB] ?: d.escucharPorUsb,
            escucharPorAdb = p[Keys.ESCUCHAR_POR_ADB] ?: d.escucharPorAdb,
            informesDeEntrega = p[Keys.INFORMES_ENTREGA] ?: d.informesDeEntrega,
            retencionTextosDias = p[Keys.RETENCION_TEXTOS] ?: d.retencionTextosDias,
            retencionMensajesDias = p[Keys.RETENCION_MENSAJES] ?: d.retencionMensajesDias,
            retencionRegistroDias = p[Keys.RETENCION_REGISTRO] ?: d.retencionRegistroDias,
            origenCertificado = p[Keys.ORIGEN_CERTIFICADO]
        )
    }

    /**
     * Guarda los ajustes editables de la pantalla. No toca el servidor, la pausa ni el certificado,
     * que tienen sus propios interruptores.
     */
    suspend fun guardar(c: AppConfig) {
        dataStore.edit { p ->
            p[Keys.PUERTO] = c.puerto
            p[Keys.SUBSCRIPTION_ID] = c.subscriptionId
            p[Keys.PAUSA_ENTRE_MENSAJES] = c.pausaEntreMensajesSegundos
            p[Keys.MAX_INTENTOS] = c.maxIntentos
            p[Keys.INTERVALO_REINTENTO] = c.intervaloReintentoSegundos
            p[Keys.TIMEOUT_ENVIO] = c.timeoutEnvioSegundos
            p[Keys.CUOTA_DIARIA] = c.cuotaDiariaGlobal
            p[Keys.MAX_PARTES] = c.maxPartesPorMensaje
            p[Keys.MAX_LOTE] = c.maxMensajesPorLote
            p[Keys.PREFIJO_PAIS] = c.prefijoPais
            p[Keys.PREFIJOS_PERMITIDOS] = c.prefijosPermitidos
            p[Keys.PREFIJOS_BLOQUEADOS] = c.prefijosBloqueados
            p[Keys.REDES_PERMITIDAS] = c.redesPermitidas
            p[Keys.ESCUCHAR_POR_USB] = c.escucharPorUsb
            p[Keys.ESCUCHAR_POR_ADB] = c.escucharPorAdb
            p[Keys.INFORMES_ENTREGA] = c.informesDeEntrega
            p[Keys.RETENCION_TEXTOS] = c.retencionTextosDias
            p[Keys.RETENCION_MENSAJES] = c.retencionMensajesDias
            p[Keys.RETENCION_REGISTRO] = c.retencionRegistroDias
        }
    }

    suspend fun cambiarServidor(activo: Boolean) {
        dataStore.edit { it[Keys.SERVIDOR_ACTIVO] = activo }
    }

    suspend fun cambiarPausa(pausada: Boolean) {
        dataStore.edit { it[Keys.COLA_PAUSADA] = pausada }
    }

    suspend fun cambiarOrigenCertificado(origen: String) {
        dataStore.edit { it[Keys.ORIGEN_CERTIFICADO] = origen }
    }

    private object Keys {
        val SERVIDOR_ACTIVO = booleanPreferencesKey("servidor_activo")
        val PUERTO = intPreferencesKey("puerto")
        val SUBSCRIPTION_ID = intPreferencesKey("subscription_id")
        val COLA_PAUSADA = booleanPreferencesKey("cola_pausada")
        val PAUSA_ENTRE_MENSAJES = intPreferencesKey("pausa_entre_mensajes_segundos")
        val MAX_INTENTOS = intPreferencesKey("max_intentos")
        val INTERVALO_REINTENTO = intPreferencesKey("intervalo_reintento")
        val TIMEOUT_ENVIO = intPreferencesKey("timeout_envio_segundos")
        val CUOTA_DIARIA = intPreferencesKey("cuota_diaria_global")
        val MAX_PARTES = intPreferencesKey("max_partes_por_mensaje")
        val MAX_LOTE = intPreferencesKey("max_mensajes_por_lote")
        val PREFIJO_PAIS = stringPreferencesKey("prefijo_pais")
        val PREFIJOS_PERMITIDOS = stringPreferencesKey("prefijos_permitidos")
        val PREFIJOS_BLOQUEADOS = stringPreferencesKey("prefijos_bloqueados")
        val REDES_PERMITIDAS = stringPreferencesKey("redes_permitidas")
        val ESCUCHAR_POR_USB = booleanPreferencesKey("escuchar_por_usb")
        val ESCUCHAR_POR_ADB = booleanPreferencesKey("escuchar_por_adb")
        val INFORMES_ENTREGA = booleanPreferencesKey("informes_entrega")
        val RETENCION_TEXTOS = intPreferencesKey("retencion_textos_dias")
        val RETENCION_MENSAJES = intPreferencesKey("retencion_mensajes_dias")
        val RETENCION_REGISTRO = intPreferencesKey("retencion_registro_dias")
        val ORIGEN_CERTIFICADO = stringPreferencesKey("origen_certificado")
    }
}
