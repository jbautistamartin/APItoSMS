// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.cola

import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.domain.model.MensajeSaliente
import com.capicua.apitosms.domain.seguridad.Reloj
import com.capicua.apitosms.domain.transport.EnviadorDeMensajes
import com.capicua.apitosms.domain.transport.ResultadoEnvio
import java.time.Duration
import java.time.Instant

/**
 * La cola en serie: saca **un** mensaje, lo manda, espera el desenlace y lo apunta.
 *
 * Quien la mueve es `ColaWorker`, que llama a [procesarSiguiente] en bucle y duerme la pausa entre
 * mensajes. Esta clase no sabe de WorkManager ni de Android, y por eso se prueba entera en la JVM.
 *
 * ## Por qué uno detrás de otro
 * Es lo que se pidió, y además es lo que pide el medio: el operador tolera mal ráfagas de SMS
 * desde una línea, Android pide confirmación pasados 30 en 30 minutos, y un mensaje en vuelo
 * cada vez hace que el orden de salida sea el de llegada.
 *
 * ## Un error transitorio no bloquea la cola (decisión B)
 * El mensaje vuelve a `EN_COLA` con un `proximoIntentoEn` en el futuro y pasa el siguiente. El
 * orden deja de ser estricto, a cambio de que un número sin cobertura no pare a todos los demás.
 *
 * ## Idempotencia
 * [AlmacenDeMensajes.reclamar] pasa a `ENVIANDO` solo lo que sigue `EN_COLA`, de modo que un
 * mensaje cancelado mientras esperaba su turno no sale, y dos pasadas simultáneas —no debería
 * haberlas, pero WorkManager no lo garantiza al cien por cien— no mandan el mismo.
 */
