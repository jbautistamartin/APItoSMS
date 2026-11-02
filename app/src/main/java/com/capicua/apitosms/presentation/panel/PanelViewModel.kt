// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.panel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.apitosms.data.config.AppConfig
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.data.repository.ClaveRepository
import com.capicua.apitosms.data.repository.LogRepository
import com.capicua.apitosms.data.repository.MensajeRepository
import com.capicua.apitosms.data.servidor.CertificadoDelServidor
import com.capicua.apitosms.data.servidor.RedLocal
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.domain.seguridad.BloqueoPorFallos
import com.capicua.apitosms.service.ApiServerService
import com.capicua.apitosms.service.EstadoServidor
import com.capicua.apitosms.util.LimiteDeSmsDeAndroid
import com.capicua.apitosms.util.OptimizacionBateria
import com.capicua.apitosms.worker.ColaDeEnvios
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * El Panel: lo que hay que ver de un vistazo para saber si la pasarela funciona.
 *
 * Los avisos que no llegan por un `Flow` —permisos, batería, IPs bloqueadas, el tope de SMS de
 * Android— se recalculan en [refrescar], que el fragment llama al volver a primer plano: es
 * cuando pueden haber cambiado, porque el usuario viene de los ajustes del sistema.
 */
@HiltViewModel
class PanelViewModel @Inject constructor(
    application: Application,
    private val configDataStore: ConfigDataStore,
    mensajes: MensajeRepository,
    claves: ClaveRepository,
    private val registro: LogRepository,
    private val certificado: CertificadoDelServidor,
    private val cola: ColaDeEnvios,
    private val bloqueo: BloqueoPorFallos,
    private val redLocal: RedLocal
) : AndroidViewModel(application) {

    data class Huellas(val certificado: String, val clavePublica: String, val origen: String?)

    /** Avisos que se calculan a mano. Ver [refrescar]. */
    data class Comprobaciones(
        val sinPermisoSms: Boolean = false,
        val sinExencionBateria: Boolean = false,
        val limiteDeAndroid: Boolean = false,
        val ipsBloqueadas: Map<String, Instant> = emptyMap(),
        /** Con el anclaje USB, la computadora conectada saldría a internet por estos datos. */
        val datosMoviles: Boolean = false
    )

    val estado: StateFlow<EstadoServidor.Estado> = EstadoServidor.estado

    val config: StateFlow<AppConfig?> = configDataStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val clavesActivas: Flow<Int> = claves.observarActivas()

    val bloqueoDeCola: StateFlow<String?> = cola.bloqueo

    private val _huellas = MutableStateFlow<Huellas?>(null)
    val huellas: StateFlow<Huellas?> = _huellas.asStateFlow()

    private val _comprobaciones = MutableStateFlow(Comprobaciones())
    val comprobaciones: StateFlow<Comprobaciones> = _comprobaciones.asStateFlow()

    /**
     * Lo de hoy, desde la medianoche local, y lo que hay en cola ahora mismo sea del día que sea.
     * La medianoche se fija al crear el ViewModel: el Panel que se queda abierto de un día para
     * otro sigue contando desde la de ayer hasta que se recrea, y eso no engaña a nadie.
     */
    val resumen: Flow<Pair<Map<EstadoMensaje, Int>, Int>> = combine(
        mensajes.observarResumenDesde(inicioDeHoy()),
        mensajes.observarEnEstado(EstadoMensaje.EN_COLA)
    ) { hoy, enCola -> hoy to enCola }

    fun refrescar() {
        val app = getApplication<Application>()
        _comprobaciones.value = Comprobaciones(
            sinPermisoSms = ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) !=
                PackageManager.PERMISSION_GRANTED,
            sinExencionBateria = !OptimizacionBateria.estaExenta(app),
            limiteDeAndroid = !LimiteDeSmsDeAndroid.estaAmpliado(app),
            ipsBloqueadas = bloqueo.bloqueadasAhora(),
            datosMoviles = redLocal.hayDatosMoviles()
        )
        viewModelScope.launch {
            val origen = configDataStore.config.first().origenCertificado
            _huellas.value = withContext(Dispatchers.IO) {
                runCatching { certificado.info(null) }.getOrNull()
            }?.let { Huellas(it.huellas.certificado, it.huellas.clavePublica, origen) }
        }
    }

    fun cambiarServidor(activo: Boolean) {
        viewModelScope.launch {
            configDataStore.cambiarServidor(activo)
            val app = getApplication<Application>()
            if (activo) ApiServerService.iniciar(app) else ApiServerService.detener(app)
            // La primera vez el servicio genera el certificado: las huellas llegan un poco después.
            if (activo) {
                delay(1_500)
                refrescar()
            }
        }
    }

    fun cambiarPausa(pausada: Boolean) {
        viewModelScope.launch {
            cola.cambiarPausa(pausada)
            registro.registrar(LogEntry(
                tipo = LogTipo.SEGURIDAD,
                detalle = if (pausada) "Cola pausada desde el teléfono" else "Cola reanudada desde el teléfono",
                timestamp = Instant.now()
            ))
            if (!pausada) cola.despertar()
        }
    }

    fun desbloquearIps() {
        bloqueo.desbloquearTodas()
        viewModelScope.launch {
            registro.registrar(LogEntry(
                tipo = LogTipo.SEGURIDAD,
                detalle = "IPs bloqueadas por fuerza bruta desbloqueadas a mano",
                timestamp = Instant.now()
            ))
        }
        refrescar()
    }

    private fun inicioDeHoy(): Instant =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
}
