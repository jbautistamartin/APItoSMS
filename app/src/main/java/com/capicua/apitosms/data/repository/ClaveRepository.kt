// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.repository

import com.capicua.apitosms.data.local.db.dao.ClaveDao
import com.capicua.apitosms.data.local.db.entity.ClaveEntity
import com.capicua.apitosms.domain.cola.AlmacenDeClaves
import com.capicua.apitosms.domain.cola.RegistroDeAuditoria
import com.capicua.apitosms.domain.model.Ambito
import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.domain.seguridad.SecretoDeClave
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Las claves de la API. Crear, regenerar, revocar y borrar dejan constancia en el registro.
 *
 * **Los secretos solo existen en el valor de retorno** de [crear] y [regenerar]: la pantalla los
 * enseña una vez y se pierden. Lo que se guarda es el hash.
 */
@Singleton
class ClaveRepository @Inject constructor(
    private val dao: ClaveDao,
    private val registro: RegistroDeAuditoria
) : AlmacenDeClaves {

    /** Una clave recién creada o regenerada, con su secreto en claro, que no se volverá a ver. */
    data class ConSecreto(val clave: ClaveApi, val secreto: String)

    override suspend fun porHash(hash: String): ClaveApi? = dao.porHash(hash)?.aDominio()

    override suspend fun registrarUso(id: String, ahora: Instant, ip: String) =
        dao.registrarUso(id, ahora.toEpochMilli(), ip)

    fun observarTodas(): Flow<List<ClaveApi>> = dao.observarTodas().map { l -> l.map { it.aDominio() } }

    fun observarActivas(): Flow<Int> = dao.observarActivas()

    suspend fun todas(): List<ClaveApi> = dao.todas().map { it.aDominio() }

    suspend fun obtener(id: String): ClaveApi? = dao.obtener(id)?.aDominio()

    suspend fun crear(
        nombre: String,
        ambitos: Set<Ambito>,
        redes: List<String>,
        cuotaDiaria: Int,
        peticionesPorMinuto: Int
    ): ConSecreto {
        val secreto = SecretoDeClave.generar()
        val clave = ClaveApi(
            id = UUID.randomUUID().toString(),
            nombre = nombre.trim(),
            prefijo = SecretoDeClave.prefijoVisible(secreto),
            hash = SecretoDeClave.hash(secreto),
            ambitos = ambitos,
            redesPermitidas = redes,
            cuotaDiaria = cuotaDiaria,
            peticionesPorMinuto = peticionesPorMinuto,
            creadaEn = Instant.now()
        )
        dao.insertar(clave.aEntidad())
        anotar(clave, "Clave creada con ámbitos ${ambitos.joinToString { it.name }}")
        return ConSecreto(clave, secreto)
    }

    /** Guarda los cambios de nombre, ámbitos, redes y límites. No toca el secreto. */
    suspend fun guardar(clave: ClaveApi) {
        dao.actualizar(clave.aEntidad())
        anotar(clave, "Clave modificada: ámbitos ${clave.ambitos.joinToString { it.name }}, " +
            "redes ${clave.redesPermitidas.ifEmpty { listOf("las globales") }.joinToString()}")
    }

    /**
     * Secreto nuevo para la misma clave, que además queda activa. Es la rotación de una clave, y
     * es la forma de reactivar las claves que llegan revocadas de una copia de seguridad.
     */
    suspend fun regenerar(id: String): ConSecreto? {
        val anterior = obtener(id) ?: return null
        val secreto = SecretoDeClave.generar()
        val nueva = anterior.copy(
            prefijo = SecretoDeClave.prefijoVisible(secreto),
            hash = SecretoDeClave.hash(secreto),
            revocadaEn = null
        )
        dao.actualizar(nueva.aEntidad())
        anotar(nueva, "Secreto regenerado (el anterior, ${anterior.prefijo}…, deja de valer)")
        return ConSecreto(nueva, secreto)
    }

    suspend fun revocar(id: String) {
        val clave = obtener(id) ?: return
        val revocada = clave.copy(revocadaEn = Instant.now())
        dao.actualizar(revocada.aEntidad())
        anotar(revocada, "Clave revocada")
    }

    suspend fun borrar(id: String) {
        val clave = obtener(id) ?: return
        dao.borrar(id)
        anotar(clave, "Clave borrada")
    }

    /** Inserta una clave tal cual: la usa la importación de la copia, con claves ya revocadas. */
    suspend fun insertarImportada(clave: ClaveApi) {
        dao.insertar(clave.aEntidad())
    }

    private suspend fun anotar(clave: ClaveApi, detalle: String) {
        registro.registrar(LogEntry(
            tipo = LogTipo.SEGURIDAD,
            clave = "${clave.nombre} · ${clave.prefijo}…",
            detalle = detalle,
            timestamp = Instant.now()
        ))
    }
}

private fun ClaveApi.aEntidad() = ClaveEntity(
    id = id,
    nombre = nombre,
    prefijo = prefijo,
    hash = hash,
    ambitos = ambitos.joinToString(",") { it.name },
    redesPermitidas = redesPermitidas.joinToString(","),
    cuotaDiaria = cuotaDiaria,
    peticionesPorMinuto = peticionesPorMinuto,
    creadaEn = creadaEn.toEpochMilli(),
    ultimoUsoEn = ultimoUsoEn?.toEpochMilli(),
    ultimaIp = ultimaIp,
    revocadaEn = revocadaEn?.toEpochMilli()
)

private fun ClaveEntity.aDominio() = ClaveApi(
    id = id,
    nombre = nombre,
    prefijo = prefijo,
    hash = hash,
    ambitos = ambitos.split(',').mapNotNull { Ambito.de(it.trim()) }.toSet(),
    redesPermitidas = redesPermitidas.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    cuotaDiaria = cuotaDiaria,
    peticionesPorMinuto = peticionesPorMinuto,
    creadaEn = Instant.ofEpochMilli(creadaEn),
    ultimoUsoEn = ultimoUsoEn?.let(Instant::ofEpochMilli),
    ultimaIp = ultimaIp,
    revocadaEn = revocadaEn?.let(Instant::ofEpochMilli)
)
