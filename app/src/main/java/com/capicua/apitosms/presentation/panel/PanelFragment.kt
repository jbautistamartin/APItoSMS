// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.panel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.capicua.apitosms.R
import com.capicua.apitosms.databinding.FragmentPanelBinding
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.presentation.claves.PuertaDeCredencial
import com.capicua.apitosms.presentation.common.copiar
import com.capicua.apitosms.service.EstadoServidor
import com.capicua.apitosms.util.LimiteDeSmsDeAndroid
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PanelFragment : Fragment() {

    private var _binding: FragmentPanelBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PanelViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchServidor.setOnClickListener {
            viewModel.cambiarServidor(binding.switchServidor.isChecked)
        }
        binding.buttonCopiarUrl.setOnClickListener {
            (viewModel.estado.value as? EstadoServidor.Estado.Escuchando)?.let {
                binding.root.copiar(getString(R.string.panel_url_etiqueta), it.url)
            }
        }
        binding.buttonCopiarHuella.setOnClickListener {
            viewModel.huellas.value?.let { binding.root.copiar(getString(R.string.panel_huella_certificado), it.certificado) }
        }
        binding.buttonCopiarHuellaClave.setOnClickListener {
            viewModel.huellas.value?.let { binding.root.copiar(getString(R.string.panel_huella_clave), "sha256//${it.clavePublica}") }
        }
        binding.buttonPausa.setOnClickListener {
            viewModel.config.value?.let { viewModel.cambiarPausa(!it.colaPausada) }
        }
        binding.buttonDesbloquearIps.setOnClickListener { viewModel.desbloquearIps() }
        binding.buttonClaves.setOnClickListener { abrirClaves() }

        observar()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refrescar()
    }

    private fun abrirClaves() {
        PuertaDeCredencial.pedir(this) { _ ->
            // La propia pantalla de claves avisa si el teléfono no tiene bloqueo.
            if (_binding != null) findNavController().navigate(R.id.action_home_a_claves)
        }
    }

    private fun observar() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.estado, viewModel.huellas) { e, h -> e to h }.collect { (estado, huellas) ->
                        pintarServidor(estado)
                        pintarHuellas(huellas)
                    }
                }
                launch {
                    viewModel.config.collect { config ->
                        if (config == null) return@collect
                        binding.switchServidor.isChecked = config.servidorActivo
                        binding.buttonPausa.setText(if (config.colaPausada) R.string.panel_reanudar else R.string.panel_pausar)
                    }
                }
                launch {
                    combine(viewModel.resumen, viewModel.config) { r, c -> r to c }.collect { (resumen, config) ->
                        val (hoy, enCola) = resumen
                        val pausada = config?.colaPausada == true
                        binding.textColaEstado.text = when {
                            pausada -> getString(R.string.panel_cola_pausada, enCola)
                            enCola == 0 -> getString(R.string.panel_cola_vacia)
                            else -> getString(R.string.panel_cola_en_marcha, enCola)
                        }
                        binding.textColaHoy.text = getString(
                            R.string.panel_cola_hoy,
                            hoy.values.sum(),
                            (hoy[EstadoMensaje.ENVIADO] ?: 0) + (hoy[EstadoMensaje.ENTREGADO] ?: 0) + (hoy[EstadoMensaje.NO_ENTREGADO] ?: 0),
                            hoy[EstadoMensaje.ENTREGADO] ?: 0,
                            (hoy[EstadoMensaje.FALLIDO] ?: 0) + (hoy[EstadoMensaje.NO_ENTREGADO] ?: 0),
                            (hoy[EstadoMensaje.CANCELADO] ?: 0) + (hoy[EstadoMensaje.CADUCADO] ?: 0)
                        )
                    }
                }
                launch {
                    combine(
                        viewModel.comprobaciones, viewModel.clavesActivas, viewModel.bloqueoDeCola
                    ) { c, claves, bloqueo -> Triple(c, claves, bloqueo) }.collect { (c, claves, bloqueo) ->
                        binding.textClaves.text = if (claves == 0) getString(R.string.panel_sin_claves)
                        else resources.getQuantityString(R.plurals.panel_claves_activas, claves, claves)
                        pintarAvisos(c, claves, bloqueo)
                    }
                }
            }
        }
    }

    private fun pintarServidor(estado: EstadoServidor.Estado) {
        val (texto, url) = when (estado) {
            EstadoServidor.Estado.Detenido -> getString(R.string.panel_estado_detenido) to null
            EstadoServidor.Estado.EsperandoRed -> getString(R.string.panel_estado_sin_red) to null
            is EstadoServidor.Estado.Error -> getString(R.string.panel_estado_error, estado.motivo) to null
            is EstadoServidor.Estado.Escuchando -> getString(R.string.panel_estado_escuchando) to estado.url
        }
        binding.textEstadoServidor.text = texto
        binding.textUrl.text = url.orEmpty()
        binding.textUrl.visibility = if (url == null) View.GONE else View.VISIBLE
        binding.buttonCopiarUrl.visibility = binding.textUrl.visibility
    }

    private fun pintarHuellas(huellas: PanelViewModel.Huellas?) {
        if (huellas == null) {
            binding.textOrigenCertificado.setText(R.string.panel_certificado_ninguno)
            binding.textHuellaCertificado.text = "—"
            binding.textHuellaClave.text = "—"
            return
        }
        binding.textOrigenCertificado.setText(
            if (huellas.origen == "IMPORTADO") R.string.panel_certificado_importado else R.string.panel_certificado_generado
        )
        binding.textHuellaCertificado.text = huellas.certificado
        binding.textHuellaClave.text = "sha256//${huellas.clavePublica}"
    }

    private fun pintarAvisos(c: PanelViewModel.Comprobaciones, claves: Int, bloqueo: String?) {
        val avisos = buildList {
            if (c.sinPermisoSms) add(getString(R.string.panel_aviso_permiso_sms))
            if (bloqueo != null) add(getString(R.string.panel_aviso_bloqueo, bloqueo))
            if (claves == 0) add(getString(R.string.panel_aviso_sin_claves))
            if (c.sinExencionBateria) add(getString(R.string.panel_aviso_bateria))
            if (c.limiteDeAndroid) add(getString(
                R.string.panel_aviso_limite_android,
                LimiteDeSmsDeAndroid.COMANDO_MAXIMO, LimiteDeSmsDeAndroid.COMANDO_INTERVALO
            ))
            if (c.ipsBloqueadas.isNotEmpty()) add(getString(
                R.string.panel_aviso_ips_bloqueadas, c.ipsBloqueadas.keys.joinToString()
            ))
        }
        binding.cardAvisos.visibility = if (avisos.isEmpty()) View.GONE else View.VISIBLE
        binding.textAvisos.text = avisos.joinToString("\n\n") { "• $it" }
        binding.buttonDesbloquearIps.visibility = if (c.ipsBloqueadas.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
