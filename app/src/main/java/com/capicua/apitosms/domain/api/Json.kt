// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.api

import com.capicua.apitosms.domain.model.MensajeSaliente
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * El JSON de la API, campo a campo. Es el contrato con los clientes: `docs/openapi.yaml` lo
 * describe y `Capicua.ApiToSms.Cliente` (C#) lo consume. Un nombre que cambie aquí rompe a los dos.
 *
 * Los instantes van como texto ISO 8601 en UTC (`2026-11-01T18:30:00Z`).
 */
object JsonApi {
    /**
     * Estricto al leer: **un campo desconocido es un error**. Un cliente que escribe `destinatario`
     * en lugar de `destino` tiene que enterarse con un `422`, no descubrir días después que sus
     * mensajes salían sin referencia.
     */
    val formato = Json {
        ignoreUnknownKeys = false
        // Un campo null no se escribe: `"enviadoEn": null` no dice nada que su ausencia no diga.
        explicitNulls = false
        isLenient = false
    }
}

@Serializable
data class PeticionMensaje(
    val destino: String? = null,
    val texto: String? = null,
    val referencia: String? = null,
    val caducaEn: String? = null
)

@Serializable
data class PeticionLote(
    val mensajes: List<PeticionMensaje>? = null
)

@Serializable
data class MensajeJson(
    val id: String,
    val destino: String,
    /** null cuando la retención lo ha purgado. */
    val texto: String? = null,
    val referencia: String? = null,
    val estado: String,
    val intentos: Int,
    val ultimoError: String? = null,
    val partes: Int,
    val admitidoEn: String,
    val proximoIntentoEn: String? = null,
    val enviadoEn: String? = null,
    val entregadoEn: String? = null,
    val caducaEn: String? = null,
    /** Solo en `EN_COLA`: 1 es el siguiente en salir. */
    val posicionEnCola: Int? = null
) {
    companion object {
        fun de(m: MensajeSaliente, posicion: Int? = null) = MensajeJson(
            id = m.id,
            destino = m.destino,
            texto = m.texto,
            referencia = m.referencia,
            estado = m.estado.name,
            intentos = m.intentos,
            ultimoError = m.ultimoError,
            partes = m.partes,
            admitidoEn = m.admitidoEn.toString(),
            proximoIntentoEn = m.proximoIntentoEn.takeIf { m.intentos > 0 && !m.estado.terminado }?.toString(),
            enviadoEn = m.enviadoEn?.toString(),
            entregadoEn = m.entregadoEn?.toString(),
            caducaEn = m.caducaEn?.toString(),
            posicionEnCola = posicion
        )
    }
}

@Serializable
data class LoteJson(val mensajes: List<MensajeJson>)

@Serializable
data class PaginaJson(
    val mensajes: List<MensajeJson>,
    /** Cursor para pedir la página siguiente, o null si no hay más. */
    val siguiente: String? = null
)

@Serializable
data class CuotaJson(
    /** 0: sin límite. */
    val limite: Int,
    val usadas: Int
)

@Serializable
data class ColaJson(
    val pausada: Boolean,
    val enCola: Int,
    val enviando: Int,
    val pausaEntreMensajesSegundos: Int,
    /** Mensajes de hoy (hora del teléfono) por estado, de la clave que pregunta o de todas si es admin. */
    val hoy: Map<String, Int>,
    val cuotaGlobal: CuotaJson,
    val cuotaClave: CuotaJson? = null
)

@Serializable
data class SaludJson(val estado: String, val version: String)

/**
 * Un error, en el formato de RFC 9457 (`application/problem+json`).
 *
 * [codigo] es lo que debe mirar un programa: estable, en minúsculas y con guiones. [title] y
 * [detail] son para una persona y pueden cambiar de redacción.
 */
@Serializable
data class ProblemaJson(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val codigo: String,
    val errores: List<ErrorDeCampo>? = null
)

@Serializable
data class ErrorDeCampo(
    /** Posición en el lote; null en una petición de un solo mensaje. */
    val indice: Int? = null,
    val campo: String,
    val motivo: String
)
