// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.data.repository.LogRepository
import com.capicua.apitosms.data.repository.MensajeRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Worker periódico de limpieza: la retención.
 *
 * Cada [com.capicua.apitosms.util.Constants.HEALTH_MONITOR_INTERVAL_MINUTES] minutos, y por este
 * orden:
 * 1. Borra el **texto** de los mensajes terminados hace más de `retencionTextosDias`. La fila se
 *    queda: un cliente puede seguir preguntando por el estado de un mensaje viejo.
 * 2. Borra **enteros** los mensajes terminados hace más de `retencionMensajesDias`.
 * 3. Borra el registro de más de `retencionRegistroDias`.
 *
 * Lo que sigue en cola o en vuelo no se toca nunca, por antiguo que sea.
 */
@HiltWorker
class HealthMonitorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mensajes: MensajeRepository,
    private val registro: LogRepository,
    private val configDataStore: ConfigDataStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = configDataStore.config.first()
        val ahora = Instant.now()

        val textos = mensajes.purgarTextos(ahora.minus(config.retencionTextosDias.toLong().coerceAtLeast(1), ChronoUnit.DAYS))
        val borrados = mensajes.borrarTerminadosAntesDe(ahora.minus(config.retencionMensajesDias.toLong().coerceAtLeast(1), ChronoUnit.DAYS))
        registro.eliminarAntiguos(ahora.minus(config.retencionRegistroDias.toLong().coerceAtLeast(1), ChronoUnit.DAYS))

        if (textos > 0 || borrados > 0) {
            Timber.i("HealthMonitorWorker: $textos texto(s) purgado(s), $borrados mensaje(s) borrado(s)")
        }
        return Result.success()
    }
}
