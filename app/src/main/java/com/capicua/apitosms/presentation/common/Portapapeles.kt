// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.View
import com.capicua.apitosms.R
import com.google.android.material.snackbar.Snackbar

/**
 * Copiar al portapapeles, con el mismo criterio en toda la aplicación.
 *
 * @param sensible marca el contenido como sensible (Android 13+), para que la vista previa del
 *   sistema no enseñe el secreto de una clave a quien mire la pantalla por encima del hombro.
 */
fun View.copiar(etiqueta: String, texto: String, sensible: Boolean = false) {
    val portapapeles = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(etiqueta, texto)
    if (sensible) {
        clip.description.extras = PersistableBundle().apply {
            val clave = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }
            putBoolean(clave, true)
        }
    }
    portapapeles.setPrimaryClip(clip)
    // Desde Android 13 el sistema enseña su propio aviso al copiar.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Snackbar.make(this, context.getString(R.string.copiado, etiqueta), Snackbar.LENGTH_SHORT).show()
    }
}
