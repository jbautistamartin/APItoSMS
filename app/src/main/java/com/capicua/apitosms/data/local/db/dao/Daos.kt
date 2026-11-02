// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.capicua.apitosms.data.local.db.entity.ClaveEntity
import com.capicua.apitosms.data.local.db.entity.LogEntity
import com.capicua.apitosms.data.local.db.entity.MensajeEntity
import kotlinx.coroutines.flow.Flow

/** Recuento por estado, para el Panel y para `GET /cola`. */
data class RecuentoPorEstado(val estado: String, val total: Int)

@Dao
interface MensajeDao {

    /** Room inserta una lista en una sola transacción: un lote entra entero o no entra. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(mensajes: List<MensajeEntity>)

    @Update
    suspend fun actualizar(mensaje: MensajeEntity)

    @Query("SELECT * FROM mensajes WHERE id = :id")
    suspend fun obtener(id: String): MensajeEntity?

    @Query("SELECT * FROM mensajes WHERE clave_id = :claveId AND idempotency_key IN (:claves)")
    suspend fun porIdempotencia(claveId: String, claves: List<String>): List<MensajeEntity>

    /**
     * Página de mensajes, del más reciente al más antiguo. El cursor es la pareja
     * (admitido_en, id) del último de la página anterior: con mensajes del mismo milisegundo —un
     * lote entero lo es— solo el desempate por id hace que ninguno se repita ni se salte.
     */
    @Query(
        """
        SELECT * FROM mensajes
        WHERE (:claveId IS NULL OR clave_id = :claveId)
          AND (:estado IS NULL OR estado = :estado)
          AND (:referencia IS NULL OR referencia = :referencia)
          AND (:desde IS NULL OR admitido_en >= :desde)
          AND (:antesDeMs IS NULL OR admitido_en < :antesDeMs OR (admitido_en = :antesDeMs AND id < :antesDeId))
        ORDER BY admitido_en DESC, id DESC
        LIMIT :limite
        """
    )
    suspend fun listar(
        claveId: String?, estado: String?, referencia: String?, desde: Long?,
        antesDeMs: Long?, antesDeId: String?, limite: Int
    ): List<MensajeEntity>

    @Query(
        """
        UPDATE mensajes SET estado = 'CANCELADO', actualizado_en = :ahora,
            ultimo_error = 'cancelado antes de salir'
        WHERE id = :id AND estado = 'EN_COLA'
        """
    )
    suspend fun cancelar(id: String, ahora: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM mensajes WHERE estado = 'EN_COLA'
          AND (admitido_en < :admitido OR (admitido_en = :admitido AND id < :id))
        """
    )
    suspend fun delanteEnCola(admitido: Long, id: String): Int

    @Query(
        """
        SELECT COALESCE(SUM(partes), 0) FROM mensajes
        WHERE admitido_en >= :desde AND estado != 'CANCELADO'
          AND (:claveId IS NULL OR clave_id = :claveId)
        """
    )
    suspend fun partesDesde(desde: Long, claveId: String?): Int

    @Query(
        """
        SELECT estado, COUNT(*) AS total FROM mensajes
        WHERE admitido_en >= :desde AND (:claveId IS NULL OR clave_id = :claveId)
        GROUP BY estado
        """
    )
    suspend fun resumenDesde(desde: Long, claveId: String?): List<RecuentoPorEstado>

    @Query(
        "SELECT estado, COUNT(*) AS total FROM mensajes WHERE admitido_en >= :desde GROUP BY estado"
    )
    fun observarResumenDesde(desde: Long): Flow<List<RecuentoPorEstado>>

    @Query("SELECT COUNT(*) FROM mensajes WHERE estado = :estado")
    suspend fun contarEnEstado(estado: String): Int

    @Query("SELECT COUNT(*) FROM mensajes WHERE estado = :estado")
    fun observarEnEstado(estado: String): Flow<Int>

    @Query(
        """
        SELECT * FROM mensajes WHERE estado = 'EN_COLA' AND proximo_intento_en <= :ahora
        ORDER BY admitido_en ASC, id ASC LIMIT 1
        """
    )
    suspend fun siguienteElegible(ahora: Long): MensajeEntity?

    @Query(
        """
        UPDATE mensajes SET estado = 'ENVIANDO', actualizado_en = :ahora
        WHERE id = :id AND estado = 'EN_COLA'
        """
    )
    suspend fun reclamar(id: String, ahora: Long): Int

    @Query("SELECT * FROM mensajes WHERE estado = 'ENVIANDO'")
    suspend fun enviando(): List<MensajeEntity>

    /** El historial de la pestaña Mensajes. */
    @Query(
        """
        SELECT * FROM mensajes WHERE (:estado IS NULL OR estado = :estado)
        ORDER BY admitido_en DESC, id DESC LIMIT :limite
        """
    )
    fun observar(estado: String?, limite: Int): Flow<List<MensajeEntity>>

    /**
     * La retención: borra el **texto** de los mensajes terminados hace más de [antesDe]. El resto
     * de la fila se queda hasta [borrarTerminadosAntesDe]: el cliente puede seguir consultando el
     * estado de un mensaje viejo, sin su contenido.
     */
    @Query(
        """
        UPDATE mensajes SET texto = NULL
        WHERE texto IS NOT NULL AND actualizado_en < :antesDe
          AND estado NOT IN ('EN_COLA', 'ENVIANDO')
        """
    )
    suspend fun purgarTextos(antesDe: Long): Int

    @Query(
        """
        DELETE FROM mensajes WHERE actualizado_en < :antesDe
          AND estado NOT IN ('EN_COLA', 'ENVIANDO')
        """
    )
    suspend fun borrarTerminadosAntesDe(antesDe: Long): Int
}

@Dao
interface ClaveDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(clave: ClaveEntity)

    @Update
    suspend fun actualizar(clave: ClaveEntity)

    @Query("DELETE FROM claves WHERE id = :id")
    suspend fun borrar(id: String)

    @Query("SELECT * FROM claves WHERE id = :id")
    suspend fun obtener(id: String): ClaveEntity?

    @Query("SELECT * FROM claves WHERE hash = :hash")
    suspend fun porHash(hash: String): ClaveEntity?

    @Query("SELECT * FROM claves ORDER BY revocada_en IS NOT NULL, nombre COLLATE NOCASE")
    fun observarTodas(): Flow<List<ClaveEntity>>

    @Query("SELECT * FROM claves ORDER BY nombre COLLATE NOCASE")
    suspend fun todas(): List<ClaveEntity>

    @Query("SELECT COUNT(*) FROM claves WHERE revocada_en IS NULL")
    fun observarActivas(): Flow<Int>

    @Query("UPDATE claves SET ultimo_uso_en = :ahora, ultima_ip = :ip WHERE id = :id")
    suspend fun registrarUso(id: String, ahora: Long, ip: String)
}

@Dao
interface LogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(log: LogEntity)

    @Query("SELECT * FROM log_entries WHERE (:tipo IS NULL OR tipo = :tipo) ORDER BY timestamp DESC, id DESC LIMIT :limite")
    fun observarFiltrados(tipo: String?, limite: Int = 500): Flow<List<LogEntity>>

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC, id DESC")
    suspend fun obtenerTodosParaExportar(): List<LogEntity>

    @Query("DELETE FROM log_entries WHERE timestamp < :antesDeMs")
    suspend fun eliminarAnterioresA(antesDeMs: Long)

    @Query("SELECT COUNT(*) FROM log_entries")
    fun observarTotal(): Flow<Int>

    @Query("DELETE FROM log_entries")
    suspend fun limpiarTodo()
}
