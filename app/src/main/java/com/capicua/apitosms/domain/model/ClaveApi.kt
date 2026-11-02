// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.model

import java.time.Instant

/**
 * Lo que puede hacer una clave de la API.
 *
 * Son tres y no uno por endpoint a propósito: lo que hay que separar en una empresa es «el
 * sistema que manda» de «el que solo mira» y de «quien puede parar la cola». Más finura sería
 * configuración que nadie revisa.
 */
enum class Ambito(val descripcion: String) {
    /** Mandar mensajes y lotes, y cancelar los propios. */
    ENVIAR("Enviar y cancelar"),

    /** Consultar mensajes propios y el estado de la cola. */
    CONSULTAR("Consultar"),

    /** Ver los mensajes de todas las claves, y pausar o reanudar la cola. */
    ADMIN("Administrar");

    companion object {
        fun de(nombre: String): Ambito? = entries.firstOrNull { it.name == nombre }
    }
}

/**
 * Una clave de la API: quién es un cliente, qué puede hacer y desde dónde.
 *
 * **El secreto no está aquí.** Se enseña una vez, al crearla o regenerarla, y lo que se guarda es
 * su SHA-256 en [hash]. Quien lea la base de datos del teléfono no puede usar ninguna clave.
 *
 * @property prefijo los primeros caracteres del secreto, para reconocer la clave en la lista y en
 *   el registro sin enseñarla entera (`ats_7Kq2…`).
 * @property redesPermitidas IP o CIDR desde las que se acepta. Vacía: las que permita el ajuste
 *   global.
 * @property cuotaDiaria SMS (contando partes) al día con esta clave. 0: sin cuota propia, solo la
 *   global.
 * @property peticionesPorMinuto ritmo máximo de peticiones con esta clave.
 * @property revocadaEn no null: la clave no autentica. Una clave importada de una copia llega así.
 */
data class ClaveApi(
    val id: String,
    val nombre: String,
    val prefijo: String,
    val hash: String,
    val ambitos: Set<Ambito>,
    val redesPermitidas: List<String> = emptyList(),
    val cuotaDiaria: Int = 0,
    val peticionesPorMinuto: Int = PETICIONES_POR_MINUTO_POR_DEFECTO,
    val creadaEn: Instant,
    val ultimoUsoEn: Instant? = null,
    val ultimaIp: String? = null,
    val revocadaEn: Instant? = null
) {
    val activa: Boolean get() = revocadaEn == null

    fun puede(ambito: Ambito): Boolean = ambito in ambitos

    companion object {
        /** Holgado para un ERP que consulta a menudo, corto para un bucle que se ha vuelto loco. */
        const val PETICIONES_POR_MINUTO_POR_DEFECTO = 120
    }
}
