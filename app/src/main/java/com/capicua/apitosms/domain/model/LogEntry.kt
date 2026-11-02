// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.model

import java.time.Instant

/**
 * Una entrada del registro de auditoría.
 *
 * [destino], [clave] e [ip] van desnormalizados a propósito: el registro se lee de principio a fin
 * sin cruzar tablas y sigue siendo legible aunque el mensaje se purgue o la clave se borre.
 * **El texto del SMS no se escribe nunca aquí**: vive en el mensaje y se purga con él.
 */
data class LogEntry(
    val id: Long = 0,
    val tipo: LogTipo,
    val mensajeId: String? = null,
    val destino: String? = null,
    /** Nombre y prefijo de la clave implicada (`ERP · ats_7Kq2…`), o null. */
    val clave: String? = null,
    /** IP del cliente, en las entradas que vienen de una petición. */
    val ip: String? = null,
    val detalle: String,
    val timestamp: Instant
)
