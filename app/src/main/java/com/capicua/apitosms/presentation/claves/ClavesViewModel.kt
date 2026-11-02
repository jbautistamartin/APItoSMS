// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.claves

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.apitosms.data.repository.ClaveRepository
import com.capicua.apitosms.domain.model.Ambito
import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.domain.seguridad.ListaDeRedes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Alta, edición, rotación, revocación y borrado de claves.
 *
 * El secreto solo existe en [ClaveRepository.ConSecreto], que vuelve de crear y de regenerar, y
 * se entrega al fragment para enseñarlo una vez. No se guarda en el ViewModel: si la pantalla se
 * recrea mientras está el diálogo abierto, el secreto se pierde y hay que regenerar, que es el
 * comportamiento correcto para algo que no debe quedarse en memoria más de lo necesario.
 */
@HiltViewModel
class ClavesViewModel @Inject constructor(
    private val claves: ClaveRepository
) : ViewModel() {

    /** Lo que escribe el formulario, sin validar. */
    data class Formulario(
        val nombre: String,
        val ambitos: Set<Ambito>,
        val redes: String,
        val cuotaDiaria: String,
        val peticionesPorMinuto: String
    )

    sealed interface Validacion {
        data class Valida(
            val nombre: String,
            val ambitos: Set<Ambito>,
            val redes: List<String>,
            val cuotaDiaria: Int,
            val peticionesPorMinuto: Int
        ) : Validacion

        data class Invalida(val motivo: String) : Validacion
    }

    val lista: Flow<List<ClaveApi>> = claves.observarTodas()

    fun crear(f: Formulario, alCrear: (ClaveRepository.ConSecreto) -> Unit, alFallar: (String) -> Unit) {
        when (val v = validar(f)) {
            is Validacion.Invalida -> alFallar(v.motivo)
            is Validacion.Valida -> viewModelScope.launch {
                alCrear(claves.crear(v.nombre, v.ambitos, v.redes, v.cuotaDiaria, v.peticionesPorMinuto))
            }
        }
    }

    fun guardar(original: ClaveApi, f: Formulario, alGuardar: () -> Unit, alFallar: (String) -> Unit) {
        when (val v = validar(f)) {
            is Validacion.Invalida -> alFallar(v.motivo)
            is Validacion.Valida -> viewModelScope.launch {
                claves.guardar(original.copy(
                    nombre = v.nombre,
                    ambitos = v.ambitos,
                    redesPermitidas = v.redes,
                    cuotaDiaria = v.cuotaDiaria,
                    peticionesPorMinuto = v.peticionesPorMinuto
                ))
                alGuardar()
            }
        }
    }

    fun regenerar(clave: ClaveApi, alRegenerar: (ClaveRepository.ConSecreto) -> Unit) {
        viewModelScope.launch { claves.regenerar(clave.id)?.let(alRegenerar) }
    }

    fun revocar(clave: ClaveApi) {
        viewModelScope.launch { claves.revocar(clave.id) }
    }

    fun borrar(clave: ClaveApi) {
        viewModelScope.launch { claves.borrar(clave.id) }
    }

    companion object {
        const val MAX_NOMBRE = 60

        fun validar(f: Formulario): Validacion {
            val nombre = f.nombre.trim()
            if (nombre.isEmpty()) return Validacion.Invalida("Ponle un nombre: el del sistema que la va a usar.")
            if (nombre.length > MAX_NOMBRE) return Validacion.Invalida("El nombre no puede pasar de $MAX_NOMBRE caracteres.")
            if (f.ambitos.isEmpty()) return Validacion.Invalida("Marca al menos un permiso.")

            val (redes, invalidas) = ListaDeRedes.leer(ListaDeRedes.separar(f.redes))
            if (invalidas.isNotEmpty()) {
                return Validacion.Invalida("No es una IP ni una red CIDR: ${invalidas.joinToString()}")
            }

            val cuota = f.cuotaDiaria.trim().ifEmpty { "0" }.toIntOrNull()
                ?.takeIf { it >= 0 } ?: return Validacion.Invalida("La cuota diaria tiene que ser un número, 0 para no tener cuota propia.")
            val ritmo = f.peticionesPorMinuto.trim().ifEmpty { "${ClaveApi.PETICIONES_POR_MINUTO_POR_DEFECTO}" }.toIntOrNull()
                ?.takeIf { it in 1..6000 } ?: return Validacion.Invalida("Las peticiones por minuto van de 1 a 6000.")

            return Validacion.Valida(nombre, f.ambitos, redes.redes.map { it.texto }, cuota, ritmo)
        }
    }
}
