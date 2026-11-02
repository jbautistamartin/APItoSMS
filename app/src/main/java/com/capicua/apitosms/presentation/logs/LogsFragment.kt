// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
import com.capicua.apitosms.databinding.FragmentLogsBinding
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LogsViewModel by viewModels()
    private lateinit var adapter: LogsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LogsAdapter(alMantenerPulsado = ::copiarAlPortapapeles)
        binding.recyclerViewLogs.adapter = adapter

        configurarChips()
        configurarExportar()
        configurarLimpiar()
        observarLogs()
    }

    /**
     * Copia una entrada del registro al portapapeles. La engancha la pulsación larga sobre la
     * fila, que es donde la busca quien quiere pegar un error en un correo o en un parte de
     * incidencia: lo que se copia es **la misma línea** que escribe el fichero exportado.
     *
     * El aviso solo se enseña hasta Android 12. Desde Android 13 el sistema saca el suyo al
     * escribir en el portapapeles —con una vista previa de lo copiado— y añadir un Snackbar
     * encima deja dos avisos de lo mismo tapándose el uno al otro.
     */
    private fun copiarAlPortapapeles(log: LogEntry) {
        val portapapeles = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        portapapeles.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.logs_copiado_etiqueta), viewModel.comoLinea(log))
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Snackbar.make(binding.root, R.string.logs_copiado, Snackbar.LENGTH_SHORT).show()
        }
    }

    /** Un chip por cada [LogTipo], detrás del «Todos» que ya trae el layout. */
    private fun configurarChips() {
        binding.chipTodos.isChecked = viewModel.filtroActivo.value == null
        binding.chipTodos.setOnClickListener { viewModel.aplicarFiltro(null) }

        LogTipo.entries.forEach { tipo ->
            val chip = Chip(requireContext()).apply {
                setChipDrawable(ChipDrawable.createFromAttributes(
                    requireContext(), null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter
                ))
                id = View.generateViewId()
                text = tipo.etiqueta
                isCheckable = true
                isChecked = viewModel.filtroActivo.value == tipo
                setOnClickListener { viewModel.aplicarFiltro(tipo) }
            }
            binding.chipGroupTipo.addView(chip)
        }
    }

    private fun configurarLimpiar() {
        binding.fabLimpiarLogs.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.logs_limpiar_titulo)
                .setMessage(R.string.logs_limpiar_mensaje)
                .setNegativeButton(R.string.accion_cancelar, null)
                .setPositiveButton(R.string.logs_limpiar) { _, _ -> viewModel.limpiarTodos() }
                .show()
        }
    }

    private fun configurarExportar() {
        binding.fabExportar.setOnClickListener {
            viewModel.exportarLogs { uri ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.logs_exportar)))
            }
        }
    }

    private fun observarLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logs.collect { logs ->
                        adapter.submitList(logs)
                        binding.textViewSinLogs.visibility =
                            if (logs.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.totalLogs.collect { total ->
                        binding.textViewTotal.text =
                            resources.getQuantityString(R.plurals.logs_total_entradas, total, total)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}