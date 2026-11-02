// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.red

import com.capicua.apitosms.domain.seguridad.ListaDeRedes
import com.capicua.apitosms.domain.seguridad.Red
import java.net.InetAddress

/**
 * Por dónde puede llegar una computadora a la API.
 *
 * - [WIFI] y [ETHERNET] son la red local de siempre: el teléfono es **un equipo más** de una red
 *   que no es suya.
 * - [USB] es el **anclaje de red USB**: el teléfono crea una red de dos equipos sobre el cable, le
 *   da IP por DHCP a la computadora y escucha en la suya. Android no la trata como una red a la que
 *   el teléfono esté conectado, sino como una que comparte, y por eso `ConnectivityManager` no la
 *   ve: se busca por el nombre de la interfaz ([esInterfazDeAnclajeUsb]).
 * - [ADB] es `127.0.0.1` del propio teléfono. Solo llega quien esté **dentro** del teléfono: en la
 *   práctica, una computadora con `adb forward tcp:<puerto> tcp:<puerto>` por el cable.
 *
 * Los dos últimos son opcionales y vienen apagados (`AppConfig.escucharPorUsb` y
 * `escucharPorAdb`): son para un teléfono sin wifi, conectado por cable a una sola computadora.
 */
enum class TipoEnlace(val etiqueta: String) {
    WIFI("wifi"),
    ETHERNET("Ethernet"),
    USB("anclaje USB"),
    ADB("adb");

    /**
     * Si la conexión es punto a punto por el cable. En ellos la lista global de redes no aplica:
     * por el cable solo llega la computadora que está al otro lado, y encender la opción es la
     * autorización.
     */
    val porCable: Boolean get() = this == USB || this == ADB
}

/**
 * Una dirección en la que escuchar, con la subred que se acepta por ella.
 *
 * La subred es la de la propia interfaz: en la wifi, la de la oficina; en el anclaje USB, la que
 * Android inventa para el cable (una /24 privada); en adb, `127.0.0.1/32`.
 */
data class Enlace(val tipo: TipoEnlace, val direccion: InetAddress, val subred: Red?) {
    val ip: String get() = direccion.hostAddress ?: "?"

    companion object {
        /** El enlace de adb: siempre el mismo, y no depende de ninguna red. */
        val ADB: Enlace = Enlace(
            TipoEnlace.ADB,
            InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)),
            Red.leer("127.0.0.1/32")
        )
    }
}

/** Una interfaz del sistema, reducida a lo que hace falta para decidir. Ver [buscarAnclajeUsb]. */
data class InterfazDelSistema(
    val nombre: String,
    val activa: Boolean,
    /** Direcciones IPv4 con su longitud de prefijo. */
    val direcciones: List<Pair<InetAddress, Int>>
)

/**
 * Las decisiones de red del servidor, en Kotlin puro para probarlas en la JVM. Lo que toca Android
 * —preguntar qué redes hay, abrir sockets— está en `RedLocal` y `ApiServerService`.
 */
object Enlaces {

    /**
     * Nombres que Android da a la interfaz del anclaje USB según la versión y el fabricante:
     * `rndis0` (el clásico, y el de Windows), `ncm0` (Android 11 y posteriores en muchos
     * teléfonos), `usb0` y `ecm0` en algunos fabricantes.
     */
    private val NOMBRE_ANCLAJE_USB = Regex("""^(rndis|ncm|usb|ecm)\d+$""")

    fun esInterfazDeAnclajeUsb(nombre: String): Boolean = NOMBRE_ANCLAJE_USB.matches(nombre)

    /**
     * El enlace del anclaje USB, si está activo: la primera interfaz encendida con nombre de
     * anclaje y una IPv4. Sin cable, o con el anclaje apagado, la interfaz no existe o no tiene IP.
     */
    fun buscarAnclajeUsb(interfaces: List<InterfazDelSistema>): Enlace? =
        interfaces.asSequence()
            .filter { it.activa && esInterfazDeAnclajeUsb(it.nombre) }
            .sortedBy { it.nombre }
            .firstNotNullOfOrNull { interfaz ->
                interfaz.direcciones.firstOrNull { (dir, _) -> !dir.isLoopbackAddress && dir.address.size == 4 }
                    ?.let { (dir, prefijo) -> Enlace(TipoEnlace.USB, dir, Red.leer("${dir.hostAddress}/$prefijo")) }
            }

    /**
     * Los enlaces en los que hay que escuchar, sin repetir dirección. El orden es el de
     * preferencia: el primero es el que enseñan la notificación y el botón de copiar del Panel.
     */
    fun deseados(local: Enlace?, usb: Enlace?, escucharPorUsb: Boolean, escucharPorAdb: Boolean): List<Enlace> =
        listOfNotNull(
            local,
            usb.takeIf { escucharPorUsb },
            Enlace.ADB.takeIf { escucharPorAdb }
        ).distinctBy { it.direccion }

    /** Qué servidores parar y cuáles arrancar para pasar de [actuales] a [deseados]. */
    data class Plan(val parar: Set<Enlace>, val arrancar: List<Enlace>) {
        val vacio: Boolean get() = parar.isEmpty() && arrancar.isEmpty()
    }

    /**
     * Compara lo que hay con lo que debería haber. Un servidor que ya no escucha ([vivos] no lo
     * incluye: su socket murió con la red) se para y, si sigue deseado, se vuelve a arrancar.
     */
    fun planificar(actuales: Set<Enlace>, vivos: Set<Enlace>, deseados: List<Enlace>): Plan {
        val sobran = actuales.filter { it !in deseados || it !in vivos }.toSet()
        val quedan = actuales - sobran
        return Plan(parar = sobran, arrancar = deseados.filter { it !in quedan })
    }

    /**
     * Si se acepta una conexión de [ip] que entra por [enlace], **antes del saludo TLS**. La IP
     * bloqueada por fuerza bruta se comprueba aparte, antes de llamar aquí.
     *
     * - Por adb solo puede llegar algo de dentro del teléfono: se exige que sea `127.0.0.1`.
     * - Por el anclaje USB, la subred del cable, que solo alcanza a la computadora conectada.
     * - Por la wifi o el Ethernet, la lista global si la hay; si está vacía, la subred de la
     *   interfaz, como hasta ahora.
     */
    fun admitir(ip: InetAddress, enlace: Enlace, globales: ListaDeRedes): Boolean = when {
        enlace.tipo == TipoEnlace.ADB -> ip.isLoopbackAddress
        enlace.tipo.porCable -> enlace.subred?.contiene(ip) == true
        !globales.vacia -> globales.contiene(ip)
        else -> enlace.subred?.contiene(ip) == true
    }
}
