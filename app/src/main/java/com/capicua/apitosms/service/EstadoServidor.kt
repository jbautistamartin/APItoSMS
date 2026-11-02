// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.service

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

        /** Encendido, pero sin wifi: escuchará en cuanto vuelva. */
        data object EsperandoRed : Estado

        data class Escuchando(val ip: String, val puerto: Int) : Estado {
            val url: String get() = "https://$ip:$puerto"
        }

        data class Error(val motivo: String) : Estado
    }

    private val _estado = MutableStateFlow<Estado>(Estado.Detenido)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    internal fun cambiar(nuevo: Estado) {
        _estado.value = nuevo
    }
}
