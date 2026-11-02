// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.repository

import com.capicua.apitosms.data.local.db.dao.LogDao
import com.capicua.apitosms.data.local.db.entity.LogEntity
import com.capicua.apitosms.domain.cola.RegistroDeAuditoria
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** El registro de auditoría sobre Room. */
@Singleton
class LogRepository @Inject constructor(
    private val logDao: LogDao
) : RegistroDeAuditoria {

    /**
     * Inserta una entrada. Si falla, lo dice Timber y sigue: perder una línea del registro no puede
     * tumbar una petición ni dejar un mensaje a medias.
     */
    override suspend fun registrar(entrada: LogEntry) {
        try {
            logDao.insertar(entrada.aEntidad())
        } catch (e: Exception) {
            Timber.e(e, "No se pudo escribir en el registro: ${entrada.detalle}")
        }
    }

    suspend fun insertar(entry: LogEntry) = registrar(entry)

    fun observarFiltrados(tipo: LogTipo?, limite: Int = 500): Flow<List<LogEntry>> =
        logDao.observarFiltrados(tipo?.name, limite).map { lista -> lista.map { it.aDominio() } }

    suspend fun obtenerTodosParaExportar(): List<LogEntry> =
        logDao.obtenerTodosParaExportar().map { it.aDominio() }

    suspend fun eliminarAntiguos(antes: Instant) = logDao.eliminarAnterioresA(antes.toEpochMilli())

    fun observarTotal(): Flow<Int> = logDao.observarTotal()

    suspend fun limpiarTodo() = logDao.limpiarTodo()

    private fun LogEntry.aEntidad() = LogEntity(
        id = id,
        tipo = tipo.name,
        mensajeId = mensajeId,
        destino = destino,
        clave = clave,
        ip = ip,
        detalle = detalle,
        timestamp = timestamp.toEpochMilli()
    )

    private fun LogEntity.aDominio() = LogEntry(
        id = id,
        tipo = runCatching { LogTipo.valueOf(tipo) }.getOrDefault(LogTipo.SISTEMA),
        mensajeId = mensajeId,
        destino = destino,
        clave = clave,
        ip = ip,
        detalle = detalle,
        timestamp = Instant.ofEpochMilli(timestamp)
    )
}
