// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.about

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.apitosms.data.update.ComprobadorDeActualizaciones
import com.capicua.apitosms.data.update.DescargaDeApk
import com.capicua.apitosms.data.update.EstadoActualizacion
import com.capicua.apitosms.data.update.ResultadoActualizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * El estado de la tarjeta «Actualizaciones» de «Acerca de».
 *
 * Al crearse lanza una comprobación **respetando el intervalo**: abrir la pantalla diez veces en
 * una tarde no son diez preguntas a GitHub. La del botón sí fuerza, porque es una pregunta
 * explícita.
 */
@HiltViewModel
class ActualizacionViewModel @Inject constructor(
    private val comprobador: ComprobadorDeActualizaciones,
    private val descargas: DescargaDeApk,
    private val estado: EstadoActualizacion
) : ViewModel() {

    data class Estado(
        val avisar: Boolean = true,
        val comprobando: Boolean = false,
        val resultado: ResultadoActualizacion? = null,
        val porcentaje: Int? = null,
        val error: String? = null,
        /** El APK listo para instalar. La pantalla lo consume una vez y avisa. */
        val apkListo: Uri? = null
    )

    private val _estado = MutableStateFlow(Estado())
    val ui: StateFlow<Estado> = _estado.asStateFlow()

    private var descarga: Job? = null

    init {
        viewModelScope.launch {
            estado.avisar.collect { avisar -> _estado.update { it.copy(avisar = avisar) } }
        }
        comprobar(forzar = false)
    }

    fun comprobar(forzar: Boolean) {
        viewModelScope.launch {
            _estado.update { it.copy(comprobando = true, error = null) }
            val resultado = comprobador.comprobar(forzar)
            _estado.update { anterior ->
                anterior.copy(
                    comprobando = false,
                    // Una respuesta «no tocaba preguntar» no borra lo que ya se sabía.
                    resultado = if (resultado is ResultadoActualizacion.SinPreguntar) {
                        anterior.resultado
                    } else {
                        resultado
                    }
                )
            }
        }
    }

    fun cambiarAvisos(activado: Boolean) {
        viewModelScope.launch { estado.cambiarAvisos(activado) }
    }

    /** Baja el APK de la versión encontrada. No instala: eso lo hace el sistema después. */
    fun descargar() {
        val disponible = _estado.value.resultado as? ResultadoActualizacion.Disponible ?: return
        if (descarga?.isActive == true) return

        descarga = viewModelScope.launch {
            descargas.descargar(disponible).collect { paso ->
                when (paso) {
                    is DescargaDeApk.Paso.Progreso ->
                        _estado.update { it.copy(porcentaje = paso.porcentaje, error = null) }

                    is DescargaDeApk.Paso.Lista ->
                        _estado.update { it.copy(porcentaje = null, apkListo = paso.uri) }

                    is DescargaDeApk.Paso.Fallo ->
                        _estado.update { it.copy(porcentaje = null, error = paso.motivo) }
                }
            }
        }
    }

    /** La pantalla ya ha lanzado el instalador: que no se vuelva a lanzar al rotar. */
    fun apkConsumido() {
        _estado.update { it.copy(apkListo = null) }
    }

    fun puedeInstalar(): Boolean = descargas.puedeInstalar()

    fun intentDeAutorizacion(): Intent = descargas.intentDeAutorizacion()

    fun intentDeInstalacion(uri: Uri): Intent = descargas.intentDeInstalacion(uri)
}
