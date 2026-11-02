// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.home

import androidx.fragment.app.Fragment
import com.capicua.apitosms.R
import com.capicua.apitosms.presentation.about.AboutFragment
import com.capicua.apitosms.presentation.logs.LogsFragment
import com.capicua.apitosms.presentation.mensajes.MensajesFragment
import com.capicua.apitosms.presentation.panel.PanelFragment
import com.capicua.apitosms.presentation.settings.SettingsFragment

/**
 * Las cinco pestañas de primer nivel, en el orden en que se deslizan.
 *
 * Es la única fuente de verdad de la correspondencia entre tres cosas que antes vivían
 * separadas y tenían que coincidir a mano: la posición dentro del `ViewPager2`, el item del
 * menú de la barra inferior y el Fragment que se muestra. El orden de declaración **es** el
 * orden de las páginas, así que reordenar las pestañas es reordenar este enum; el menú
 * `bottom_nav_menu.xml` debe listar los items en el mismo orden para que la barra se lea igual
 * que se desliza.
 *
 * El grafo de navegación solo conoce [HomeFragment] y la subpantalla de claves, que no cabe
 * en la barra —Material admite cinco items— y además va detrás de la credencial del teléfono.
 */
enum class Pestana(val idMenu: Int, val crear: () -> Fragment) {
    PANEL(R.id.tab_panel, { PanelFragment() }),
    MENSAJES(R.id.tab_mensajes, { MensajesFragment() }),
    LOGS(R.id.tab_logs, { LogsFragment() }),
    AJUSTES(R.id.tab_ajustes, { SettingsFragment() }),
    ACERCA_DE(R.id.tab_acerca_de, { AboutFragment() });

    companion object {
        /** Pestaña asociada a un item del menú inferior, o null si el id no es de una pestaña. */
        fun porIdMenu(idMenu: Int): Pestana? = entries.firstOrNull { it.idMenu == idMenu }

        /** Pestaña en una posición del pager, acotada por si la posición llega fuera de rango. */
        fun enPosicion(posicion: Int): Pestana = entries[posicion.coerceIn(entries.indices)]
    }
}
