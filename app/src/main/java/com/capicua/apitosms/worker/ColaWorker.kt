// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.worker

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.domain.cola.ProcesadorDeCola
import com.capicua.apitosms.domain.cola.RegistroDeAuditoria
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant

/**
 * Una pasada de la cola: manda mensajes uno detrás de otro hasta que no queda ninguno elegible.
 *
 * Toda la lógica de un envío está en [ProcesadorDeCola]; esto es solo el bucle, la pausa entre
 * mensajes y el límite de tiempo de WorkManager.
 *
 * Termina cuando:
 * - **No queda nada elegible.** Puede quedar algo esperando su reintento: lo despierta el rescate
 *   ([OrphanRescueWorker]) cada 30 s, cuando le llega la hora.
 * - **La cola está pausada.** Reanudar despierta otra pasada.
 * - **El transporte no puede enviar** (falta el permiso). El Panel lo enseña, y el rescate
 *   vuelve a probar cada 30 s.
 * - **Se acercan los diez minutos** que WorkManager da a un trabajo. Deja otra pasada encadenada
 *   y termina limpia, en lugar de que el sistema la corte a mitad de un envío.
 */
@HiltWorker
class ColaWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val procesador: ProcesadorDeCola,
    private val cola: ColaDeEnvios,
    private val configDataStore: ConfigDataStore,
    private val registro: RegistroDeAuditoria
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val inicio = SystemClock.elapsedRealtime()

        while (!isStopped) {
            when (val paso = procesador.procesarSiguiente()) {
                is ProcesadorDeCola.Paso.Procesado -> {
                    anotarBloqueo(null)
                    if (paso.huboEnvio) {
                        val pausa = configDataStore.config.first().pausaEntreMensajesSegundos
                        if (pausa > 0) delay(pausa * 1000L)
                    }
                }

                ProcesadorDeCola.Paso.NadaPendiente, ProcesadorDeCola.Paso.Pausada -> {
                    anotarBloqueo(null)
                    return Result.success()
                }

                is ProcesadorDeCola.Paso.Bloqueada -> {
                    anotarBloqueo(paso.motivo)
                    return Result.success()
                }
            }

            if (SystemClock.elapsedRealtime() - inicio > Constants.MAX_PASADA_COLA_MS) {
                Timber.d("ColaWorker: fin de pasada por tiempo, queda otra encadenada")
                cola.encadenar()
                return Result.success()
            }
        }
        return Result.success()
    }

    /** Anota el bloqueo para el Panel, y en el registro solo cuando cambia: no cada 30 s. */
    private suspend fun anotarBloqueo(motivo: String?) {
        if (cola.bloqueo.value == motivo) return
        cola.anotarBloqueo(motivo)
        registro.registrar(LogEntry(
            tipo = LogTipo.SISTEMA,
            timestamp = Instant.now(),
            detalle = motivo?.let { "La cola no puede enviar: $it" } ?: "La cola vuelve a poder enviar"
        ))
    }
}
