// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.capicua.apitosms.R
import com.capicua.apitosms.data.config.AppConfig
import com.capicua.apitosms.databinding.FragmentSettingsBinding
import com.capicua.apitosms.presentation.claves.PuertaDeCredencial
import com.capicua.apitosms.util.InicioAutomatico
import com.capicua.apitosms.util.LimiteDeSmsDeAndroid
import com.capicua.apitosms.util.OptimizacionBateria
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    /** La SIM elegida en el formulario; se guarda con el resto al pulsar Guardar. */
    private var simElegida = AppConfig.SIM_POR_DEFECTO

    private val elegirP12 = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pedirContrasena(uri)
    }

    private val crearCopia = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val conConfidenciales = binding.switchIncluirConfidenciales.isChecked
        viewModel.exportarCopia(uri, conConfidenciales) { resultado ->
            resultado.onSuccess { certificado ->
                // Que el fichero lleva datos confidenciales se dice también al terminar, no solo en
                // el aviso de antes: entre marcar la casilla y guardar se pasa por el selector del
                // sistema, que es donde se decide la carpeta.
                avisar(getString(when (certificado) {
                    SettingsViewModel.CertificadoExportado.NO_PEDIDO -> R.string.settings_copia_exportada
                    SettingsViewModel.CertificadoExportado.INCLUIDO -> R.string.settings_copia_exportada_confidenciales
                    SettingsViewModel.CertificadoExportado.GENERADO -> R.string.settings_copia_exportada_sin_certificado_generado
                    SettingsViewModel.CertificadoExportado.SIN_COPIA -> R.string.settings_copia_exportada_sin_certificado_copia
                }))
            }.onFailure { avisar(getString(R.string.settings_copia_error, it.message)) }
        }
    }

    private val abrirCopia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        viewModel.importarCopia(uri) { resultado ->
            resultado.onSuccess { r ->
                rellenar()
                // Un diálogo y no un Snackbar: dice lo siguiente que hay que hacer.
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_copia_importada_titulo)
                    .setMessage(textoDeImportacion(r))
                    .setPositiveButton(R.string.permiso_entendido, null)
                    .show()
            }.onFailure { avisar(getString(R.string.settings_copia_error, it.message)) }
        }
    }

    private val pedirEstadoTelefono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) elegirSim() else avisar(getString(R.string.settings_sim_sin_permiso))
    }

    private val pedirEnviarSms = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (_binding != null) pintarPermisoSms()
        if (!concedido) avisar(getString(R.string.settings_sms_permiso_denegado))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonGuardar.setOnClickListener { guardar() }
        // Al pulsar, no al cambiar: si escuchara los cambios, pintar el valor guardado lo
        // volvería a aplicar.
        binding.switchServidor.setOnClickListener {
            viewModel.cambiarServidor(binding.switchServidor.isChecked)
        }
        binding.buttonSmsPermiso.setOnClickListener { pedirEnviarSms.launch(Manifest.permission.SEND_SMS) }
        binding.buttonSim.setOnClickListener { elegirSim() }
        binding.buttonRegenerarCertificado.setOnClickListener { confirmarRegenerar() }
        binding.buttonImportarCertificado.setOnClickListener {
            elegirP12.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"))
        }
        binding.buttonClaves.setOnClickListener {
            PuertaDeCredencial.pedir(this) { _ ->
                if (_binding != null) findNavController().navigate(R.id.action_home_a_claves)
            }
        }
        binding.buttonBateria.setOnClickListener {
            if (!OptimizacionBateria.abrirAjuste(requireContext())) avisar(getString(R.string.settings_sin_pantalla))
        }
        binding.buttonAutoinicio.setOnClickListener {
            if (!InicioAutomatico.abrirAjuste(requireContext())) avisar(getString(R.string.settings_sin_pantalla))
        }
        binding.buttonExportarCopia.setOnClickListener {
            crearCopia.launch("apitosms-ajustes-${LocalDate.now()}.json")
        }
        binding.buttonImportarCopia.setOnClickListener { confirmarImportarCopia() }
        // El aviso solo con la casilla encendida: un texto en rojo permanente sobre algo que no
        // está pasando se deja de leer, y entonces tampoco se lee cuando sí pasa.
        binding.switchIncluirConfidenciales.setOnCheckedChangeListener { _, marcado ->
            binding.textConfidencialesAviso.isVisible = marcado
        }

        // Solo se rellena una vez por vista: si se rellenara en cada emisión, guardar desde otra
        // pantalla —la pausa del Panel, por ejemplo— borraría lo que se está escribiendo aquí.
        // Tras una rotación los campos los restaura Android; la SIM, que no es un campo, no.
        if (savedInstanceState == null) {
            rellenar()
        } else {
            simElegida = savedInstanceState.getInt(ESTADO_SIM, AppConfig.SIM_POR_DEFECTO)
            pintarSim()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.datosCertificado.collect { datos ->
                        binding.textCertificado.text = when {
                            datos == null -> getString(R.string.settings_certificado_ninguno)
                            datos.origen == "IMPORTADO" -> getString(R.string.settings_certificado_importado, datos.resumen)
                            else -> getString(R.string.settings_certificado_generado, datos.resumen)
                        }
                    }
                }
                // El interruptor del servidor sí sigue al valor guardado: también se cambia desde
                // el Panel, y no es un campo del formulario.
                launch {
                    viewModel.config.filterNotNull().collect { binding.switchServidor.isChecked = it.servidorActivo }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refrescarCertificado()
        val ctx = requireContext()
        // La exención y el permiso cambian fuera de la aplicación: se repintan al volver.
        pintarBateria()
        pintarPermisoSms()
        val maximo = LimiteDeSmsDeAndroid.maximo(ctx)
        binding.textLimite.text = getString(
            R.string.settings_limite_estado,
            maximo?.toString() ?: getString(R.string.settings_limite_desconocido),
            LimiteDeSmsDeAndroid.COMANDO_MAXIMO,
            LimiteDeSmsDeAndroid.COMANDO_INTERVALO
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ESTADO_SIM, simElegida)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val ESTADO_SIM = "sim_elegida"
    }

    // ── Formulario ────────────────────────────────────────────────────────────

    private fun rellenar() {
        viewLifecycleOwner.lifecycleScope.launch {
            val c = viewModel.config.filterNotNull().first()
            val b = _binding ?: return@launch
            b.editPuerto.setText(c.puerto.toString())
            b.editRedes.setText(c.redesPermitidas)
            b.editPrefijoPais.setText(c.prefijoPais)
            b.editPrefijosPermitidos.setText(c.prefijosPermitidos)
            b.editPrefijosBloqueados.setText(c.prefijosBloqueados)
            b.editPausa.setText(c.pausaEntreMensajesSegundos.toString())
            b.editIntentos.setText(c.maxIntentos.toString())
            b.editIntervalo.setText(c.intervaloReintentoSegundos.toString())
            b.editTimeout.setText(c.timeoutEnvioSegundos.toString())
            b.editCuota.setText(c.cuotaDiariaGlobal.toString())
            b.editPartes.setText(c.maxPartesPorMensaje.toString())
            b.editLote.setText(c.maxMensajesPorLote.toString())
            b.editRetTextos.setText(c.retencionTextosDias.toString())
            b.editRetMensajes.setText(c.retencionMensajesDias.toString())
            b.editRetRegistro.setText(c.retencionRegistroDias.toString())
            b.switchInformes.isChecked = c.informesDeEntrega
            simElegida = c.subscriptionId
            pintarSim()
        }
    }

    private fun guardar() {
        val b = binding
        fun TextInputEditText.valor() = text?.toString().orEmpty()
        val campos = ValidadorDeAjustes.Campos(
            puerto = b.editPuerto.valor(),
            prefijoPais = b.editPrefijoPais.valor(),
            pausaEntreMensajes = b.editPausa.valor(),
            maxIntentos = b.editIntentos.valor(),
            intervaloReintento = b.editIntervalo.valor(),
            timeoutEnvio = b.editTimeout.valor(),
            cuotaDiaria = b.editCuota.valor(),
            maxPartes = b.editPartes.valor(),
            maxLote = b.editLote.valor(),
            prefijosPermitidos = b.editPrefijosPermitidos.valor(),
            prefijosBloqueados = b.editPrefijosBloqueados.valor(),
            redesPermitidas = b.editRedes.valor(),
            informesDeEntrega = b.switchInformes.isChecked,
            retencionTextos = b.editRetTextos.valor(),
            retencionMensajes = b.editRetMensajes.valor(),
            retencionRegistro = b.editRetRegistro.valor(),
            subscriptionId = simElegida
        )
        viewModel.guardar(campos) { errores ->
            val vista = _binding ?: return@guardar
            vista.textErrores.text = errores.joinToString("\n") { "• $it" }
            vista.textErrores.visibility = if (errores.isEmpty()) View.GONE else View.VISIBLE
            if (errores.isEmpty()) avisar(getString(R.string.settings_guardado))
        }
    }

    // ── Batería y permiso ─────────────────────────────────────────────────────

    /**
     * Con la exención concedida el botón se queda, como acción secundaria: algunos fabricantes
     * la revocan por su cuenta tras una actualización.
     */
    private fun pintarBateria() {
        val exenta = OptimizacionBateria.estaExenta(requireContext())
        binding.textBateriaEstado.setText(if (exenta) R.string.settings_bateria_ok else R.string.settings_bateria_pendiente)
        binding.textBateriaEstado.setTextColor(
            resources.getColor(if (exenta) R.color.status_delivered else R.color.status_pending, null)
        )
        binding.textBateriaDetalle.setText(
            if (exenta) R.string.settings_bateria_ok_detalle else R.string.settings_bateria_pendiente_detalle
        )
        binding.buttonBateria.setText(if (exenta) R.string.settings_bateria_revisar else R.string.settings_bateria_activar)
    }

    private fun pintarPermisoSms() {
        val puede = viewModel.puedeEnviarSms
        binding.textSmsPermiso.setText(if (puede) R.string.settings_sms_permiso_ok else R.string.settings_sms_permiso_falta)
        binding.textSmsPermiso.setTextColor(
            resources.getColor(if (puede) R.color.status_delivered else R.color.status_failed, null)
        )
        binding.buttonSmsPermiso.isVisible = !puede
    }

    // ── SIM ───────────────────────────────────────────────────────────────────

    private fun pintarSim() {
        binding.textSim.text = if (simElegida == AppConfig.SIM_POR_DEFECTO) {
            getString(R.string.settings_sim_por_defecto)
        } else {
            viewModel.etiquetaSim(simElegida) ?: getString(R.string.settings_sim_desconocida, simElegida)
        }
    }

    private fun elegirSim() {
        val permiso = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE)
        if (permiso != PackageManager.PERMISSION_GRANTED) {
            pedirEstadoTelefono.launch(Manifest.permission.READ_PHONE_STATE)
            return
        }
        val sims = viewModel.sims()
        val opciones = listOf(getString(R.string.settings_sim_por_defecto) to AppConfig.SIM_POR_DEFECTO) +
            sims.map { it.etiqueta to it.subscriptionId }
        val marcada = opciones.indexOfFirst { it.second == simElegida }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_sim_elegir)
            .setSingleChoiceItems(opciones.map { it.first }.toTypedArray(), marcada) { d, i ->
                simElegida = opciones[i].second
                pintarSim()
                d.dismiss()
            }
            .show()
    }

    // ── Certificado ───────────────────────────────────────────────────────────

    private fun confirmarRegenerar() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_certificado_regenerar)
            .setMessage(R.string.settings_certificado_regenerar_aviso)
            .setNegativeButton(R.string.accion_cancelar, null)
            .setPositiveButton(R.string.settings_certificado_regenerar) { _, _ ->
                viewModel.regenerarCertificado { avisar(getString(R.string.settings_certificado_regenerado)) }
            }
            .show()
    }

    private fun pedirContrasena(uri: android.net.Uri) {
        val campo = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val caja = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.settings_certificado_contrasena)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(campo)
        }
        val marco = FrameLayout(requireContext()).apply {
            val m = (24 * resources.displayMetrics.density).toInt()
            setPadding(m, m / 3, m, 0)
            addView(caja)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_certificado_importar)
            .setView(marco)
            .setNegativeButton(R.string.accion_cancelar, null)
            .setPositiveButton(R.string.settings_certificado_leer) { _, _ ->
                val editable = campo.text
                val contrasena = CharArray(editable?.length ?: 0) { editable!![it] }
                editable?.clear()
                viewModel.leerP12(uri, contrasena) { ok, texto -> confirmarImportar(ok, texto) }
            }
            .show()
    }

    private fun confirmarImportar(ok: Boolean, texto: String) {
        if (!ok) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_certificado_no_vale)
                .setMessage(texto)
                .setPositiveButton(R.string.permiso_entendido, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_certificado_confirmar)
            .setMessage(getString(R.string.settings_certificado_confirmar_mensaje, texto))
            .setNegativeButton(R.string.accion_cancelar) { _, _ -> viewModel.descartarPendiente() }
            .setOnCancelListener { viewModel.descartarPendiente() }
            .setPositiveButton(R.string.settings_certificado_importar) { _, _ ->
                viewModel.importarPendiente { error ->
                    avisar(error ?: getString(R.string.settings_certificado_importado_ok))
                }
            }
            .show()
    }

    // ── Copia ─────────────────────────────────────────────────────────────────

    private fun confirmarImportarCopia() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_copia_importar)
            .setMessage(R.string.settings_copia_importar_aviso)
            .setNegativeButton(R.string.accion_cancelar, null)
            .setPositiveButton(R.string.settings_copia_elegir) { _, _ ->
                abrirCopia.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .show()
    }

    private fun textoDeImportacion(r: SettingsViewModel.ResultadoImportacion): String {
        val nuevas = r.activas + r.revocadas
        val parrafos = buildList {
            add(buildString {
                append(resources.getQuantityString(R.plurals.settings_copia_importada, nuevas, nuevas))
                if (r.revocadas > 0) {
                    append(' ')
                    append(resources.getQuantityString(R.plurals.settings_copia_importada_revocadas, r.revocadas, r.revocadas))
                }
            })
            if (r.activas > 0) add(getString(R.string.settings_copia_importada_activas))
            if (r.repetidas > 0) add(getString(R.string.settings_copia_importada_repetidas, r.repetidas))
            when {
                r.certificadoImportado -> add(getString(R.string.settings_copia_importada_certificado))
                r.traiaCertificado -> add(getString(R.string.settings_copia_importada_certificado_no, r.motivoCertificado))
                r.conConfidenciales -> add(getString(R.string.settings_copia_importada_sin_certificado))
            }
        }
        return parrafos.joinToString("\n\n")
    }

    private fun avisar(texto: String) {
        _binding?.let { Snackbar.make(it.root, texto, Snackbar.LENGTH_LONG).show() }
    }
}
