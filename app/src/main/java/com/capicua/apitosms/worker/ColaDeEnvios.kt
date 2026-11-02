// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.domain.cola.ControlDeCola
import com.capicua.apitosms.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La cola en serie sobre WorkManager.
 *
 * ## Un solo trabajo, y siempre uno detrás de otro
 * En SMSRelay cada reenvío era un trabajo propio, y varios podían correr a la vez. Aquí hay **un
 * único trabajo con nombre** ([Constants.WORKER_COLA]) que drena la cola mensaje a mensaje; dos
 * trabajos con el mismo nombre no corren nunca a la vez, y eso es lo que garantiza que haya un
 * solo SMS en vuelo.
 *
 * ## Por qué `APPEND_OR_REPLACE` y no `KEEP`
 * Con `KEEP`, un mensaje admitido justo cuando la pasada en marcha acaba de ver la cola vacía se
 * quedaría sin nadie que lo mandara hasta el siguiente rescate (30 s). Con `APPEND_OR_REPLACE`
 * queda otra pasada encadenada detrás de la actual. Y para no acumular una pasada por cada
 * petición de un cliente que manda de uno en uno, [despertar] no encadena nada si ya hay una
 * pendiente: con una basta.
 *
 * Sin restricción de red: un SMS sale por la radio del operador, no por datos.
 */
@Singleton
class ColaDeEnvios @Inject constructor(
    private val workManager: WorkManager,
    private val configDataStore: ConfigDataStore
) : ControlDeCola {

    /**
     * Por qué la cola no puede enviar ahora, si no puede (falta el permiso de SMS). Lo escribe
     * `ColaWorker` y lo enseña el Panel; null si no hay nada que decir.
     */
    private val _bloqueo = MutableStateFlow<String?>(null)
    val bloqueo: StateFlow<String?> = _bloqueo.asStateFlow()

    fun anotarBloqueo(motivo: String?) {
        _bloqueo.value = motivo
    }

    override suspend fun despertar() {
        val infos = workManager.getWorkInfosForUniqueWork(Constants.WORKER_COLA).await()
        val hayPendiente = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (hayPendiente) return
        encadenar()
    }

    /** Deja una pasada detrás de la actual, haya o no otra pendiente. La usa la propia pasada al agotar su tiempo. */
    fun encadenar() {
        val pasada = OneTimeWorkRequestBuilder<ColaWorker>().build()
        workManager.enqueueUniqueWork(Constants.WORKER_COLA, ExistingWorkPolicy.APPEND_OR_REPLACE, pasada)
    }

    /** true si hay una pasada corriendo ahora mismo. Con una corriendo, un `ENVIANDO` es legítimo. */
    suspend fun hayPasadaEnMarcha(): Boolean =
        workManager.getWorkInfosForUniqueWork(Constants.WORKER_COLA).await()
            .any { it.state == WorkInfo.State.RUNNING }

    override suspend fun estaPausada(): Boolean = configDataStore.config.first().colaPausada

    override suspend fun cambiarPausa(pausada: Boolean) = configDataStore.cambiarPausa(pausada)
}
