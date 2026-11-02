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
import android.telephony.TelephonyManager
import com.capicua.apitosms.domain.red.Enlace
import com.capicua.apitosms.domain.red.Enlaces
import com.capicua.apitosms.domain.red.InterfazDelSistema
import com.capicua.apitosms.domain.red.TipoEnlace
import com.capicua.apitosms.domain.seguridad.Red
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
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
 *
 * El **anclaje de red USB** va aparte ([anclajeUsb]): para Android no es una red a la que el
 * teléfono esté conectado sino una que comparte, y `ConnectivityManager` no la enseña.
 */
@Singleton
class RedLocal @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val conectividad = context.getSystemService(ConnectivityManager::class.java)

    /**
     * La interfaz local actual, o null si no hay wifi ni Ethernet.
     *
     * `allNetworks` está marcado como obsoleto desde la API 31 en favor de los callbacks, pero es
     * la única forma de preguntar «qué hay ahora» sin esperar a un aviso, y sigue funcionando.
     */
    @Suppress("DEPRECATION")
    fun actual(): Enlace? {
        val cm = conectividad ?: return null
        return cm.allNetworks.asSequence()
            .mapNotNull { red ->
                val tipo = tipoLocal(cm.getNetworkCapabilities(red)) ?: return@mapNotNull null
                cm.getLinkProperties(red)?.let { ipv4(it, tipo) }
            }
            .firstOrNull()
    }

    /**
     * El enlace del anclaje de red USB, o null si no está activo. Se busca por el nombre de la
     * interfaz (`rndis0`, `ncm0`…) entre todas las del sistema, que es la única forma de verlo
     * desde una aplicación. Android no avisa al encenderlo, así que quien lo use tiene que
     * volver a preguntar de vez en cuando.
     */
    fun anclajeUsb(): Enlace? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
                InterfazDelSistema(
                    nombre = ni.name,
                    activa = runCatching { ni.isUp }.getOrDefault(false),
                    direcciones = ni.interfaceAddresses.mapNotNull { a ->
                        a.address?.takeIf { it is Inet4Address }?.let { it to a.networkPrefixLength.toInt() }
                    }
                )
            }
        }.onFailure { Timber.w(it, "RedLocal: no se pudieron leer las interfaces") }.getOrDefault(emptyList())
        return Enlaces.buscarAnclajeUsb(interfaces)
    }

    /**
     * Avisa cuando la red local aparece, cambia o desaparece. Devuelve con qué darse de baja.
     *
     * Se escucha **cualquier** red y se vuelve a calcular [actual] en cada aviso, en lugar de
     * fiarse de los parámetros del aviso: así una wifi que cae mientras hay datos móviles se ve
     * como lo que es, «ya no hay red local».
     */
    fun observar(alCambiar: (Enlace?) -> Unit): () -> Unit {
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

    /**
     * Si los **datos móviles están activados**. Con el anclaje USB encendido, eso es lo que la
     * computadora conectada recibiría como internet en cuanto no haya wifi: el Panel lo avisa.
     *
     * Se pregunta por el ajuste y no por las redes que hay, porque con la wifi conectada Android no
     * enseña a las aplicaciones la red celular que mantiene en segundo plano (se vio en el
     * teléfono: datos encendidos y `allNetworks` sin ninguna celular). Pide `READ_PHONE_STATE`,
     * que la app ya tiene para elegir la SIM; sin él, se mira si hay una red celular visible.
     */
    @Suppress("DEPRECATION")
    fun hayDatosMoviles(): Boolean {
        val telefonia = context.getSystemService(TelephonyManager::class.java)
        runCatching { telefonia?.isDataEnabled }.getOrNull()?.let { return it }
        val cm = conectividad ?: return false
        return cm.allNetworks.any { red ->
            cm.getNetworkCapabilities(red)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
    }

    private fun tipoLocal(capacidades: NetworkCapabilities?): TipoEnlace? = when {
        capacidades == null -> null
        capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TipoEnlace.WIFI
        capacidades.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TipoEnlace.ETHERNET
        else -> null
    }

    private fun ipv4(lp: LinkProperties, tipo: TipoEnlace): Enlace? =
        lp.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.let { Enlace(tipo, it.address, Red.leer("${it.address.hostAddress}/${it.prefixLength}")) }
}
