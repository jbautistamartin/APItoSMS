// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.cola

import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.MensajeSaliente
import java.time.Instant

/**
 * Lo que la API y la cola necesitan de la persistencia de mensajes.
 *
 * Es un interfaz del dominio, no un DAO, para que la API y la cola se prueben en la JVM con una
 * implementación en memoria. La de verdad es `MensajeRepositoryImpl`, sobre Room.
 */
interface AlmacenDeMensajes {

    /** Inserta todos o ninguno, en una transacción: un lote se admite entero o no se admite. */
    suspend fun insertarTodos(mensajes: List<MensajeSaliente>)

    suspend fun obtener(id: String): MensajeSaliente?

    /** Mensajes de [claveId] con alguna de las [claves] de idempotencia. */
    suspend fun porIdempotencia(claveId: String, claves: List<String>): List<MensajeSaliente>

    /** Página de mensajes, del más reciente al más antiguo. */
    suspend fun listar(filtro: FiltroDeMensajes): List<MensajeSaliente>

    /** `EN_COLA` → `CANCELADO`. false si ya no estaba en cola (salió, o lo tomó la cola). */
    suspend fun cancelar(id: String, ahora: Instant): Boolean

    /** Mensajes `EN_COLA` que van por delante de [mensaje]. */
    suspend fun delanteEnCola(mensaje: MensajeSaliente): Int

    /** SMS (contando partes) admitidos desde [desde], sin los cancelados. [claveId] null: todos. */
    suspend fun partesAdmitidasDesde(desde: Instant, claveId: String?): Int

    /** Cuántos mensajes hay en cada estado, de los admitidos desde [desde]. */
    suspend fun resumenDesde(desde: Instant, claveId: String?): Map<EstadoMensaje, Int>

    /** Cuántos hay ahora mismo en [estado], sea cual sea su fecha. */
    suspend fun contarEnEstado(estado: EstadoMensaje): Int

    // ── Cola ──────────────────────────────────────────────────────────────

    /** El `EN_COLA` más antiguo cuyo `proximoIntentoEn` ya ha llegado, o null. */
    suspend fun siguienteElegible(ahora: Instant): MensajeSaliente?

    /**
     * `EN_COLA` → `ENVIANDO`, solo si sigue `EN_COLA`. Es lo que impide que un mensaje cancelado
     * en el último instante salga igualmente, y que dos pasadas de la cola manden el mismo.
     */
    suspend fun reclamar(id: String, ahora: Instant): Boolean

    suspend fun actualizar(mensaje: MensajeSaliente)

    /** Los que están `ENVIANDO`. Fuera de una pasada de la cola, son huérfanos. */
    suspend fun enviando(): List<MensajeSaliente>
}

data class FiltroDeMensajes(
    /** null: de todas las claves (solo con el ámbito de administración). */
    val claveId: String?,
    val estado: EstadoMensaje? = null,
    val referencia: String? = null,
    val desde: Instant? = null,
    /** Cursor: devolver solo los anteriores a este (admitidoEn, id). */
    val antesDe: Pair<Instant, String>? = null,
    val limite: Int = 50
)

/** Lo que la API necesita de las claves. */
interface AlmacenDeClaves {
    suspend fun porHash(hash: String): ClaveApi?
    suspend fun registrarUso(id: String, ahora: Instant, ip: String)
}

/** El registro de auditoría. */
fun interface RegistroDeAuditoria {
    suspend fun registrar(entrada: LogEntry)
}

/**
 * La cola vista desde la API: despertarla al admitir algo, y pausarla o reanudarla.
 */
interface ControlDeCola {
    /** Asegura que habrá una pasada de la cola después de este momento. */
    suspend fun despertar()

    suspend fun estaPausada(): Boolean

    suspend fun cambiarPausa(pausada: Boolean)
}

/** Ajustes de la cola, leídos al principio de cada mensaje: un cambio se aplica al siguiente. */
data class AjustesDeCola(
    val pausada: Boolean = false,
    val maxIntentos: Int = 5,
    val intervaloReintentoSegundos: Int = 30,
    val timeoutEnvioSegundos: Int = 60,
    val pausaEntreMensajesSegundos: Int = 3
)
