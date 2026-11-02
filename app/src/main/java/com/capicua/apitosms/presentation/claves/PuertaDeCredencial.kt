// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.claves

import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.capicua.apitosms.R

/**
 * Pide la credencial del teléfono —huella, cara, PIN, patrón o contraseña— antes de entrar en
 * la gestión de claves.
 *
 * Es lo único que separa «alguien con el teléfono en la mano» de «alguien que puede crear una
 * clave de administrador y mandar SMS desde la red». El teléfono pasarela suele estar en un
 * cajón o en un rack, al alcance de cualquiera de la oficina.
 *
 * **Si el teléfono no tiene bloqueo de pantalla, no hay credencial que pedir** y se deja pasar:
 * negarse dejaría la aplicación inservible y no protegería nada, porque sin bloqueo cualquiera
 * puede ponerlo. En ese caso [alPasar] recibe `protegido = false` y la pantalla lo avisa.
 *
 * La autorización dura [VIGENCIA_MS]: la pantalla de claves la vuelve a pedir si se queda en
 * segundo plano más tiempo, y también tras una muerte de proceso, en la que Android restaura la
 * pila de navegación —con la pantalla de claves encima— sin pasar por aquí. Vive en memoria a
 * propósito: un proceso nuevo empieza sin autorizar.
 */
object PuertaDeCredencial {

    private const val VIGENCIA_MS = 5 * 60 * 1000L

    @Volatile
    private var autorizadaEn = 0L

    /** true si hubo una autorización hace menos de [VIGENCIA_MS]. */
    fun vigente(): Boolean = SystemClock.elapsedRealtime() - autorizadaEn < VIGENCIA_MS && autorizadaEn != 0L

    /** Da la autorización por usada: al salir de la pantalla de claves. */
    fun olvidar() {
        autorizadaEn = 0L
    }

    /** Alarga la vigencia mientras se usa la pantalla. */
    fun renovar() {
        if (autorizadaEn != 0L) autorizadaEn = SystemClock.elapsedRealtime()
    }

    fun pedir(fragment: Fragment, alFallar: () -> Unit = {}, alPasar: (protegido: Boolean) -> Unit) {
        val contexto = fragment.requireContext()
        val autenticadores = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            // Antes de Android 11 BiometricPrompt no admite BIOMETRIC_STRONG con credencial.
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }

        if (BiometricManager.from(contexto).canAuthenticate(autenticadores) != BiometricManager.BIOMETRIC_SUCCESS) {
            autorizadaEn = SystemClock.elapsedRealtime()
            alPasar(false)
            return
        }

        val prompt = BiometricPrompt(
            fragment,
            ContextCompat.getMainExecutor(contexto),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    autorizadaEn = SystemClock.elapsedRealtime()
                    alPasar(true)
                }

                // Cancelar, demasiados intentos o no hay hardware: no se entra.
                override fun onAuthenticationError(codigo: Int, mensaje: CharSequence) {
                    alFallar()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(contexto.getString(R.string.claves_credencial_titulo))
            .setSubtitle(contexto.getString(R.string.claves_credencial_subtitulo))
            .setAllowedAuthenticators(autenticadores)
            .build()
        prompt.authenticate(info)
    }
}
