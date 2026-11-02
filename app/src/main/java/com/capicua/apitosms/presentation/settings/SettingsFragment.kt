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
        viewModel.exportarCopia(uri) { error ->
            avisar(if (error == null) getString(R.string.settings_copia_exportada) else getString(R.string.settings_copia_error, error))
        }
    }

    private val abrirCopia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        viewModel.importarCopia(uri) { resultado ->
            resultado.onSuccess { n ->
                rellenar()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_copia_importada_titulo)
                    .setMessage(resources.getQuantityString(R.plurals.settings_copia_importada, n, n))
                    .setPositiveButton(R.string.permiso_entendido, null)
                    .show()
            }.onFailure { avisar(getString(R.string.settings_copia_error, it.message)) }
        }
    }

    private val pedirEstadoTelefono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) elegirSim() else avisar(getString(R.string.settings_sim_sin_permiso))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonGuardar.setOnClickListener { guardar() }
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
                viewModel.datosCertificado.collect { datos ->
                    binding.textCertificado.text = when {
                        datos == null -> getString(R.string.settings_certificado_ninguno)
                        datos.origen == "IMPORTADO" -> getString(R.string.settings_certificado_importado, datos.resumen)
                        else -> getString(R.string.settings_certificado_generado, datos.resumen)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refrescarCertificado()
        val ctx = requireContext()
        binding.textBateria.setText(
            if (OptimizacionBateria.estaExenta(ctx)) R.string.settings_bateria_ok else R.string.settings_bateria_falta
        )
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

    private fun avisar(texto: String) {
        _binding?.let { Snackbar.make(it.root, texto, Snackbar.LENGTH_LONG).show() }
    }
}
