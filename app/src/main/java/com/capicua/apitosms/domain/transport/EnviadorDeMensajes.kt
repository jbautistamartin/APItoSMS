// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.transport

/**
 * El medio por el que sale un mensaje.
 *
 * Es la juntura de la aplicación. Lo que hay por encima —admitir la petición, persistirla,
 * ponerla en la cola, decidir el turno, reintentar, rescatar lo que se quedó a medias— no sabe
 * cómo sale un SMS. Lo que hay por debajo vive en `data/sms`.
 *
 * ## Una sola implementación, y aun así una juntura
 * APItoSMS solo manda SMS, con `SmsSender`, y no está previsto que mande otra cosa. El interfaz
 * se queda igualmente, por dos motivos: separa «qué se manda y cuándo» de «cómo sale», y permite
 * probar la cola entera en la JVM con un enviador falso, sin radio ni teléfono.
 *
 * Viene de SMSRelay, donde había cuatro implementaciones elegidas por regla; aquí no hay reglas
 * ni canales, y `di/TransportModule` es un `@Binds` y nada más.
 *
 * ## El contrato
 * - **Espera la confirmación real.** Si [enviar] volviera antes de saber el desenlace, la API
 *   diría «enviado» de mensajes que nunca salieron.
 * - **No reintenta.** Devuelve [ResultadoEnvio.ErrorTransitorio] y decide la cola.
 * - **No registra en el log.** Devuelve un motivo legible y lo escribe quien llama.
 * - **No decide el destino ni valida la política** (prefijos, cuotas): eso lo hizo la API al
 *   admitir el mensaje.
 */
interface EnviadorDeMensajes {

    /**
     * Entrega [texto] a [destino] y **espera la confirmación**, como mucho [timeoutSegundos].
     *
     * @param seguimiento identificador que el transporte adjunta a los informes de entrega, si los
     *   pide. Es el id del mensaje: el informe llega minutos después, por otro camino, y tiene que
     *   saber a qué fila pertenece. Esperarlo no es parte de [enviar]: muchos operadores no lo
     *   mandan nunca.
     */
    suspend fun enviar(
        destino: String,
        texto: String,
        timeoutSegundos: Int,
        seguimiento: String? = null
    ): ResultadoEnvio

    /**
     * En cuántos SMS se trocea [texto]. Es lo que se factura, y lo que cuentan la cuota diaria y
     * el tope de partes por mensaje.
     */
    fun contarPartes(texto: String): Int

    /**
     * Explicación legible de por qué no se puede enviar ahora mismo, o null si se puede.
     *
     * Para avisar en el Panel antes de que la cola se llene de fallos. [enviar] valida lo suyo
     * igualmente, porque entre una consulta y un envío puede cambiar cualquier cosa.
     */
    suspend fun motivoNoDisponible(): String?
}
