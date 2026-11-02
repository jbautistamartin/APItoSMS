// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.transport

/**
 * Resultado de intentar entregar un mensaje.
 *
 * La distinción entre transitorio y permanente es la que gobierna los reintentos de la cola: un
 * error transitorio devuelve el mensaje a la cola con una espera, uno permanente lo cierra como
 * `FALLIDO`. Para el SMS, «transitorio» es no tener cobertura, la radio apagada o un fallo genérico
 * del operador; «permanente» es un destino que la radio no acepta o la falta de permiso.
 */
sealed interface ResultadoEnvio {

    /** El operador confirmó la salida de todas las partes. */
    data object Enviado : ResultadoEnvio

    /**
     * Fallo que puede desaparecer solo: sin cobertura, o la confirmación no llegó en el plazo.
     *
     * @param esperaSegundos espera pedida por el propio medio, si la pide. El SMS no la pide
     *   nunca; el campo se hereda de SMSRelay, donde Telegram mandaba `retry_after`, y se queda
     *   porque describe el resultado, no el medio.
     */
    data class ErrorTransitorio(
        val motivo: String,
        val esperaSegundos: Int? = null
    ) : ResultadoEnvio

    /** Fallo que no se arregla reintentando. */
    data class ErrorPermanente(val motivo: String) : ResultadoEnvio
}
