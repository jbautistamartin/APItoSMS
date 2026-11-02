// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * La outbox: un mensaje pedido por la API.
 *
 * Los índices responden a las consultas que se hacen de verdad:
 * - `(estado, admitido_en, id)`: la cola pide el `EN_COLA` más antiguo, una vez por mensaje.
 * - `(clave_id, admitido_en)`: la cuota diaria y el listado de cada clave.
 * - `(clave_id, idempotency_key)` **único**: el respaldo en la base de datos de la idempotencia,
 *   por si algún día dos admisiones se cruzan pese al `Mutex` de la API. SQLite trata dos `NULL`
 *   como distintos, así que los mensajes sin clave de idempotencia no chocan entre sí.
 */
@Entity(
    tableName = "mensajes",
    indices = [
        Index(value = ["estado", "admitido_en", "id"]),
        Index(value = ["clave_id", "admitido_en"]),
        Index(value = ["clave_id", "idempotency_key"], unique = true),
        Index(value = ["referencia"])
    ]
)
data class MensajeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "destino") val destino: String,
    @ColumnInfo(name = "texto") val texto: String?,
    @ColumnInfo(name = "clave_id") val claveId: String?,
    @ColumnInfo(name = "clave_nombre") val claveNombre: String?,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?,
    @ColumnInfo(name = "huella_peticion") val huellaPeticion: String?,
    @ColumnInfo(name = "referencia") val referencia: String?,
    /** Nombre de la constante de `EstadoMensaje`. */
    @ColumnInfo(name = "estado") val estado: String,
    @ColumnInfo(name = "intentos") val intentos: Int,
    @ColumnInfo(name = "ultimo_error") val ultimoError: String?,
    @ColumnInfo(name = "partes") val partes: Int,
    @ColumnInfo(name = "partes_entregadas") val partesEntregadas: Int,
    @ColumnInfo(name = "admitido_en") val admitidoEn: Long,
    @ColumnInfo(name = "proximo_intento_en") val proximoIntentoEn: Long,
    @ColumnInfo(name = "enviado_en") val enviadoEn: Long?,
    @ColumnInfo(name = "entregado_en") val entregadoEn: Long?,
    @ColumnInfo(name = "caduca_en") val caducaEn: Long?,
    @ColumnInfo(name = "actualizado_en") val actualizadoEn: Long
)

/**
 * Una clave de la API. Solo el hash del secreto: ver `SecretoDeClave`.
 *
 * Las listas se guardan como texto separado por comas: son cortas, nunca se consultan por su
 * contenido y un `TypeConverter` sería más código para lo mismo.
 */
@Entity(
    tableName = "claves",
    indices = [Index(value = ["hash"], unique = true)]
)
data class ClaveEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "nombre") val nombre: String,
    @ColumnInfo(name = "prefijo") val prefijo: String,
    @ColumnInfo(name = "hash") val hash: String,
    /** Nombres de `Ambito`, separados por comas. */
    @ColumnInfo(name = "ambitos") val ambitos: String,
    /** IP o CIDR separadas por comas. */
    @ColumnInfo(name = "redes_permitidas") val redesPermitidas: String,
    @ColumnInfo(name = "cuota_diaria") val cuotaDiaria: Int,
    @ColumnInfo(name = "peticiones_por_minuto") val peticionesPorMinuto: Int,
    @ColumnInfo(name = "creada_en") val creadaEn: Long,
    @ColumnInfo(name = "ultimo_uso_en") val ultimoUsoEn: Long?,
    @ColumnInfo(name = "ultima_ip") val ultimaIp: String?,
    @ColumnInfo(name = "revocada_en") val revocadaEn: Long?
)

/** El registro de auditoría. */
@Entity(
    tableName = "log_entries",
    indices = [Index(value = ["timestamp"]), Index(value = ["mensaje_id"])]
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** Nombre de la constante de `LogTipo`. */
    @ColumnInfo(name = "tipo") val tipo: String,
    @ColumnInfo(name = "mensaje_id") val mensajeId: String?,
    @ColumnInfo(name = "destino") val destino: String?,
    @ColumnInfo(name = "clave") val clave: String?,
    @ColumnInfo(name = "ip") val ip: String?,
    @ColumnInfo(name = "detalle") val detalle: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)
