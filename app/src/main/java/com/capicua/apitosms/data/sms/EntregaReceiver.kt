// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsMessage
import com.capicua.apitosms.data.repository.LogRepository
import com.capicua.apitosms.data.repository.MensajeRepository
import com.capicua.apitosms.domain.cola.ProcesadorDeCola
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/**
 * Recibe los informes de entrega del operador y los aplica al mensaje.
 *
 * Va declarado en el manifiesto, **no exportado**, y no registrado en tiempo de ejecución como el
 * receptor de confirmaciones de `SmsSender`: el informe puede llegar horas después, cuando el
 * proceso que mandó el SMS ya no existe. Un receptor del manifiesto despierta a la aplicación.
 * No exportado significa que solo el sistema, a través del `PendingIntent` que creó esta misma
 * aplicación, puede entregarle nada.
 *
 * ## Qué dice el informe
 * El campo `status` de la PDU (TP-Status, 3GPP TS 23.040): `0x00`–`0x1F` entregado, `0x20`–`0x3F`
 * el operador sigue intentándolo (no se hace nada), `0x40` en adelante no se pudo entregar.
 */
@AndroidEntryPoint
class EntregaReceiver : BroadcastReceiver() {

    @Inject lateinit var mensajes: MensajeRepository
    @Inject lateinit var registro: LogRepository

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val estado = leerEstado(intent) ?: return
        if (estado in 0x20..0x3F) return

        val exito = estado < 0x20
        val pendiente = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val mensaje = mensajes.obtener(id) ?: return@launch
                val ahora = Instant.now()
                val actualizado = ProcesadorDeCola.aplicarEntrega(mensaje, exito, ahora) ?: return@launch
                mensajes.actualizar(actualizado)
                if (actualizado.estado != mensaje.estado) {
                    registro.registrar(LogEntry(
                        tipo = LogTipo.ENTREGA, mensajeId = id, destino = mensaje.destino,
                        clave = mensaje.claveNombre, timestamp = ahora,
                        detalle = if (actualizado.estado == EstadoMensaje.ENTREGADO) {
                            "Entregado en el teléfono de destino"
                        } else {
                            "No entregado: el operador devolvió el estado 0x%02X".format(estado)
                        }
                    ))
                }
            } catch (e: Exception) {
                Timber.e(e, "EntregaReceiver: no se pudo aplicar el informe de $id")
            } finally {
                pendiente.finish()
            }
        }
    }

    private fun leerEstado(intent: Intent): Int? = try {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val formato = intent.getStringExtra("format") ?: SmsMessage.FORMAT_3GPP
        SmsMessage.createFromPdu(pdu, formato)?.status
    } catch (e: Exception) {
        Timber.w(e, "EntregaReceiver: informe ilegible")
        null
    }

    companion object {
        private const val ACCION = "com.capicua.apitosms.SMS_ENTREGADO"
        private const val EXTRA_ID = "mensaje_id"

        /**
         * El `PendingIntent` de la parte [indice] del mensaje [id]. La URI de datos lo hace único:
         * con la misma acción y sin datos, Android devolvería el mismo `PendingIntent` para todos
         * los mensajes y el último sobrescribiría el id de los anteriores.
         */
        fun pendingIntent(context: Context, id: String, indice: Int): PendingIntent {
            val intent = Intent(ACCION, Uri.parse("apitosms://entrega/$id/$indice"))
                .setClass(context, EntregaReceiver::class.java)
                .putExtra(EXTRA_ID, id)
            // MUTABLE: el sistema tiene que poder añadir la PDU del informe al intent. Es seguro
            // porque el intent es explícito (lleva la clase) y el receptor no está exportado.
            // Antes de Android 12 todo PendingIntent era mutable y FLAG_MUTABLE no existía.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_ONE_SHOT or mutable)
        }
    }
}
