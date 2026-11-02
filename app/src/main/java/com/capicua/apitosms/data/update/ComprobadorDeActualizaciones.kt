// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.update

import com.capicua.apitosms.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Lo que puede contestar [ComprobadorDeActualizaciones.comprobar]. */
sealed interface ResultadoActualizacion {

    /** La versión instalada es la última publicada. */
    data object AlDia : ResultadoActualizacion

    /** Hay una versión más nueva publicada. */
    data class Disponible(
        val version: VersionPublicada,
        val etiqueta: String,
        val pagina: String,
        val apkUrl: String?,
        val apkNombre: String?,
        val bytes: Long
    ) : ResultadoActualizacion

    /** No tocaba preguntar todavía, o el aviso está apagado. No es un error. */
    data object SinPreguntar : ResultadoActualizacion

    /** Se intentó y no se pudo: sin red, GitHub caído, respuesta ilegible. */
    data class NoSePudo(val motivo: String) : ResultadoActualizacion
}

/**
 * Pregunta a GitHub si hay publicada una versión más nueva que la instalada.
 *
 * Cuatro decisiones que no son casuales:
 *
 * 1. **Se compara con números** ([VersionPublicada]), nunca como texto.
 * 2. **Una vez al día como mucho**, y con `If-None-Match`. El límite de la API sin autenticar
 *    son 60 peticiones por hora e IP, y una respuesta 304 no gasta cuota. El botón de la
 *    pantalla de «Acerca de» salta el intervalo, porque quien lo pulsa quiere una respuesta
 *    ahora, no la de ayer.
 * 3. **No lleva ningún token.** El repositorio es público y meter una credencial en un APK es
 *    regalarla.
 * 4. **Un 404 no es un error**: significa que el repositorio todavía no tiene releases.
 *
 * Esto solo *mira*. Bajar e instalar es [DescargaDeApk], y no se hace sin que alguien lo pida.
 */
