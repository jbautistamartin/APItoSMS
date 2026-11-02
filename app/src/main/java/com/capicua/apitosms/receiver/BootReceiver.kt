// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.service.ApiServerService
import com.capicua.apitosms.util.Constants
import com.capicua.apitosms.worker.HealthMonitorWorker
import com.capicua.apitosms.worker.OrphanRescueWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Se ejecuta al arrancar el dispositivo o reinstalar la app.
 * WorkManager persiste sus colas en disco, pero el Worker periódico
 * necesita ser re-programado explícitamente tras reinicios en algunos fabricantes.
 *
 * Hace dos cosas: reprograma los trabajos de fondo —el rescate de huérfanos, que es también el
 * que despierta la cola, y el de limpieza— y **vuelve a encender el servidor** si estaba
 * encendido antes de apagar el teléfono. Sin esto, tras un corte de luz la API no contestaría
 * hasta que alguien abriese la aplicación. `BOOT_COMPLETED` es uno de los casos en que Android
 * deja arrancar un servicio en primer plano desde segundo plano.
 *
 * Hay dos motivos por los que, aun así, puede no arrancar nada tras un reinicio, y ninguno se
 * arregla desde aquí:
 *
 * 1. **El inicio automático del fabricante.** MIUI, HyperOS, EMUI y compañía no entregan el
 *    arranque a lo que no esté en su lista, que viene vacía. Ver [InicioAutomatico]: la
 *    pantalla de ajustes lleva hasta ella.
 * 2. **El estado «detenida».** Una aplicación recién instalada que nadie ha abierto todavía, o
 *    una que se ha forzado a detener, no recibe ningún broadcast —tampoco el del arranque— hasta que
 *    se abre una primera vez. Es de Android y no tiene vuelta: después de instalar hay que
 *    abrirla una vez.
 *
 * Se escuchan también los arranques rápidos propietarios (`QUICKBOOT_POWERON`), que algunas
 * ROM mandan **en lugar** de `BOOT_COMPLETED` cuando el apagado anterior fue el suyo.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var configDataStore: ConfigDataStore

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACCIONES) return

        Timber.d("BootReceiver: $action — programando workers")
        programarHealthMonitor(context)
        iniciarOrphanRescue(context)

        val pendiente = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (configDataStore.config.first().servidorActivo) ApiServerService.iniciar(context)
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: no se pudo encender el servidor")
            } finally {
                pendiente.finish()
            }
        }
    }

    companion object {

        /** Lo que se considera «ha arrancado el teléfono» o «me acaban de reinstalar». */
        private val ACCIONES = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        /** Programa (o reemplaza) el Worker periódico de limpieza (mínimo 15 min). */
        fun programarHealthMonitor(context: Context) {
            val solicitud = PeriodicWorkRequestBuilder<HealthMonitorWorker>(
                Constants.HEALTH_MONITOR_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.WORKER_HEALTH_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                solicitud
            )
        }

        /**
         * Lanza la primera iteración del bucle de rescate de SMS huérfanos.
         * El propio [OrphanRescueWorker] se auto-encadena cada 30 segundos.
         * Se usa KEEP para no interrumpir una ejecución ya en curso.
         */
        fun iniciarOrphanRescue(context: Context) {
            val solicitud = OneTimeWorkRequestBuilder<OrphanRescueWorker>().build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                Constants.WORKER_ORPHAN_RESCUE_TAG,
                ExistingWorkPolicy.KEEP,
                solicitud
            )
        }
    }
}