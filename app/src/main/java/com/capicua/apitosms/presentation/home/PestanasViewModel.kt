// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pestaña visible, compartida entre `MainActivity` —dueña de la barra inferior— y
 * [HomeFragment] —dueño del `ViewPager2`—.
 *
 * Existe porque los dos extremos tienen que mantenerse sincronizados pero no viven lo mismo:
 * la barra dura lo que dura la Activity, mientras que el pager se destruye en cuanto se navega
 * a la pantalla de claves. Sin un punto común, uno de los dos tendría que guardar
 * una referencia a una vista que ya no existe.
 *
 * Al ser un `StateFlow`, volver a seleccionar la pestaña que ya estaba no emite nada, y eso es
 * lo que impide que la sincronización en los dos sentidos (barra → pager, deslizar → barra) se
 * realimente. Y al vivir en un ViewModel, la pestaña sobrevive a la rotación sin guardar estado.
 */
class PestanasViewModel : ViewModel() {

    private val _pestanaActual = MutableStateFlow(Pestana.PANEL)
    val pestanaActual: StateFlow<Pestana> = _pestanaActual.asStateFlow()

    fun seleccionar(pestana: Pestana) {
        _pestanaActual.value = pestana
    }
}
