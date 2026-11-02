// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.mensajes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.apitosms.data.repository.LogRepository
import com.capicua.apitosms.data.repository.MensajeRepository
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.domain.model.MensajeSaliente
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Los mensajes salientes, de más nuevo a más viejo, con filtro por estado.
 *
 * Desde aquí se ve **todo**, de todas las claves: quien tiene el teléfono en la mano es el
 * administrador. Lo único que se puede hacer es cancelar lo que sigue en cola, igual que con
 * `DELETE /api/v1/mensajes/{id}`.
 */
@HiltViewModel
class MensajesViewModel @Inject constructor(
    private val mensajes: MensajeRepository,
    private val registro: LogRepository
) : ViewModel() {

    private val _filtro = MutableStateFlow<EstadoMensaje?>(null)
    val filtro: StateFlow<EstadoMensaje?> = _filtro.asStateFlow()

    val lista: Flow<List<MensajeSaliente>> = _filtro.flatMapLatest { mensajes.observar(it, LIMITE) }

    fun filtrar(estado: EstadoMensaje?) {
        _filtro.value = estado
    }

    /** @param alTerminar recibe false si el mensaje ya había salido de la cola. */
    fun cancelar(mensaje: MensajeSaliente, alTerminar: (Boolean) -> Unit) {
        viewModelScope.launch {
            val cancelado = mensajes.cancelar(mensaje.id, Instant.now())
            if (cancelado) {
                registro.registrar(LogEntry(
                    tipo = LogTipo.CANCELADO,
                    mensajeId = mensaje.id,
                    destino = mensaje.destino,
                    clave = mensaje.claveNombre,
                    detalle = "Cancelado desde el teléfono",
                    timestamp = Instant.now()
                ))
            }
            alTerminar(cancelado)
        }
    }

    private companion object {
        /** La pantalla es para mirar lo reciente; lo viejo se consulta por la API, paginado. */
        const val LIMITE = 300
    }
}