class ProcesadorDeCola(
    private val almacen: AlmacenDeMensajes,
    private val enviador: EnviadorDeMensajes,
    private val ajustes: suspend () -> AjustesDeCola,
    private val registro: RegistroDeAuditoria,
    private val reloj: Reloj = Reloj.SISTEMA
) {

    sealed interface Paso {
        /** Se tocó un mensaje. [huboEnvio]: llegó a la radio, y toca dormir la pausa. */
        data class Procesado(val mensaje: MensajeSaliente, val huboEnvio: Boolean) : Paso

        /** No queda nada elegible ahora (puede haber mensajes esperando su reintento). */
        data object NadaPendiente : Paso

        data object Pausada : Paso

        /**
         * El transporte no puede enviar (falta el permiso de SMS, no hay radio). No se toca ningún
         * mensaje: si la cola los intentara, gastaría sus intentos contra algo que no va a cambiar
         * solo, y acabarían todos `FALLIDO` por un permiso olvidado.
         */
        data class Bloqueada(val motivo: String) : Paso
    }

    suspend fun procesarSiguiente(): Paso {
        val config = ajustes()
        if (config.pausada) return Paso.Pausada

        val ahora = reloj.ahora()
        val mensaje = almacen.siguienteElegible(ahora) ?: return Paso.NadaPendiente
        enviador.motivoNoDisponible()?.let { return Paso.Bloqueada(it) }

        // Caducado mientras esperaba: no se manda. Un código de un solo uso que llega tarde
        // confunde más que no llegar.
        mensaje.caducaEn?.let { caduca ->
            if (!ahora.isBefore(caduca)) {
                val cerrado = mensaje.copy(
                    estado = EstadoMensaje.CADUCADO,
                    ultimoError = "caducó antes de salir",
                    actualizadoEn = ahora
                )
                if (almacen.reclamar(mensaje.id, ahora)) {
                    almacen.actualizar(cerrado)
                    registrar(LogTipo.CANCELADO, cerrado, "Caducado sin salir (caducaEn $caduca)")
                }
                return Paso.Procesado(cerrado, huboEnvio = false)
            }
        }

        if (!almacen.reclamar(mensaje.id, ahora)) {
            // Lo canceló alguien entre la lectura y aquí. No es un error.
            return Paso.Procesado(mensaje, huboEnvio = false)
        }

        val texto = mensaje.texto
        if (texto.isNullOrEmpty()) {
            val fallido = mensaje.copy(
                estado = EstadoMensaje.FALLIDO,
                ultimoError = "el texto ya no está (purgado por la retención)",
                actualizadoEn = ahora
            )
            almacen.actualizar(fallido)
            registrar(LogTipo.ERROR, fallido, "Fallido: ${fallido.ultimoError}")
            return Paso.Procesado(fallido, huboEnvio = false)
        }

        val intento = mensaje.intentos + 1
        val resultado = try {
            enviador.enviar(mensaje.destino, texto, config.timeoutEnvioSegundos, seguimiento = mensaje.id)
        } catch (e: Exception) {
            ResultadoEnvio.ErrorTransitorio("${e.javaClass.simpleName}: ${e.message}")
        }
        val despues = reloj.ahora()

        val final = when (resultado) {
            is ResultadoEnvio.Enviado -> mensaje.copy(
                estado = EstadoMensaje.ENVIADO,
                intentos = intento,
                ultimoError = null,
                enviadoEn = despues,
                actualizadoEn = despues
            ).also { registrar(LogTipo.ENVIADO, it, "Enviado (${it.partes} parte(s), intento $intento)") }

            is ResultadoEnvio.ErrorPermanente -> mensaje.copy(
                estado = EstadoMensaje.FALLIDO,
                intentos = intento,
                ultimoError = resultado.motivo,
                actualizadoEn = despues
            ).also { registrar(LogTipo.ERROR, it, "Fallo permanente: ${resultado.motivo}") }

            is ResultadoEnvio.ErrorTransitorio ->
                if (intento >= config.maxIntentos) {
                    mensaje.copy(
                        estado = EstadoMensaje.FALLIDO,
                        intentos = intento,
                        ultimoError = "${resultado.motivo} (agotados $intento intentos)",
                        actualizadoEn = despues
                    ).also { registrar(LogTipo.ERROR, it, "Fallido tras $intento intentos: ${resultado.motivo}") }
                } else {
                    val espera = esperaTras(intento, config, resultado.esperaSegundos)
                    mensaje.copy(
                        estado = EstadoMensaje.EN_COLA,
                        intentos = intento,
                        ultimoError = resultado.motivo,
                        proximoIntentoEn = despues.plus(espera),
                        actualizadoEn = despues
                    ).also {
                        registrar(
                            LogTipo.ERROR, it,
                            "Fallo transitorio (intento $intento de ${config.maxIntentos}): " +
                                "${resultado.motivo}; se reintenta en ${espera.seconds} s"
                        )
                    }
                }
        }
        almacen.actualizar(final)
        return Paso.Procesado(final, huboEnvio = true)
    }

    /**
     * Devuelve a la cola los mensajes que se quedaron `ENVIANDO` porque el proceso murió en mitad
     * del envío (decisión C: **al menos una vez**).
     *
     * No hay forma de saber si ese SMS llegó a salir: la radio pudo mandarlo un instante antes de
     * que el proceso muriera. Reenviarlo puede duplicarlo; no reenviarlo puede perderlo. Se eligió
     * duplicar, como en SMSRelay, y el error que queda en el mensaje lo dice.
     *
     * Solo se llama cuando no hay ninguna pasada de la cola en marcha: con una en marcha, el
     * `ENVIANDO` es legítimo.
     */
    suspend fun rescatarHuerfanos(): Int {
        val ahora = reloj.ahora()
        val huerfanos = almacen.enviando()
        huerfanos.forEach { mensaje ->
            val rescatado = mensaje.copy(
                estado = EstadoMensaje.EN_COLA,
                intentos = mensaje.intentos + 1,
                ultimoError = MOTIVO_RESCATE,
                proximoIntentoEn = ahora,
                actualizadoEn = ahora
            )
            almacen.actualizar(rescatado)
            registrar(LogTipo.SISTEMA, rescatado, "Rescatado: $MOTIVO_RESCATE")
        }
        return huerfanos.size
    }

    /**
     * Espera antes del siguiente intento: lineal, como el backoff de SMSRelay, con tope.
     * Si el medio pidió una espera mayor, gana la suya.
     */
    private fun esperaTras(intento: Int, config: AjustesDeCola, pedida: Int?): Duration {
        val lineal = Duration.ofSeconds(config.intervaloReintentoSegundos.toLong() * intento)
            .coerceAtMost(ESPERA_MAXIMA)
        val delMedio = pedida?.takeIf { it > 0 }?.let { Duration.ofSeconds(it.toLong()) }
        return if (delMedio != null && delMedio > lineal) delMedio else lineal
    }

    private suspend fun registrar(tipo: LogTipo, mensaje: MensajeSaliente, detalle: String) {
        registro.registrar(
            LogEntry(
                tipo = tipo,
                mensajeId = mensaje.id,
                destino = mensaje.destino,
                clave = mensaje.claveNombre,
                detalle = detalle,
                timestamp = reloj.ahora()
            )
        )
    }

    companion object {
        val ESPERA_MAXIMA: Duration = Duration.ofMinutes(30)

        const val MOTIVO_RESCATE =
            "la aplicación se cerró mientras se enviaba; se reintenta y puede llegar duplicado"

        /** Aplica un informe de entrega a [mensaje]. null si no cambia nada. */
        fun aplicarEntrega(mensaje: MensajeSaliente, exito: Boolean, ahora: Instant): MensajeSaliente? {
            if (mensaje.estado != EstadoMensaje.ENVIADO) return null
            if (!exito) {
                return mensaje.copy(
                    estado = EstadoMensaje.NO_ENTREGADO,
                    ultimoError = "el operador informa de que no se pudo entregar",
                    actualizadoEn = ahora
                )
            }
            val entregadas = mensaje.partesEntregadas + 1
            return if (entregadas >= mensaje.partes) {
                mensaje.copy(
                    estado = EstadoMensaje.ENTREGADO,
                    partesEntregadas = entregadas,
                    entregadoEn = ahora,
                    actualizadoEn = ahora
                )
            } else {
                mensaje.copy(partesEntregadas = entregadas, actualizadoEn = ahora)
            }
        }
    }
}
