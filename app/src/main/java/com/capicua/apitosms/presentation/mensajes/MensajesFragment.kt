// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.mensajes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capicua.apitosms.R
import com.capicua.apitosms.databinding.FragmentMensajesBinding
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.MensajeSaliente
import com.capicua.apitosms.util.toDisplayString
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MensajesFragment : Fragment() {

    private var _binding: FragmentMensajesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MensajesViewModel by viewModels()
    private lateinit var adapter: MensajesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMensajesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MensajesAdapter(::mostrarDetalle)
        binding.recyclerMensajes.adapter = adapter
        configurarChips()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.lista.collect { lista ->
                    adapter.submitList(lista)
                    binding.textViewTotal.text =
                        resources.getQuantityString(R.plurals.mensajes_total, lista.size, lista.size)
                    binding.textVacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun configurarChips() {
        binding.chipTodos.isChecked = viewModel.filtro.value == null
        binding.chipTodos.setOnClickListener { viewModel.filtrar(null) }
        EstadoMensaje.entries.forEach { estado ->
            val chip = Chip(requireContext()).apply {
                setChipDrawable(ChipDrawable.createFromAttributes(
                    requireContext(), null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter
                ))
                id = View.generateViewId()
                text = estado.etiqueta
                isCheckable = true
                isChecked = viewModel.filtro.value == estado
                setOnClickListener { viewModel.filtrar(estado) }
            }
            binding.chipGroupEstado.addView(chip)
        }
    }

    private fun mostrarDetalle(m: MensajeSaliente) {
        val lineas = buildList {
            add(getString(R.string.mensajes_detalle_estado, m.estado.etiqueta))
            add(getString(R.string.mensajes_detalle_destino, m.destino))
            add(getString(R.string.mensajes_detalle_id, m.id))
            m.claveNombre?.let { add(getString(R.string.mensajes_detalle_clave, it)) }
            m.referencia?.let { add(getString(R.string.mensajes_detalle_referencia, it)) }
            add(getString(R.string.mensajes_detalle_partes, m.partes, m.partesEntregadas))
            add(getString(R.string.mensajes_detalle_intentos, m.intentos))
            add(getString(R.string.mensajes_detalle_admitido, m.admitidoEn.toDisplayString()))
            m.enviadoEn?.let { add(getString(R.string.mensajes_detalle_enviado, it.toDisplayString())) }
            m.entregadoEn?.let { add(getString(R.string.mensajes_detalle_entregado, it.toDisplayString())) }
            m.caducaEn?.let { add(getString(R.string.mensajes_detalle_caduca, it.toDisplayString())) }
            m.ultimoError?.let { add(getString(R.string.mensajes_detalle_error, it)) }
            add("")
            add(m.texto ?: getString(R.string.mensajes_texto_purgado))
        }

        val dialogo = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mensajes_detalle_titulo)
            .setMessage(lineas.joinToString("\n"))
            .setPositiveButton(R.string.accion_cerrar, null)

        if (m.estado == EstadoMensaje.EN_COLA) {
            dialogo.setNegativeButton(R.string.mensajes_cancelar) { _, _ -> confirmarCancelar(m) }
        }
        dialogo.show()
    }

    private fun confirmarCancelar(m: MensajeSaliente) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mensajes_cancelar_titulo)
            .setMessage(getString(R.string.mensajes_cancelar_mensaje, m.destino))
            .setNegativeButton(R.string.accion_no, null)
            .setPositiveButton(R.string.mensajes_cancelar) { _, _ ->
                viewModel.cancelar(m) { cancelado ->
                    val vista = _binding?.root ?: return@cancelar
                    Snackbar.make(
                        vista,
                        if (cancelado) R.string.mensajes_cancelado else R.string.mensajes_no_cancelable,
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
