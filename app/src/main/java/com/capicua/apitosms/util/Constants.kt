// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.util

import com.capicua.apitosms.BuildConfig

/**
 * Constantes de infraestructura.
 *
 * Aquí solo vive lo que no tiene sentido que el operador ajuste. Los parámetros editables —pausa
 * entre mensajes, intentos, cuotas, retención— están en `AppConfig` y se leen de DataStore.
 */
object Constants {

    // WorkManager — nombres únicos de trabajo
    /** La cola en serie: un único trabajo con este nombre, nunca dos a la vez. */
    const val WORKER_COLA = "cola_envios"
    const val WORKER_HEALTH_TAG = "health_monitor"
    const val WORKER_ORPHAN_RESCUE_TAG = "orphan_rescue"

    // HealthMonitor (limpieza periódica; WorkManager no admite menos de 15 min)
    const val HEALTH_MONITOR_INTERVAL_MINUTES = 15L

    // OrphanRescue (se auto-encadena cada N segundos para bajar del mínimo de 15 min)
    const val ORPHAN_RESCUE_DELAY_SECONDS = 30L

    /**
     * Tiempo máximo de una pasada de la cola. WorkManager para un trabajo a los diez minutos; a
     * los ocho, la pasada termina limpia y deja otra encadenada detrás.
     */
    const val MAX_PASADA_COLA_MS = 8 * 60 * 1000L

    // Notificación del foreground service
    const val NOTIFICATION_CHANNEL_ID = "apitosms_servidor"
    const val NOTIFICATION_CHANNEL_NAME = "Servidor de la API"
    const val NOTIFICATION_ID_FOREGROUND = 1001

    /**
     * FileProvider authority (para exportar el registro y la copia de ajustes).
     *
     * Se deriva del `applicationId` y **no se escribe a mano**: una autoridad es única en todo
     * el dispositivo, y con un nombre fijo la variante debug y la release no podrían convivir
     * (`INSTALL_FAILED_CONFLICTING_PROVIDER`). El manifiesto usa el mismo valor con
     * `${applicationId}.provider`: si cambia uno, tiene que cambiar el otro.
     */
    val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider"
}
