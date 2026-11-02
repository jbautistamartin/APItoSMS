// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.data.repository.MensajeRepository
import com.capicua.apitosms.domain.cola.ProcesadorDeCola
import com.capicua.apitosms.service.ApiServerService
import com.capicua.apitosms.service.EstadoServidor
import com.capicua.apitosms.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Worker de rescate: recoge lo que se quedó a medias, cada [Constants.ORPHAN_RESCUE_DELAY_SECONDS]
 * segundos. Se auto-encadena porque WorkManager no admite periódicos de menos de 15 minutos.
 *
 * Tiene tres cosas que vigilar:
 *
 * 1. **Mensajes `ENVIANDO` sin pasada en marcha.** El proceso murió en mitad de un envío. Se
 *    devuelven a la cola (decisión C, ver [ProcesadorDeCola.rescatarHuerfanos]).
 * 2. **Mensajes en cola sin nadie que los mande.** Un reintento cuya hora ha llegado, o una pasada
 *    que se perdió. Se despierta la cola.
 * 3. **El servidor caído.** Si el ajuste dice que debe estar encendido y no lo está —un fabricante
 *    que mata servicios en primer plano, que los hay—, se vuelve a arrancar. Android permite
 *    arrancar un servicio en primer plano desde segundo plano a una aplicación exenta de la
 *    optimización de batería, que es como tiene que estar este teléfono.
 */
@HiltWorker
class OrphanRescueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mensajes: MensajeRepository,
    private val procesador: ProcesadorDeCola,
    private val cola: ColaDeEnvios,
    private val configDataStore: ConfigDataStore,
    private val workManager: WorkManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            if (!cola.hayPasadaEnMarcha()) {
                val rescatados = procesador.rescatarHuerfanos()
                if (rescatados > 0) Timber.i("OrphanRescueWorker: $rescatados mensaje(s) rescatado(s)")

                val config = configDataStore.config.first()
                if (!config.colaPausada && mensajes.siguienteElegible(Instant.now()) != null) {
                    cola.despertar()
                }
            }
            vigilarServidor()
        } catch (e: Exception) {
            Timber.e(e, "OrphanRescueWorker: fallo en el rescate")
        }
        programarSiguiente()
        return Result.success()
    }

    private suspend fun vigilarServidor() {
        val activo = configDataStore.config.first().servidorActivo
        if (activo && EstadoServidor.estado.value is EstadoServidor.Estado.Detenido) {
            try {
                ApiServerService.iniciar(applicationContext)
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException sin la exención de batería.
                Timber.w(e, "OrphanRescueWorker: no se pudo rearrancar el servidor")
            }
        }
    }

    /** REPLACE: si ya hubiera una iteración encolada —tras un doble arranque—, la sustituye. */
    private fun programarSiguiente() {
        val siguiente = OneTimeWorkRequestBuilder<OrphanRescueWorker>()
            .setInitialDelay(Constants.ORPHAN_RESCUE_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(Constants.WORKER_ORPHAN_RESCUE_TAG, ExistingWorkPolicy.REPLACE, siguiente)
    }
}
