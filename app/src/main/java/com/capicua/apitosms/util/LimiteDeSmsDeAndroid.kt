// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.util

import android.content.Context
import android.provider.Settings

/**
 * El tope de SMS salientes que Android impone a cada aplicación: **30 cada 30 minutos** por
 * defecto. Pasado el tope, el sistema enseña un diálogo pidiendo permiso para cada SMS más, y en
 * un teléfono desatendido nadie lo pulsa: la cola se queda esperando el informe de envío hasta
 * agotar el tiempo.
 *
 * El tope se sube por ADB, y no hay otra forma:
 *
 * ```
 * adb shell settings put global sms_outgoing_check_max_count 10000
 * adb shell settings put global sms_outgoing_check_interval_ms 60000
 * ```
 *
 * Aquí solo se **lee**, para avisar en el Panel. Desde Android 12 leer una clave global que no es
 * pública puede lanzar `SecurityException` o devolver null: en los dos casos se responde «no se
 * sabe», y el Panel enseña el aviso genérico.
 */
object LimiteDeSmsDeAndroid {

    const val MAXIMO_POR_DEFECTO = 30
    const val COMANDO_MAXIMO = "adb shell settings put global sms_outgoing_check_max_count 10000"
    const val COMANDO_INTERVALO = "adb shell settings put global sms_outgoing_check_interval_ms 60000"

    /** El máximo configurado, o null si no está configurado o no se puede leer. */
    fun maximo(context: Context): Int? = runCatching {
        Settings.Global.getString(context.contentResolver, "sms_outgoing_check_max_count")?.toIntOrNull()
    }.getOrNull()

    /** true solo si se sabe que el tope se ha subido por encima del de fábrica. */
    fun estaAmpliado(context: Context): Boolean = (maximo(context) ?: 0) > MAXIMO_POR_DEFECTO
}
