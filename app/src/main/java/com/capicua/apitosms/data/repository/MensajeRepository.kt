// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.repository

import com.capicua.apitosms.data.local.db.dao.MensajeDao
import com.capicua.apitosms.data.local.db.entity.MensajeEntity
import com.capicua.apitosms.domain.cola.AlmacenDeMensajes
import com.capicua.apitosms.domain.cola.FiltroDeMensajes
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.MensajeSaliente
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Los mensajes sobre Room. Es el [AlmacenDeMensajes] de la API y de la cola, y además da a la
 * interfaz los flujos que observa.
 */
@Singleton
class MensajeRepository @Inject constructor(
    private val dao: MensajeDao
) : AlmacenDeMensajes {

    override suspend fun insertarTodos(mensajes: List<MensajeSaliente>) =
        dao.insertar(mensajes.map { it.aEntidad() })

    override suspend fun obtener(id: String): MensajeSaliente? = dao.obtener(id)?.aDominio()

    override suspend fun porIdempotencia(claveId: String, claves: List<String>): List<MensajeSaliente> =
        if (claves.isEmpty()) emptyList() else dao.porIdempotencia(claveId, claves).map { it.aDominio() }

    override suspend fun listar(filtro: FiltroDeMensajes): List<MensajeSaliente> =
        dao.listar(
            claveId = filtro.claveId,
            estado = filtro.estado?.name,
            referencia = filtro.referencia,
            desde = filtro.desde?.toEpochMilli(),
            antesDeMs = filtro.antesDe?.first?.toEpochMilli(),
            antesDeId = filtro.antesDe?.second,
            limite = filtro.limite
        ).map { it.aDominio() }

    override suspend fun cancelar(id: String, ahora: Instant): Boolean =
        dao.cancelar(id, ahora.toEpochMilli()) > 0

    override suspend fun delanteEnCola(mensaje: MensajeSaliente): Int =
        dao.delanteEnCola(mensaje.admitidoEn.toEpochMilli(), mensaje.id)

    override suspend fun partesAdmitidasDesde(desde: Instant, claveId: String?): Int =
        dao.partesDesde(desde.toEpochMilli(), claveId)

    override suspend fun resumenDesde(desde: Instant, claveId: String?): Map<EstadoMensaje, Int> =
        dao.resumenDesde(desde.toEpochMilli(), claveId)
            .mapNotNull { r -> EstadoMensaje.de(r.estado)?.let { it to r.total } }
            .toMap()

    override suspend fun contarEnEstado(estado: EstadoMensaje): Int = dao.contarEnEstado(estado.name)

    override suspend fun siguienteElegible(ahora: Instant): MensajeSaliente? =
        dao.siguienteElegible(ahora.toEpochMilli())?.aDominio()

    override suspend fun reclamar(id: String, ahora: Instant): Boolean =
        dao.reclamar(id, ahora.toEpochMilli()) > 0

    override suspend fun actualizar(mensaje: MensajeSaliente) = dao.actualizar(mensaje.aEntidad())

    override suspend fun enviando(): List<MensajeSaliente> = dao.enviando().map { it.aDominio() }

    // ── Para la interfaz ──────────────────────────────────────────────────

    fun observar(estado: EstadoMensaje?, limite: Int = 300): Flow<List<MensajeSaliente>> =
        dao.observar(estado?.name, limite).map { lista -> lista.map { it.aDominio() } }

    fun observarResumenDesde(desde: Instant): Flow<Map<EstadoMensaje, Int>> =
        dao.observarResumenDesde(desde.toEpochMilli()).map { lista ->
            lista.mapNotNull { r -> EstadoMensaje.de(r.estado)?.let { it to r.total } }.toMap()
        }

    fun observarEnEstado(estado: EstadoMensaje): Flow<Int> = dao.observarEnEstado(estado.name)

    // ── Retención ─────────────────────────────────────────────────────────

    suspend fun purgarTextos(antesDe: Instant): Int = dao.purgarTextos(antesDe.toEpochMilli())

    suspend fun borrarTerminadosAntesDe(antesDe: Instant): Int =
        dao.borrarTerminadosAntesDe(antesDe.toEpochMilli())
}

internal fun MensajeSaliente.aEntidad() = MensajeEntity(
    id = id,
    destino = destino,
    texto = texto,
    claveId = claveId,
    claveNombre = claveNombre,
    idempotencyKey = idempotencyKey,
    huellaPeticion = huellaPeticion,
    referencia = referencia,
    estado = estado.name,
    intentos = intentos,
    ultimoError = ultimoError,
    partes = partes,
    partesEntregadas = partesEntregadas,
    admitidoEn = admitidoEn.toEpochMilli(),
    proximoIntentoEn = proximoIntentoEn.toEpochMilli(),
    enviadoEn = enviadoEn?.toEpochMilli(),
    entregadoEn = entregadoEn?.toEpochMilli(),
    caducaEn = caducaEn?.toEpochMilli(),
    actualizadoEn = actualizadoEn.toEpochMilli()
)

internal fun MensajeEntity.aDominio() = MensajeSaliente(
    id = id,
    destino = destino,
    texto = texto,
    claveId = claveId,
    claveNombre = claveNombre,
    idempotencyKey = idempotencyKey,
    huellaPeticion = huellaPeticion,
    referencia = referencia,
    // Un estado desconocido solo podría venir de una versión futura degradada; se trata como
    // fallido para que la cola no lo toque.
    estado = EstadoMensaje.de(estado) ?: EstadoMensaje.FALLIDO,
    intentos = intentos,
    ultimoError = ultimoError,
    partes = partes,
    partesEntregadas = partesEntregadas,
    admitidoEn = Instant.ofEpochMilli(admitidoEn),
    proximoIntentoEn = Instant.ofEpochMilli(proximoIntentoEn),
    enviadoEn = enviadoEn?.let(Instant::ofEpochMilli),
    entregadoEn = entregadoEn?.let(Instant::ofEpochMilli),
    caducaEn = caducaEn?.let(Instant::ofEpochMilli),
    actualizadoEn = Instant.ofEpochMilli(actualizadoEn)
)
