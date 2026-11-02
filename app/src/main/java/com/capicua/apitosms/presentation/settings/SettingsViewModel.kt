// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.settings

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.apitosms.data.backup.CopiaDeAjustes
import com.capicua.apitosms.data.config.AppConfig
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.data.repository.ClaveRepository
import com.capicua.apitosms.data.repository.LogRepository
import com.capicua.apitosms.data.servidor.CertificadoDelServidor
import com.capicua.apitosms.data.servidor.CopiaDelCertificado
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
    private val copiaDelCertificado: CopiaDelCertificado,
    private val redLocal: RedLocal,
    private val proveedorDeSims: ProveedorDeSims
) : AndroidViewModel(application) {

    /** Lo que la sección del certificado enseña, ya en texto. */
    data class DatosCertificado(val origen: String?, val resumen: String)

    /** Qué llevó una copia exportada con datos confidenciales. */
    enum class CertificadoExportado {
        /** La copia no lleva datos confidenciales. */
        NO_PEDIDO,
        INCLUIDO,
        /** El certificado es el generado en el teléfono, que no puede salir de él. */
        GENERADO,
        /** Importado antes de que se guardara su copia: hay que volver a importarlo. */
        SIN_COPIA
    }

    /**
     * Lo que pasó al importar una copia.
     *
     * @property repetidas claves cuyo hash ya estaba en este teléfono, y que no se duplican.
     * @property motivoCertificado por qué no entró el certificado que traía la copia.
     */
    data class ResultadoImportacion(
        val activas: Int,
        val revocadas: Int,
        val repetidas: Int,
        val conConfidenciales: Boolean,
        val traiaCertificado: Boolean,
        val certificadoImportado: Boolean,
        val motivoCertificado: String?
    )

    val config: StateFlow<AppConfig?> = configDataStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _certificado = MutableStateFlow<DatosCertificado?>(null)
    val datosCertificado: StateFlow<DatosCertificado?> = _certificado.asStateFlow()

    /** Un `.p12` leído y validado, a la espera de que el usuario confirme la importación. */
    private var pendiente: Pendiente? = null

    /** El `.p12` tal cual, y su contraseña, para guardar su copia cifrada si se importa. */
    private class Pendiente(
        val lectura: CertificadoDelServidor.Lectura.Valido,
        val p12: ByteArray,
        val contrasena: CharArray
    ) {
        fun olvidar() = contrasena.fill('\u0000')
    }

    val puedeEnviarSms: Boolean
        get() = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun sims(): List<SimDisponible> = proveedorDeSims.simsDisponibles()

    fun etiquetaSim(id: Int): String? = proveedorDeSims.etiquetaDe(id)

    // ── Servidor ──────────────────────────────────────────────────────────────

    /** El mismo interruptor que el del Panel: se aplica al momento, sin pasar por Guardar. */
    fun cambiarServidor(activo: Boolean) {
        viewModelScope.launch {
            configDataStore.cambiarServidor(activo)
            val app = getApplication<Application>()
            if (activo) ApiServerService.iniciar(app) else ApiServerService.detener(app)
        }
    }

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
            withContext(Dispatchers.IO) {
                certificado.generar()
                copiaDelCertificado.borrar()
            }
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
            descartarPendiente()
            var bytes: ByteArray? = null
            val lectura = withContext(Dispatchers.IO) {
                try {
                    bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val leidos = bytes
                        ?: return@withContext CertificadoDelServidor.Lectura.Invalido("No se pudo abrir el fichero.")
                    if (leidos.size > MAX_P12) CertificadoDelServidor.Lectura.Invalido("El fichero es demasiado grande para ser un .p12.")
                    else certificado.leer(leidos, contrasena)
                } catch (e: Exception) {
                    CertificadoDelServidor.Lectura.Invalido("No se pudo leer el fichero: ${e.message}")
                }
            }
            when (lectura) {
                is CertificadoDelServidor.Lectura.Invalido -> {
                    contrasena.fill('\u0000')
                    alLeer(false, lectura.motivo)
                }
                is CertificadoDelServidor.Lectura.Valido -> {
                    // La contraseña se queda hasta confirmar: hace falta para guardar la copia cifrada.
                    pendiente = Pendiente(lectura, bytes!!, contrasena)
                    alLeer(true, textoDeConfirmacion(lectura))
                }
            }
        }
    }

    private fun textoDeConfirmacion(lectura: CertificadoDelServidor.Lectura.Valido): String {
        val ip = redLocal.actual()?.ip
        return buildString {
            appendLine(lectura.sujeto)
            appendLine("Válido hasta ${DateFormat.getDateInstance().format(lectura.validoHasta)}")
            appendLine("Nombres: ${lectura.nombres.ifEmpty { listOf("ninguno") }.joinToString()}")
            appendLine()
            appendLine(lectura.huellas.certificado)
            if (ip != null && ip !in lectura.nombres) {
                appendLine()
                append("⚠ La IP actual del teléfono, $ip, no está entre los nombres del certificado. ")
                append("Un cliente que compruebe el nombre lo rechazará; Capicua.ApiToSms.Cliente fija la huella y no lo necesita.")
            }
        }.trim()
    }

    fun importarPendiente(alTerminar: (String?) -> Unit) {
        val p = pendiente ?: return alTerminar("No hay ningún certificado leído.")
        pendiente = null
        viewModelScope.launch {
            val error = try {
                instalarCertificado(p.lectura, p.p12, p.contrasena)
            } finally {
                p.olvidar()
            }
            if (error != null) {
                alTerminar(error)
                return@launch
            }
            anotar(LogTipo.SEGURIDAD, "Certificado importado: ${p.lectura.sujeto}")
            alTerminar(null)
        }
    }

    /**
     * Mete el certificado en el almacén, guarda la copia cifrada del `.p12` y reinicia el
     * servidor. Devuelve el motivo si Android no acepta la clave.
     */
    private suspend fun instalarCertificado(
        lectura: CertificadoDelServidor.Lectura.Valido,
        p12: ByteArray,
        contrasena: CharArray
    ): String? {
        val error = withContext(Dispatchers.IO) {
            runCatching {
                certificado.importar(lectura)
                // Sin su copia el certificado funciona igual; solo no podrá viajar en la copia.
                runCatching { copiaDelCertificado.guardar(p12, contrasena) }
                    .onFailure { copiaDelCertificado.borrar() }
            }.exceptionOrNull()
        }
        if (error != null) return "Android no aceptó la clave: ${error.message}"
        configDataStore.cambiarOrigenCertificado(CertificadoDelServidor.Origen.IMPORTADO.name)
        ApiServerService.reiniciar(getApplication())
        refrescarCertificado()
        return null
    }

    fun descartarPendiente() {
        pendiente?.olvidar()
        pendiente = null
    }

    override fun onCleared() {
        descartarPendiente()
    }

    // ── Copia de seguridad ────────────────────────────────────────────────────

    /** @param alTerminar recibe qué pasó con el certificado, o el motivo del fallo. */
    fun exportarCopia(uri: Uri, conConfidenciales: Boolean, alTerminar: (Result<CertificadoExportado>) -> Unit) {
        viewModelScope.launch {
            val config = configDataStore.config.first()
            val resultado = withContext(Dispatchers.IO) {
                runCatching {
                    var p12: CopiaDelCertificado.Contenido? = null
                    val estado = when {
                        !conConfidenciales -> CertificadoExportado.NO_PEDIDO
                        config.origenCertificado != CertificadoDelServidor.Origen.IMPORTADO.name -> CertificadoExportado.GENERADO
                        else -> {
                            p12 = copiaDelCertificado.leer()
                            if (p12 == null) CertificadoExportado.SIN_COPIA else CertificadoExportado.INCLUIDO
                        }
                    }
                    try {
                        val confidencial = if (conConfidenciales) {
                            CopiaDeAjustes.Confidencial(p12?.p12, p12?.contrasena)
                        } else null
                        val texto = CopiaDeAjustes.exportar(config, claves.todas(), confidencial = confidencial)
                        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(texto.toByteArray(Charsets.UTF_8))
                        } ?: error("No se pudo escribir el fichero")
                    } finally {
                        p12?.contrasena?.fill('\u0000')
                    }
                    estado
                }
            }
            resultado.onSuccess { estado ->
                anotar(
                    if (conConfidenciales) LogTipo.SEGURIDAD else LogTipo.SISTEMA,
                    when (estado) {
                        CertificadoExportado.NO_PEDIDO -> "Copia de ajustes exportada"
                        CertificadoExportado.INCLUIDO -> "Copia de ajustes exportada con datos confidenciales: hashes de las claves y certificado"
                        else -> "Copia de ajustes exportada con datos confidenciales: hashes de las claves, sin certificado"
                    }
                )
            }
            alTerminar(resultado)
        }
    }

    fun importarCopia(uri: Uri, alTerminar: (Result<ResultadoImportacion>) -> Unit) {
        viewModelScope.launch {
            val texto = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().let { b -> String(b, 0, minOf(b.size, MAX_COPIA), Charsets.UTF_8) }
                    }
                }.getOrNull()
            } ?: return@launch alTerminar(Result.failure(IllegalStateException("No se pudo leer el fichero.")))

            val lectura = when (val l = CopiaDeAjustes.leer(texto)) {
                is CopiaDeAjustes.Lectura.Invalida -> return@launch alTerminar(Result.failure(IllegalArgumentException(l.motivo)))
                is CopiaDeAjustes.Lectura.Valida -> l
            }

            configDataStore.guardar(lectura.config(configDataStore.config.first()))

            // Una clave cuyo hash ya está es la misma clave: importar dos veces la misma copia no
            // la duplica (y la base no lo admitiría: el hash es único).
            val (repetidasEnLaBase, nuevas) = lectura.claves.distinctBy { it.hash }.partition { claves.porHash(it.hash) != null }
            nuevas.forEach { claves.insertarImportada(it) }
            val repetidas = repetidasEnLaBase.size

            var importado = false
            var motivo: String? = null
            lectura.certificado?.let { c ->
                try {
                    when (val l = withContext(Dispatchers.IO) { certificado.leer(c.p12, c.contrasena) }) {
                        is CertificadoDelServidor.Lectura.Invalido -> motivo = l.motivo
                        is CertificadoDelServidor.Lectura.Valido -> {
                            motivo = instalarCertificado(l, c.p12, c.contrasena)
                            importado = motivo == null
                            if (importado) anotar(LogTipo.SEGURIDAD, "Certificado importado de la copia: ${l.sujeto}")
                        }
                    }
                } finally {
                    c.contrasena.fill('\u0000')
                }
            }

            val activas = nuevas.count { it.activa }
            anotar(
                LogTipo.SEGURIDAD,
                "Copia de ajustes importada: ${activas} clave(s) activa(s), ${nuevas.size - activas} revocada(s)" +
                    if (repetidas > 0) ", $repetidas ya estaban" else ""
            )
            alTerminar(Result.success(ResultadoImportacion(
                activas = activas,
                revocadas = nuevas.size - activas,
                repetidas = repetidas,
                conConfidenciales = lectura.conConfidenciales,
                traiaCertificado = lectura.certificado != null,
                certificadoImportado = importado,
                motivoCertificado = motivo
            )))
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
