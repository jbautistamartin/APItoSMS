// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.model

import java.time.Instant

/**
 * Un SMS que un cliente de la API pidió mandar: la fila de la outbox.
 *
 * Se persiste **antes** de responder `202` a quien lo pidió. A partir de ahí, lo que le pase al
 * proceso —un reinicio, un cierre forzado— no lo pierde: la cola lo encuentra en Room al volver.
 *
 * @property id el que devuelve la API y el que el cliente usa para consultar.
 * @property destino ya normalizado a E.164 (`+523310257136`).
 * @property texto null cuando la retención lo ha purgado: queda el metadato, no el contenido.
 * @property claveId qué clave de la API lo pidió; null solo en los envíos de prueba de la propia app.
 * @property claveNombre el nombre de esa clave, copiado: el mensaje sigue siendo legible aunque la
 *   clave se borre.
 * @property idempotencyKey la cabecera `Idempotency-Key` de la petición, si la traía. Única por
 *   clave. En un lote, la del lote con `#<índice>` detrás.
 * @property huellaPeticion SHA-256 del contenido pedido, para distinguir un reintento (mismo
 *   contenido) de una reutilización de la clave de idempotencia (otro contenido: `409`).
 * @property referencia texto libre del cliente, para cruzarlo con su sistema.
 * @property partes cuántos SMS se facturan.
 * @property partesEntregadas cuántas partes tienen ya informe de entrega positivo.
 * @property proximoIntentoEn antes de este instante la cola no lo toca (espera tras un error).
 */
data class MensajeSaliente(
    val id: String,
    val destino: String,
    val texto: String?,
    val claveId: String?,
    val claveNombre: String?,
    val idempotencyKey: String? = null,
    val huellaPeticion: String? = null,
    val referencia: String? = null,
    val estado: EstadoMensaje = EstadoMensaje.EN_COLA,
    val intentos: Int = 0,
    val ultimoError: String? = null,
    val partes: Int = 1,
    val partesEntregadas: Int = 0,
    val admitidoEn: Instant,
    val proximoIntentoEn: Instant = admitidoEn,
    val enviadoEn: Instant? = null,
    val entregadoEn: Instant? = null,
    val caducaEn: Instant? = null,
    val actualizadoEn: Instant = admitidoEn
)
