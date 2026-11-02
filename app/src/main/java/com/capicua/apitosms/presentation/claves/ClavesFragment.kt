// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.claves

import android.app.KeyguardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.capicua.apitosms.R
import com.capicua.apitosms.data.repository.ClaveRepository
import com.capicua.apitosms.databinding.DialogClaveBinding
import com.capicua.apitosms.databinding.FragmentClavesBinding
import com.capicua.apitosms.databinding.ItemClaveBinding
import com.capicua.apitosms.domain.model.Ambito
import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.presentation.common.copiar
import com.capicua.apitosms.util.toDisplayString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Las claves de la API. Se llega desde el Panel, detrás de [PuertaDeCredencial].
 *
 * Mientras está abierta, la ventana lleva `FLAG_SECURE`: ni capturas de pantalla ni miniatura en
 * «recientes», porque aquí se enseña el secreto de una clave recién creada.
 */
@AndroidEntryPoint
class ClavesFragment : Fragment() {

    private var _binding: FragmentClavesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ClavesViewModel by viewModels()
    private val adapter = Adaptador { mostrarAcciones(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClavesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { salir() }
        binding.recyclerClaves.adapter = adapter
        binding.fabNueva.setOnClickListener { mostrarFormulario(null) }

        val bloqueo = requireContext().getSystemService<KeyguardManager>()
        binding.textSinBloqueo.visibility = if (bloqueo?.isDeviceSecure == true) View.GONE else View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.lista.collect { lista ->
                    adapter.submitList(lista)
                    binding.textVacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Tras una muerte de proceso, o si se dejó abierta mucho rato, se vuelve a pedir.
        if (!PuertaDeCredencial.vigente()) {
            binding.root.visibility = View.INVISIBLE
            PuertaDeCredencial.pedir(this, alFallar = { salir() }) {
                _binding?.root?.visibility = View.VISIBLE
            }
        } else {
            PuertaDeCredencial.renovar()
        }
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        PuertaDeCredencial.renovar()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun salir() {
        PuertaDeCredencial.olvidar()
        if (isAdded) findNavController().popBackStack()
    }

    // ── Acciones ──────────────────────────────────────────────────────────────

    private fun mostrarAcciones(clave: ClaveApi) {
        val acciones = buildList {
            add(getString(R.string.claves_accion_editar) to { mostrarFormulario(clave) })
            add(getString(R.string.claves_accion_regenerar) to { confirmarRegenerar(clave) })
            if (clave.activa) add(getString(R.string.claves_accion_revocar) to { confirmarRevocar(clave) })
            add(getString(R.string.claves_accion_borrar) to { confirmarBorrar(clave) })
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(clave.nombre)
            .setItems(acciones.map { it.first }.toTypedArray()) { _, i -> acciones[i].second() }
            .show()
    }

    private fun confirmarRegenerar(clave: ClaveApi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.claves_regenerar_titulo)
            .setMessage(getString(R.string.claves_regenerar_mensaje, clave.nombre, clave.prefijo))
            .setNegativeButton(R.string.accion_cancelar, null)
            .setPositiveButton(R.string.claves_accion_regenerar) { _, _ ->
                viewModel.regenerar(clave) { mostrarSecreto(it) }
            }
            .show()
    }

    private fun confirmarRevocar(clave: ClaveApi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.claves_revocar_titulo)
            .setMessage(getString(R.string.claves_revocar_mensaje, clave.nombre))
            .setNegativeButton(R.string.accion_cancelar, null)
            .setPositiveButton(R.string.claves_accion_revocar) { _, _ -> viewModel.revocar(clave) }
            .show()
    }

    private fun confirmarBorrar(clave: ClaveApi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.claves_borrar_titulo)
            .setMessage(getString(R.string.claves_borrar_mensaje, clave.nombre))
            .setNegativeButton(R.string.accion_cancelar, null)
            .setPositiveButton(R.string.claves_accion_borrar) { _, _ -> viewModel.borrar(clave) }
            .show()
    }

    /** Alta si [clave] es null; edición si no. */
    private fun mostrarFormulario(clave: ClaveApi?) {
        val form = DialogClaveBinding.inflate(layoutInflater)
        if (clave != null) {
            form.editNombre.setText(clave.nombre)
            form.checkEnviar.isChecked = clave.puede(Ambito.ENVIAR)
            form.checkConsultar.isChecked = clave.puede(Ambito.CONSULTAR)
            form.checkAdmin.isChecked = clave.puede(Ambito.ADMIN)
            form.editRedes.setText(clave.redesPermitidas.joinToString("\n"))
            form.editCuota.setText(clave.cuotaDiaria.toString())
            form.editRitmo.setText(clave.peticionesPorMinuto.toString())
        } else {
            form.checkEnviar.isChecked = true
            form.checkConsultar.isChecked = true
            form.editCuota.setText("0")
            form.editRitmo.setText(ClaveApi.PETICIONES_POR_MINUTO_POR_DEFECTO.toString())
        }

        val dialogo = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (clave == null) R.string.claves_nueva else R.string.claves_editar)
            .setView(form.root)
            .setNegativeButton(R.string.accion_cancelar, null)
            .setPositiveButton(if (clave == null) R.string.claves_crear else R.string.accion_guardar, null)
            .create()

        // El botón se engancha a mano para que un error no cierre el diálogo y se pierda lo escrito.
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val datos = ClavesViewModel.Formulario(
                    nombre = form.editNombre.text?.toString().orEmpty(),
                    ambitos = buildSet {
                        if (form.checkEnviar.isChecked) add(Ambito.ENVIAR)
                        if (form.checkConsultar.isChecked) add(Ambito.CONSULTAR)
                        if (form.checkAdmin.isChecked) add(Ambito.ADMIN)
                    },
                    redes = form.editRedes.text?.toString().orEmpty(),
                    cuotaDiaria = form.editCuota.text?.toString().orEmpty(),
                    peticionesPorMinuto = form.editRitmo.text?.toString().orEmpty()
                )
                val alFallar: (String) -> Unit = { motivo ->
                    form.textError.text = motivo
                    form.textError.visibility = View.VISIBLE
                }
                if (clave == null) {
                    viewModel.crear(datos, alCrear = { dialogo.dismiss(); mostrarSecreto(it) }, alFallar = alFallar)
                } else {
                    viewModel.guardar(clave, datos, alGuardar = { dialogo.dismiss() }, alFallar = alFallar)
                }
            }
        }
        dialogo.show()
    }

    /**
     * El secreto, una sola vez. El diálogo no se cierra tocando fuera: cerrarlo sin querer es
     * perder la clave y tener que regenerarla.
     */
    private fun mostrarSecreto(conSecreto: ClaveRepository.ConSecreto) {
        val dialogo = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.claves_secreto_titulo, conSecreto.clave.nombre))
            .setMessage(getString(R.string.claves_secreto_mensaje, conSecreto.secreto))
            .setCancelable(false)
            .setNeutralButton(R.string.accion_copiar, null)
            .setPositiveButton(R.string.claves_secreto_guardado, null)
            .create()
        dialogo.setOnShowListener {
            dialogo.findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
            dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                binding.root.copiar(getString(R.string.claves_secreto_etiqueta), conSecreto.secreto, sensible = true)
            }
        }
        dialogo.show()
    }

    // ── Lista ─────────────────────────────────────────────────────────────────

    private class Adaptador(
        private val alPulsar: (ClaveApi) -> Unit
    ) : ListAdapter<ClaveApi, Adaptador.Fila>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Fila(ItemClaveBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Fila, position: Int) = holder.bind(getItem(position))

        inner class Fila(private val b: ItemClaveBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(c: ClaveApi) {
                val ctx = b.root.context
                b.root.setOnClickListener { alPulsar(c) }
                b.textNombre.text = c.nombre
                b.textEstado.setText(if (c.activa) R.string.claves_activa else R.string.claves_revocada)
                b.textEstado.setTextColor(ContextCompat.getColor(
                    ctx, if (c.activa) R.color.status_delivered else R.color.status_failed
                ))
                b.textPrefijo.text = ctx.getString(
                    R.string.claves_fila_prefijo, c.prefijo, c.ambitos.sortedBy { it.ordinal }.joinToString { it.descripcion }
                )
                b.textDetalle.text = listOfNotNull(
                    c.ultimoUsoEn?.let { ctx.getString(R.string.claves_fila_uso, it.toDisplayString(), c.ultimaIp ?: "—") }
                        ?: ctx.getString(R.string.claves_fila_sin_uso),
                    c.redesPermitidas.takeIf { it.isNotEmpty() }?.joinToString(prefix = ctx.getString(R.string.claves_fila_redes)),
                    c.cuotaDiaria.takeIf { it > 0 }?.let { ctx.getString(R.string.claves_fila_cuota, it) }
                ).joinToString(" · ")
            }
        }

        private object Diff : DiffUtil.ItemCallback<ClaveApi>() {
            override fun areItemsTheSame(a: ClaveApi, b: ClaveApi) = a.id == b.id
            override fun areContentsTheSame(a: ClaveApi, b: ClaveApi) = a == b
        }
    }
}
