// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.apitosms.data.backup.CopiaDeAjustes
import com.capicua.apitosms.data.config.AppConfig
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.data.repository.ClaveRepository
import com.capicua.apitosms.data.repository.LogRepository
import com.capicua.apitosms.data.servidor.CertificadoDelServidor
import com.capicua.apitosms.data.servidor.RedLocal
import com.capicua.apitosms.data.sms.ProveedorDeSims
import com.capicua.apitosms.data.sms.SimDisponible
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.service.ApiServerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val configDataStore: ConfigDataStore,
    private val claves: ClaveRepository,
    private val registro: LogRepository,
    private val certificado: CertificadoDelServidor,
    private val redLocal: RedLocal,
    private val proveedorDeSims: ProveedorDeSims
) : AndroidViewModel(application) {

    /** Lo que la tarjeta del certificado enseña, ya en texto. */
    data class DatosCertificado(val origen: String?, val resumen: String)

    val config: StateFlow<AppConfig?> = configDataStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _certificado = MutableStateFlow<DatosCertificado?>(null)
    val datosCertificado: StateFlow<DatosCertificado?> = _certificado.asStateFlow()

    /** Un `.p12` leído y validado, a la espera de que el usuario confirme la importación. */
    private var pendiente: CertificadoDelServidor.Lectura.Valido? = null

    fun sims(): List<SimDisponible> = proveedorDeSims.simsDisponibles()

    fun etiquetaSim(id: Int): String? = proveedorDeSims.etiquetaDe(id)

    fun refrescarCertificado() {
        viewModelScope.launch {
            val origen = configDataStore.config.first().origenCertificado
            val info = withContext(Dispatchers.IO) { runCatching { certificado.info(null) }.getOrNull() }
            _certificado.value = if (info == null) null else DatosCertificado(
                origen,
                buildString {
                    appendLine(info.sujeto)
                    appendLine("Emisor: ${info.emisor}")
                    appendLine("${info.algoritmo} · válido hasta ${DateFormat.getDateInstance().format(info.validoHasta)}")
                    append(info.huellas.certificado)
                }
            )
        }
    }

    // ── Ajustes ───────────────────────────────────────────────────────────────

    fun guardar(campos: ValidadorDeAjustes.Campos, alTerminar: (List<String>) -> Unit) {
        viewModelScope.launch {
            val actual = configDataStore.config.first()
            when (val r = ValidadorDeAjustes.validar(campos, actual)) {
                is ValidadorDeAjustes.Resultado.Invalido -> alTerminar(r.errores)
                is ValidadorDeAjustes.Resultado.Valido -> {
                    configDataStore.guardar(r.config)
                    anotar(LogTipo.SEGURIDAD, "Ajustes modificados desde el teléfono")
                    alTerminar(emptyList())
                }
            }
        }
    }

    // ── Certificado ───────────────────────────────────────────────────────────

    fun regenerarCertificado(alTerminar: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { certificado.generar() }
            configDataStore.cambiarOrigenCertificado(CertificadoDelServidor.Origen.GENERADO.name)
            anotar(LogTipo.SEGURIDAD, "Certificado regenerado en el teléfono: los clientes tienen que fijar la huella nueva")
            ApiServerService.reiniciar(getApplication())
            refrescarCertificado()
            alTerminar()
        }
    }

    /**
     * Lee el `.p12` y lo deja pendiente. Devuelve el texto de confirmación, con un aviso si la IP
     * actual del teléfono no está entre los nombres del certificado —el cliente lo rechazará si
     * valida el nombre—, o el motivo por el que no vale.
     */
    fun leerP12(uri: Uri, contrasena: CharArray, alLeer: (ok: Boolean, texto: String) -> Unit) {
        viewModelScope.launch {
            val lectura = withContext(Dispatchers.IO) {
                try {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext CertificadoDelServidor.Lectura.Invalido("No se pudo abrir el fichero.")
                    if (bytes.size > MAX_P12) CertificadoDelServidor.Lectura.Invalido("El fichero es demasiado grande para ser un .p12.")
                    else certificado.leer(bytes, contrasena)
                } finally {
                    contrasena.fill('\u0000')
                }
            }
            when (lectura) {
                is CertificadoDelServidor.Lectura.Invalido -> {
                    pendiente = null
                    alLeer(false, lectura.motivo)
                }
                is CertificadoDelServidor.Lectura.Valido -> {
                    pendiente = lectura
                    val ip = redLocal.actual()?.ip
                    alLeer(true, buildString {
                        appendLine(lectura.sujeto)
                        appendLine("Válido hasta ${DateFormat.getDateInstance().format(lectura.validoHasta)}")
                        appendLine("Nombres: ${lectura.nombres.ifEmpty { listOf("ninguno") }.joinToString()}")
                        appendLine()
                        appendLine(lectura.huellas.certificado)
                        if (ip != null && ip !in lectura.nombres) {
                            appendLine()
                            append("⚠ La IP actual del teléfono, $ip, no está entre los nombres del certificado. ")
                            append("Un cliente que compruebe el nombre lo rechazará; ApiToSms.Cliente fija la huella y no lo necesita.")
                        }
                    }.trim())
                }
            }
        }
    }

    fun importarPendiente(alTerminar: (String?) -> Unit) {
        val lectura = pendiente ?: return alTerminar("No hay ningún certificado leído.")
        pendiente = null
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { certificado.importar(lectura) }.exceptionOrNull()
            }
            if (error != null) {
                alTerminar("Android no aceptó la clave: ${error.message}")
                return@launch
            }
            configDataStore.cambiarOrigenCertificado(CertificadoDelServidor.Origen.IMPORTADO.name)
            anotar(LogTipo.SEGURIDAD, "Certificado importado: ${lectura.sujeto}")
            ApiServerService.reiniciar(getApplication())
            refrescarCertificado()
            alTerminar(null)
        }
    }

    fun descartarPendiente() {
        pendiente = null
    }

    // ── Copia de seguridad ────────────────────────────────────────────────────

    fun exportarCopia(uri: Uri, alTerminar: (String?) -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val texto = CopiaDeAjustes.exportar(configDataStore.config.first(), claves.todas())
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(texto.toByteArray(Charsets.UTF_8))
                    } ?: error("No se pudo escribir el fichero")
                }.exceptionOrNull()
            }
            if (error == null) anotar(LogTipo.SISTEMA, "Copia de ajustes exportada")
            alTerminar(error?.message)
        }
    }

    /** @param alTerminar recibe el número de claves importadas, o el motivo del fallo. */
    fun importarCopia(uri: Uri, alTerminar: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val texto = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().let { b -> String(b, 0, minOf(b.size, MAX_COPIA), Charsets.UTF_8) }
                    }
                }.getOrNull()
            } ?: return@launch alTerminar(Result.failure(IllegalStateException("No se pudo leer el fichero.")))

            when (val lectura = CopiaDeAjustes.leer(texto)) {
                is CopiaDeAjustes.Lectura.Invalida -> alTerminar(Result.failure(IllegalArgumentException(lectura.motivo)))
                is CopiaDeAjustes.Lectura.Valida -> {
                    configDataStore.guardar(lectura.config(configDataStore.config.first()))
                    lectura.claves.forEach { claves.insertarImportada(it) }
                    anotar(LogTipo.SEGURIDAD, "Copia de ajustes importada, con ${lectura.claves.size} clave(s) revocada(s)")
                    alTerminar(Result.success(lectura.claves.size))
                }
            }
        }
    }

    private suspend fun anotar(tipo: LogTipo, detalle: String) {
        registro.registrar(LogEntry(tipo = tipo, detalle = detalle, timestamp = Instant.now()))
    }

    private companion object {
        const val MAX_P12 = 64 * 1024
        const val MAX_COPIA = 1024 * 1024
    }
}
