// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms

import com.capicua.apitosms.domain.cola.AlmacenDeClaves
import com.capicua.apitosms.domain.cola.AlmacenDeMensajes
import com.capicua.apitosms.domain.cola.ControlDeCola
import com.capicua.apitosms.domain.cola.FiltroDeMensajes
import com.capicua.apitosms.domain.cola.RegistroDeAuditoria
import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.MensajeSaliente
import com.capicua.apitosms.domain.seguridad.Reloj
import com.capicua.apitosms.domain.transport.EnviadorDeMensajes
import com.capicua.apitosms.domain.transport.ResultadoEnvio
import java.time.Duration
import java.time.Instant

/**
 * Dobles de prueba de los puertos del dominio.
 *
 * [AlmacenEnMemoria] reproduce **las consultas de `MensajeDao`**, no una versión simplificada:
 * mismo orden, mismos desempates y mismas condiciones de estado. Si se cambia una consulta del
 * DAO, hay que cambiarla aquí, o los tests probarán otra cosa.
 */
class RelojFijo(var instante: Instant = Instant.parse("2026-11-01T10:00:00Z")) : Reloj {
    override fun ahora(): Instant = instante
    fun avanzar(segundos: Long) {
        instante = instante.plus(Duration.ofSeconds(segundos))
    }
}

class AlmacenEnMemoria : AlmacenDeMensajes {

    val filas = linkedMapOf<String, MensajeSaliente>()

    override suspend fun insertarTodos(mensajes: List<MensajeSaliente>) {
        mensajes.forEach { filas[it.id] = it }
    }

    override suspend fun obtener(id: String) = filas[id]

    override suspend fun porIdempotencia(claveId: String, claves: List<String>) =
        filas.values.filter { it.claveId == claveId && it.idempotencyKey in claves }

    override suspend fun listar(filtro: FiltroDeMensajes) = filas.values
        .filter { filtro.claveId == null || it.claveId == filtro.claveId }
        .filter { filtro.estado == null || it.estado == filtro.estado }
        .filter { filtro.referencia == null || it.referencia == filtro.referencia }
        .filter { filtro.desde == null || it.admitidoEn >= filtro.desde }
        .filter { m ->
            val (ms, id) = filtro.antesDe ?: return@filter true
            m.admitidoEn.toEpochMilli() < ms.toEpochMilli() ||
                (m.admitidoEn.toEpochMilli() == ms.toEpochMilli() && m.id < id)
        }
        .sortedWith(compareByDescending<MensajeSaliente> { it.admitidoEn.toEpochMilli() }.thenByDescending { it.id })
        .take(filtro.limite)

    override suspend fun cancelar(id: String, ahora: Instant): Boolean {
        val m = filas[id]?.takeIf { it.estado == EstadoMensaje.EN_COLA } ?: return false
        filas[id] = m.copy(estado = EstadoMensaje.CANCELADO, actualizadoEn = ahora, ultimoError = "cancelado antes de salir")
        return true
    }

    override suspend fun delanteEnCola(mensaje: MensajeSaliente) = filas.values.count {
        it.estado == EstadoMensaje.EN_COLA && (it.admitidoEn < mensaje.admitidoEn ||
            (it.admitidoEn == mensaje.admitidoEn && it.id < mensaje.id))
    }

    override suspend fun partesAdmitidasDesde(desde: Instant, claveId: String?) = filas.values
        .filter { it.admitidoEn >= desde && it.estado != EstadoMensaje.CANCELADO }
        .filter { claveId == null || it.claveId == claveId }
        .sumOf { it.partes }

    override suspend fun resumenDesde(desde: Instant, claveId: String?) = filas.values
        .filter { it.admitidoEn >= desde && (claveId == null || it.claveId == claveId) }
        .groupingBy { it.estado }.eachCount()

    override suspend fun contarEnEstado(estado: EstadoMensaje) = filas.values.count { it.estado == estado }

    override suspend fun siguienteElegible(ahora: Instant) = filas.values
        .filter { it.estado == EstadoMensaje.EN_COLA && it.proximoIntentoEn <= ahora }
        .minWithOrNull(compareBy<MensajeSaliente> { it.admitidoEn }.thenBy { it.id })

    override suspend fun reclamar(id: String, ahora: Instant): Boolean {
        val m = filas[id]?.takeIf { it.estado == EstadoMensaje.EN_COLA } ?: return false
        filas[id] = m.copy(estado = EstadoMensaje.ENVIANDO, actualizadoEn = ahora)
        return true
    }

    override suspend fun actualizar(mensaje: MensajeSaliente) {
        filas[mensaje.id] = mensaje
    }

    override suspend fun enviando() = filas.values.filter { it.estado == EstadoMensaje.ENVIANDO }
}

class ClavesEnMemoria(vararg claves: ClaveApi) : AlmacenDeClaves {
    val porId = claves.associateBy { it.id }.toMutableMap()
    val usos = mutableListOf<Pair<String, String>>()

    override suspend fun porHash(hash: String) = porId.values.firstOrNull { it.hash == hash }

    override suspend fun registrarUso(id: String, ahora: Instant, ip: String) {
        usos += id to ip
    }
}

class RegistroEnMemoria : RegistroDeAuditoria {
    val entradas = mutableListOf<LogEntry>()
    override suspend fun registrar(entrada: LogEntry) {
        entradas += entrada
    }
}

class ColaFalsa : ControlDeCola {
    var despertares = 0
    var pausada = false
    override suspend fun despertar() {
        despertares++
    }
    override suspend fun estaPausada() = pausada
    override suspend fun cambiarPausa(pausada: Boolean) {
        this.pausada = pausada
    }
}

/** Devuelve, por orden, los resultados que se le den; después, [porDefecto]. */
class EnviadorFalso(
    vararg resultados: ResultadoEnvio,
    private val porDefecto: ResultadoEnvio = ResultadoEnvio.Enviado
) : EnviadorDeMensajes {
    private val pendientes = ArrayDeque(resultados.toList())
    val enviados = mutableListOf<Pair<String, String>>()
    var noDisponible: String? = null

    override suspend fun enviar(destino: String, texto: String, timeoutSegundos: Int, seguimiento: String?): ResultadoEnvio {
        enviados += destino to texto
        return pendientes.removeFirstOrNull() ?: porDefecto
    }

    /** Una parte cada 160 caracteres: basta para probar cuotas y límites. */
    override fun contarPartes(texto: String) = if (texto.length <= 160) 1 else (texto.length + 152) / 153

    override suspend fun motivoNoDisponible() = noDisponible
}
