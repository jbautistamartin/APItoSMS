// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.servidor

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.capicua.apitosms.domain.seguridad.Red
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La red local del teléfono: la wifi (o un adaptador Ethernet, que algunos teléfonos admiten por
 * USB) y nunca los datos móviles.
 *
 * El servidor se **ata a la IP de esta interfaz**, no a `0.0.0.0`. Con `0.0.0.0` escucharía
 * también en los datos móviles, y con IPv6 un operador puede dar al teléfono una dirección pública:
 * la API quedaría en internet sin que nadie lo hubiera decidido.
 *
 * Solo IPv4: una red de oficina se direcciona por IPv4, y la IP que se configura en los clientes
 * —y la que se reserva en el DHCP del router— es esa.
 */
@Singleton
class RedLocal @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Una interfaz local con su dirección y su subred. */
    data class Interfaz(val direccion: InetAddress, val longitudPrefijo: Int) {
        val subred: Red? get() = Red.leer("${direccion.hostAddress}/$longitudPrefijo")
        val ip: String get() = direccion.hostAddress ?: "?"
    }

    private val conectividad = context.getSystemService(ConnectivityManager::class.java)

    /**
     * La interfaz local actual, o null si no hay wifi ni Ethernet.
     *
     * `allNetworks` está marcado como obsoleto desde la API 31 en favor de los callbacks, pero es
     * la única forma de preguntar «qué hay ahora» sin esperar a un aviso, y sigue funcionando.
     */
    @Suppress("DEPRECATION")
    fun actual(): Interfaz? {
        val cm = conectividad ?: return null
        return cm.allNetworks.asSequence()
            .filter { esLocal(cm.getNetworkCapabilities(it)) }
            .mapNotNull { cm.getLinkProperties(it)?.let(::ipv4) }
            .firstOrNull()
    }

    /**
     * Avisa cuando la red local aparece, cambia o desaparece. Devuelve con qué darse de baja.
     *
     * Se escucha **cualquier** red y se vuelve a calcular [actual] en cada aviso, en lugar de
     * fiarse de los parámetros del aviso: así una wifi que cae mientras hay datos móviles se ve
     * como lo que es, «ya no hay red local».
     */
    fun observar(alCambiar: (Interfaz?) -> Unit): () -> Unit {
        val cm = conectividad ?: return {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = alCambiar(actual())
            override fun onLost(network: Network) = alCambiar(actual())
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = alCambiar(actual())
        }
        val peticion = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        cm.registerNetworkCallback(peticion, callback)
        return { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    private fun esLocal(capacidades: NetworkCapabilities?): Boolean =
        capacidades != null && (
            capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capacidades.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )

    private fun ipv4(lp: LinkProperties): Interfaz? =
        lp.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.let { Interfaz(it.address, it.prefixLength) }
}
