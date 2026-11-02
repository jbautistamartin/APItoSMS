// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.capicua.apitosms.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Único destino de primer nivel del grafo de navegación: aloja el `ViewPager2` con las cinco
 * pestañas de [Pestana], que se recorren deslizando a izquierda y derecha.
 *
 * No toca la barra inferior, que pertenece a `MainActivity`. La coordinación entre ambas va
 * por [PestanasViewModel], y es de ida y vuelta: pulsar en la barra mueve el pager, y deslizar
 * el pager mueve la selección de la barra.
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PestanasViewModel by activityViewModels()

    /** Se guarda para poder darlo de baja: el pager sobrevive al callback, no al contrario. */
    private var cambioDePagina: ViewPager2.OnPageChangeCallback? = null

    /** Se activa y se desactiva según la pestaña; ver [configurarAtras]. */
    private var volverAlPanel: OnBackPressedCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        volverAlPanel = configurarAtras()
    }

    /**
     * El pager se monta aquí y no en [onViewCreated], y el motivo es el orden.
     *
     * `ViewPager2` guarda y restaura por su cuenta la página en la que estaba. Esa restauración
     * llega con la jerarquía de vistas, **después** de `onViewCreated`, así que colocar el pager
     * allí no sirve de nada: lo restaurado se aplicaría después y ganaría. Se veía al pulsar una
     * pestaña desde la pantalla de claves —la barra marcaba la pestaña pulsada y el pager mostraba
     * otra—, porque es el único caso en que lo guardado contradice a lo que el usuario acaba de
     * pedir.
     *
     * `onViewStateRestored` es el primer punto posterior a esa restauración, y sigue siendo
     * anterior al primer layout, así que no hay fotograma en blanco.
     */
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        configurarPager()
        observarPestana()
    }

    override fun onDestroyView() {
        cambioDePagina?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        cambioDePagina = null
        volverAlPanel = null
        _binding = null
        super.onDestroyView()
    }

    // ── Pager ─────────────────────────────────────────────────────────────────

    private fun configurarPager() {
        // Asignar el adaptador es lo que consume la página guardada: `setAdapter` pone el pager
        // en la primera página y llama acto seguido a `restorePendingState`, que es quien
        // aplica lo restaurado —y que no hace nada mientras no haya adaptador—. Al ejecutarse
        // ya después de la restauración, aquí se salda esa cuenta y la posición que se fija a
        // continuación, la del ViewModel, es la definitiva.
        binding.viewPager.adapter = PestanasAdapter(this)
        binding.viewPager.setCurrentItem(viewModel.pestanaActual.value.ordinal, false)

        // El callback se registra después de colocar el pager, no antes, para que ni la
        // restauración ni esa colocación inicial entren en el ViewModel como si el usuario
        // hubiera deslizado.
        cambioDePagina = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.seleccionar(Pestana.enPosicion(position))
            }
        }.also { binding.viewPager.registerOnPageChangeCallback(it) }
    }

    // ── Atrás ─────────────────────────────────────────────────────────────────

    /**
     * Hace que «atrás» vuelva a la primera pestaña en lugar de cerrar la aplicación.
     *
     * Solo se activa fuera del panel: estando ya en él, el callback se desactiva y el gesto
     * recupera su comportamiento normal, que es salir. Antes esto lo daba la pila del
     * `NavController`, que iba deshaciendo las pestañas visitadas; con el pager no hay pila que
     * deshacer, así que la vuelta se define aquí.
     */
    private fun configurarAtras(): OnBackPressedCallback {
        val callback = object : OnBackPressedCallback(enabled = false) {
            override fun handleOnBackPressed() {
                binding.viewPager.currentItem = Pestana.PANEL.ordinal
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        return callback
    }

    // ── Observación ───────────────────────────────────────────────────────────

    private fun observarPestana() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pestanaActual.collect { pestana ->
                    volverAlPanel?.isEnabled = pestana != Pestana.PANEL

                    // Si el pager ya está en esa página, la emisión viene de él mismo: la
                    // provocó `onPageSelected` al deslizar. Devolvérsela no es inofensivo.
                    // `onPageSelected` se dispara con el gesto todavía asentándose, así que un
                    // `setCurrentItem` animado en ese momento se suma al desplazamiento en
                    // curso y el pager acaba saltando a la página siguiente: un deslizamiento,
                    // dos pestañas. Solo se mueve el pager cuando el cambio viene de fuera,
                    // que es cuando la página de destino todavía no es la actual.
                    if (binding.viewPager.currentItem == pestana.ordinal) return@collect

                    // Con animación: el cambio viene de la barra inferior, y el desplazamiento
                    // es lo que enseña que las pestañas están puestas en una fila.
                    binding.viewPager.setCurrentItem(pestana.ordinal, true)
                }
            }
        }
    }
}