@Singleton
class ComprobadorDeActualizaciones @Inject constructor(
    private val estado: EstadoActualizacion
) {
    suspend fun comprobar(forzar: Boolean): ResultadoActualizacion = withContext(Dispatchers.IO) {
        val memoria = estado.leer()
        val ahora = System.currentTimeMillis()

        if (!forzar) {
            if (!memoria.avisar) return@withContext ResultadoActualizacion.SinPreguntar
            if (ahora - memoria.ultimaComprobacionMs < INTERVALO_MS) {
                // No toca preguntar, pero lo que se encontró ayer sigue valiendo.
                return@withContext recordada(memoria) ?: ResultadoActualizacion.SinPreguntar
            }
        }

        var conexion: HttpURLConnection? = null
        try {
            conexion = (URL(URL_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                // Sin User-Agent, api.github.com responde 403. No es opcional.
                setRequestProperty("User-Agent", "APItoSMS/${BuildConfig.VERSION_NAME}")
                if (!forzar && !memoria.etag.isNullOrBlank()) {
                    setRequestProperty("If-None-Match", memoria.etag)
                }
            }

            val codigo = conexion.responseCode
            val etag = conexion.getHeaderField("ETag")

            when {
                codigo == HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    estado.anotarComprobacion(ahora, memoria.etag)
                    recordada(memoria) ?: ResultadoActualizacion.AlDia
                }

                codigo == HttpURLConnection.HTTP_NOT_FOUND -> {
                    // Repositorio sin releases publicados todavía: no hay de qué avisar.
                    estado.anotarComprobacion(ahora, null)
                    estado.olvidarNovedad()
                    ResultadoActualizacion.AlDia
                }

                codigo != HttpURLConnection.HTTP_OK -> {
                    estado.anotarComprobacion(ahora, memoria.etag)
                    ResultadoActualizacion.NoSePudo("GitHub respondió HTTP $codigo")
                }

                else -> {
                    val cuerpo = conexion.inputStream.bufferedReader().use { it.readText() }
                    estado.anotarComprobacion(ahora, etag)
                    interpretar(cuerpo)
                }
            }
        } catch (e: Exception) {
            // Sin red o con el DNS caído esto es lo normal, no una avería que haya que contar
            // con un diálogo: se registra y la pantalla lo dice en una línea si alguien mira.
            Timber.d(e, "No se pudo comprobar si hay versión nueva")
            estado.anotarComprobacion(ahora, memoria.etag)
            ResultadoActualizacion.NoSePudo(e.message ?: "error de red")
        } finally {
            conexion?.disconnect()
        }
    }

    private suspend fun interpretar(cuerpo: String): ResultadoActualizacion {
        val release = try {
            json.decodeFromString<ReleaseJson>(cuerpo)
        } catch (e: Exception) {
            Timber.w(e, "La respuesta de GitHub no se pudo interpretar")
            return ResultadoActualizacion.NoSePudo("respuesta ilegible")
        }

        val publicada = VersionPublicada.interpretar(release.etiqueta)
        val instalada = VersionPublicada.interpretar(BuildConfig.VERSION_NAME)

        if (publicada == null || instalada == null || publicada <= instalada) {
            estado.olvidarNovedad()
            return ResultadoActualizacion.AlDia
        }

        // El APK del release lleva el nombre de la aplicación y la versión; si algún día ese
        // nombre cambia, lo que sigue valiendo es que sea el único .apk del release.
        val apk = release.assets.firstOrNull { it.nombre.endsWith(".apk", ignoreCase = true) }

        val disponible = ResultadoActualizacion.Disponible(
            version = publicada,
            etiqueta = release.etiqueta,
            pagina = release.pagina,
            apkUrl = apk?.url,
            apkNombre = apk?.nombre,
            bytes = apk?.bytes ?: 0L
        )

        // Se apunta para poder seguir avisando mañana sin volver a preguntar.
        estado.anotarNovedad(
            etiqueta = disponible.etiqueta,
            pagina = disponible.pagina,
            apkUrl = disponible.apkUrl,
            apkNombre = disponible.apkNombre,
            bytes = disponible.bytes
        )

        return disponible
    }

    @Serializable
    private data class ReleaseJson(
        @SerialName("tag_name") val etiqueta: String = "",
        @SerialName("html_url") val pagina: String = "",
        val assets: List<AssetJson> = emptyList()
    )

    @Serializable
    private data class AssetJson(
        @SerialName("name") val nombre: String = "",
        @SerialName("browser_download_url") val url: String = "",
        @SerialName("size") val bytes: Long = 0L
    )

    /**
     * El último release que se encontró, mientras siga siendo más nuevo que lo instalado.
     *
     * Es lo que mantiene el aviso puesto entre aperturas de la aplicación. También es la
     * respuesta correcta a un **304**: «el último release no ha cambiado» no significa que sea
     * el que está instalado. Y se cae solo cuando toca — en cuanto se instala la versión de la
     * que se avisaba, la comparación deja de cumplirse.
     */
    private fun recordada(memoria: EstadoActualizacion.Memoria): ResultadoActualizacion.Disponible? {
        val guardada = VersionPublicada.interpretar(memoria.etiqueta) ?: return null
        val instalada = VersionPublicada.interpretar(BuildConfig.VERSION_NAME) ?: return null
        if (guardada <= instalada) return null

        return ResultadoActualizacion.Disponible(
            version = guardada,
            etiqueta = memoria.etiqueta ?: "",
            pagina = memoria.pagina ?: "",
            apkUrl = memoria.apkUrl,
            apkNombre = memoria.apkNombre,
            bytes = memoria.bytes
        )
    }

    private companion object {
        const val URL_API = "https://api.github.com/repos/jbautistamartin/APItoSMS/releases/latest"
        const val INTERVALO_MS = 24 * 60 * 60 * 1000L
        const val TIMEOUT_MS = 15_000

        /** La respuesta de GitHub trae decenas de campos que aquí no interesan. */
        val json = Json { ignoreUnknownKeys = true }
    }
}
