// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.service

import com.capicua.apitosms.domain.red.TipoEnlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * El estado del servidor, para el Panel y la notificación.
 *
 * Es un objeto y no un singleton de Hilt porque lo escribe un `Service` y lo lee un `Worker` y un
 * `ViewModel`, y los tres viven en el mismo proceso: no hay nada que inyectar.
 */
object EstadoServidor {

    sealed interface Estado {
        data object Detenido : Estado

        /** Encendido, pero sin wifi ni cable: escuchará en cuanto haya dónde. */
        data object EsperandoRed : Estado

        /**
         * Escuchando en uno o varios sitios: la wifi o el Ethernet y, si están encendidos, el
         * anclaje USB y `127.0.0.1` para adb. El primero es el principal: el que enseñan la
         * notificación y el botón de copiar del Panel.
         */
        data class Escuchando(val puntos: List<Punto>) : Estado {
            init { require(puntos.isNotEmpty()) { "Escuchando sin ningún punto" } }
            val url: String get() = puntos.first().url
        }

        /** Encendido, pero no se pudo escuchar en ningún sitio. */
        data class Error(val motivo: String) : Estado
    }

    /** Una dirección en la que se escucha, con por dónde llega. */
    data class Punto(val tipo: TipoEnlace, val ip: String, val puerto: Int) {
        val url: String get() = "https://$ip:$puerto"
    }

    private val _estado = MutableStateFlow<Estado>(Estado.Detenido)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    internal fun cambiar(nuevo: Estado) {
        _estado.value = nuevo
    }
}
