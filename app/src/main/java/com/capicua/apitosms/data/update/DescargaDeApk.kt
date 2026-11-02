// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baja el APK de un release y lo entrega al instalador del sistema.
 *
 * **La descarga la hace `DownloadManager`**, no la aplicación: es un servicio del sistema que
 * sobrevive a que el proceso muera, reintenta por su cuenta y enseña su propia notificación con
 * el progreso. Escribir eso a mano para un fichero de tres megas sería trabajo para volver a
 * tener lo que ya está. El destino es la carpeta privada de la aplicación en el almacenamiento
 * externo, que **no necesita ningún permiso**.
 *
 * **Instalar en silencio no se puede**, y no es una limitación que se pueda rodear: solo lo hace
 * una aplicación con privilegios de dueño del dispositivo. Lo que hay es pedirle al sistema que
 * abra su propio diálogo de instalación, que exige `REQUEST_INSTALL_PACKAGES` en el manifiesto y,
 * desde Android 8, que el usuario haya autorizado a *esta* aplicación a instalar paquetes.
 *
 * Y un detalle que es a la vez la garantía y la trampa: Android solo deja actualizar un APK
 * **firmado con la misma clave**. Nadie puede colar un APK falso encima de este, y por eso no
 * hace falta comprobar hashes aquí; pero tampoco entra encima de una instalación firmada con
 * otra clave (una compilación de depuración, por ejemplo), que falla con
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Eso lo cuenta la pantalla antes de empezar.
 */
@Singleton
class DescargaDeApk @Inject constructor(
    @ApplicationContext private val contexto: Context
) {
    /** Cómo va la descarga. */
    sealed interface Paso {
        data class Progreso(val porcentaje: Int) : Paso
        data class Lista(val uri: Uri) : Paso
        data class Fallo(val motivo: String) : Paso
    }

    /**
     * Encola la descarga y va emitiendo su progreso hasta que termina.
     *
     * Se consulta a `DownloadManager` cada [PASO_MS] en lugar de esperar su *broadcast* de
     * descarga completada: así se puede pintar el porcentaje, y el flujo se cancela solo cuando
     * la pantalla desaparece.
     */
    fun descargar(disponible: ResultadoActualizacion.Disponible): Flow<Paso> = flow {
        val url = disponible.apkUrl
        if (url.isNullOrBlank()) {
            emit(Paso.Fallo("Este release no publica ningún APK"))
            return@flow
        }

        val gestor = contexto.getSystemService(DownloadManager::class.java)
        if (gestor == null) {
            emit(Paso.Fallo("El dispositivo no tiene gestor de descargas"))
            return@flow
        }

        val nombre = disponible.apkNombre ?: "apitosms-${disponible.version}.apk"

        // Si quedó una descarga anterior con el mismo nombre, DownloadManager añadiría «-1» al
        // fichero y acabaríamos instalando el de antes.
        runCatching { File(contexto.getExternalFilesDir(null), nombre).delete() }

        val peticion = DownloadManager.Request(Uri.parse(url))
            .setTitle(nombre)
            .setMimeType(TIPO_APK)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(contexto, null, nombre)

        val id = try {
            gestor.enqueue(peticion)
        } catch (e: Exception) {
            Timber.w(e, "No se pudo encolar la descarga")
            emit(Paso.Fallo(e.message ?: "no se pudo empezar la descarga"))
            return@flow
        }

        while (currentCoroutineContext().isActive) {
            val consulta = DownloadManager.Query().setFilterById(id)
            val fila = runCatching { gestor.query(consulta) }.getOrNull()

            if (fila == null || !fila.moveToFirst()) {
                fila?.close()
                emit(Paso.Fallo("la descarga desapareció"))
                return@flow
            }

            val estado = fila.getInt(fila.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bajados = fila.getLong(
                fila.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            )
            val total = fila.getLong(
                fila.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )
            val razon = fila.getInt(fila.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            fila.close()

            when (estado) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = gestor.getUriForDownloadedFile(id)
                    if (uri == null) {
                        emit(Paso.Fallo("la descarga terminó sin fichero"))
                    } else {
                        emit(Paso.Lista(uri))
                    }
                    return@flow
                }

                DownloadManager.STATUS_FAILED -> {
                    emit(Paso.Fallo("la descarga falló (código $razon)"))
                    return@flow
                }

                else -> {
                    val porcentaje = if (total > 0) ((bajados * 100) / total).toInt() else 0
                    emit(Paso.Progreso(porcentaje))
                    delay(PASO_MS)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Si el sistema deja a esta aplicación abrir el instalador.
     *
     * Hasta Android 7 bastaba el permiso del manifiesto; desde el 8 hay que haberlo autorizado
     * para esta aplicación en concreto, y eso solo se concede desde los ajustes del sistema.
     */
    fun puedeInstalar(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            contexto.packageManager.canRequestPackageInstalls()

    /** La pantalla de ajustes donde se autoriza a esta aplicación a instalar paquetes. */
    fun intentDeAutorizacion(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${contexto.packageName}")
    )

    /** El diálogo de instalación del sistema para el APK ya descargado. */
    fun intentDeInstalacion(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, TIPO_APK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private companion object {
        const val TIPO_APK = "application/vnd.android.package-archive"
        const val PASO_MS = 400L
    }
}
