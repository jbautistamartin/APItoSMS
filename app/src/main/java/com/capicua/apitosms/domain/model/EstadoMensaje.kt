// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.model

/**
 * Ciclo de vida de un [MensajeSaliente].
 *
 * ```
 * EN_COLA ──► ENVIANDO ──► ENVIADO ──► ENTREGADO
 *    │            │           └──────► NO_ENTREGADO   (informe de entrega negativo)
 *    │            ├─► EN_COLA   (error transitorio, con espera)
 *    │            └─► FALLIDO   (permanente, o intentos agotados)
 *    ├─► CANCELADO (DELETE antes de salir)
 *    └─► CADUCADO  (caduca_en vencido sin haber salido)
 * ```
 *
 * El nombre de cada constante **es el contrato de la API**: viaja tal cual en el JSON y se
 * persiste como texto en Room. Renombrar una rompe a los clientes y a la base de datos a la vez.
 */
enum class EstadoMensaje {
    /** Admitido y persistido; espera su turno en la cola. */
    EN_COLA,

    /** Es el mensaje en vuelo: se ha entregado a la radio y se espera la confirmación. */
    ENVIANDO,

    /** El operador confirmó la salida de todas las partes. */
    ENVIADO,

    /** Llegó el informe de entrega del teléfono de destino. No todos los operadores lo mandan. */
    ENTREGADO,

    /** El informe de entrega dice que no se pudo entregar. El SMS sí salió de este teléfono. */
    NO_ENTREGADO,

    /** No salió: error permanente, o se agotaron los intentos. */
    FALLIDO,

    /** Cancelado por el cliente antes de salir. */
    CANCELADO,

    /** Pasó su `caducaEn` sin haber salido, y ya no se envía. */
    CADUCADO;

    /** true si la cola ya no tiene nada que hacer con el mensaje. */
    val terminado: Boolean get() = this != EN_COLA && this != ENVIANDO

    companion object {
        /** El estado de nombre [nombre], o null si no es ninguno. Lo usa el filtro de la API. */
        fun de(nombre: String?): EstadoMensaje? = entries.firstOrNull { it.name == nombre }
    }
}
