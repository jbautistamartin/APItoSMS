// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adaptador del `ViewPager2` de [HomeFragment]: una página por cada [Pestana].
 *
 * `FragmentStateAdapter` destruye las páginas que quedan lejos de la visible y las vuelve a
 * crear al acercarse. No se toca `offscreenPageLimit` a propósito: las cinco pantallas
 * observan `Flow` de Room y se repueblan solas al recrearse, así que mantenerlas todas vivas
 * solo gastaría memoria.
 */
class PestanasAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = Pestana.entries.size

    override fun createFragment(position: Int): Fragment = Pestana.enPosicion(position).crear()
}
