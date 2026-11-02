// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.config

import com.capicua.apitosms.domain.api.AjustesDeApi
import com.capicua.apitosms.domain.cola.AjustesDeCola
import com.capicua.apitosms.domain.telefono.PoliticaDeDestinos

/**
 * Ajustes de la aplicación, editables en Ajustes y persistidos en DataStore.
 *
 * Las claves de la API **no** están aquí: viven en Room, con su hash. Esto son los parámetros que
 * afectan a todas las peticiones y a todos los envíos.
 *
 * La copia de seguridad (`CopiaDeAjustes`) se lleva todo salvo lo que identifica a *este*
 * teléfono: la SIM, el estado del servidor y de la cola, y el origen del certificado.
 */
data class AppConfig(
    /** El interruptor del Panel. El servidor arranca solo tras reiniciar si estaba encendido. */
    val servidorActivo: Boolean = false,

    /**
     * Puerto TCP de la API. 8443 es el puerto «HTTPS alternativo» de siempre, y está por encima
     * de 1024, que es lo único que una aplicación sin root puede abrir.
     */
    val puerto: Int = 8443,

    /**
     * La SIM por la que salen los SMS (decisión H: ajuste global). [SIM_POR_DEFECTO] deja que el
     * sistema use la SIM de SMS predeterminada. No viaja en la copia: identifica una tarjeta de
     * este teléfono.
     */
    val subscriptionId: Int = SIM_POR_DEFECTO,

    /** La cola no saca mensajes; la API sigue admitiendo. La pausa de emergencia del Panel. */
    val colaPausada: Boolean = false,

    /**
     * Segundos de espera entre un SMS y el siguiente.
     *
     * Es la protección contra el filtro antispam del operador, que corta las líneas que mandan
     * ráfagas. Tres segundos son 1.200 SMS por hora, que es más de lo que una SIM normal debería
     * mandar; subirlo es prudente si la línea es nueva.
     */
    val pausaEntreMensajesSegundos: Int = 3,

    /** Intentos de un mensaje, contando el primero, antes de darlo por fallido. */
    val maxIntentos: Int = 5,

    /** Espera tras el primer fallo, en segundos. Crece lineal con cada intento, con tope de 30 min. */
    val intervaloReintentoSegundos: Int = 30,

    /** Segundos que se espera la confirmación del operador antes de dar el intento por perdido. */
    val timeoutEnvioSegundos: Int = 60,

    /**
     * SMS al día (contando partes) entre todas las claves. 0 quita el límite.
     *
     * Por defecto 500, para que una clave robada o un cliente con un error no vacíen el saldo de la
     * línea en una tarde. Las claves pueden tener además su cuota propia, más baja.
     */
    val cuotaDiariaGlobal: Int = 500,

    /**
     * SMS en los que puede trocearse un mensaje. Un texto de 160 caracteres GSM-7 es uno; con
     * acentos o emojis la codificación pasa a UCS-2 y caben 70. Tres por defecto: un aviso que
     * necesita más es un correo.
     */
    val maxPartesPorMensaje: Int = 3,

    /** Mensajes en un `POST /lotes`. */
    val maxMensajesPorLote: Int = 100,

    /** Prefijo que se antepone a un destino sin `+`. Vacío: el destino tiene que venir en E.164. */
    val prefijoPais: String = "",

    /** Prefijos admitidos, separados por comas. Vacío: cualquiera. Ver `PoliticaDeDestinos`. */
    val prefijosPermitidos: String = "",

    /** Prefijos rechazados siempre, aunque estén permitidos. */
    val prefijosBloqueados: String = "",

    /**
     * IP o CIDR desde las que se aceptan conexiones, separadas por comas. Vacío: **solo la subred
     * wifi actual**, que es lo razonable en una oficina y no obliga a configurar nada.
     */
    val redesPermitidas: String = "",

    /**
     * Escuchar también en el **anclaje de red USB**, para una computadora conectada por cable sin
     * wifi. Por el cable entra solo la computadora conectada, así que ahí no aplica
     * [redesPermitidas]: se acepta la subred del cable. Ver `domain/red/Enlaces`.
     */
    val escucharPorUsb: Boolean = false,

    /**
     * Escuchar también en `127.0.0.1`, para `adb forward`. Pide tener la depuración USB activada
     * siempre, y todas las peticiones llegan como `127.0.0.1`: es para pruebas, o para una
     * computadora de total confianza.
     */
    val escucharPorAdb: Boolean = false,

    /** Pedir informe de entrega al operador. Sin él, un mensaje se queda en `ENVIADO`. */
    val informesDeEntrega: Boolean = true,

    /** Días tras los que se borra el **texto** de un mensaje terminado. Queda el metadato. */
    val retencionTextosDias: Int = 30,

    /** Días tras los que se borra un mensaje terminado entero. */
    val retencionMensajesDias: Int = 90,

    /** Días que se guarda el registro de auditoría. */
    val retencionRegistroDias: Int = 90,

    /** De dónde salió el certificado actual: `GENERADO` o `IMPORTADO`. Lo escribe la app. */
    val origenCertificado: String? = null
) {
    val politicaDeDestinos: PoliticaDeDestinos
        get() = PoliticaDeDestinos(
            PoliticaDeDestinos.leerLista(prefijosPermitidos),
            PoliticaDeDestinos.leerLista(prefijosBloqueados)
        )

    fun ajustesDeCola() = AjustesDeCola(
        pausada = colaPausada,
        maxIntentos = maxIntentos,
        intervaloReintentoSegundos = intervaloReintentoSegundos,
        timeoutEnvioSegundos = timeoutEnvioSegundos,
        pausaEntreMensajesSegundos = pausaEntreMensajesSegundos
    )

    fun ajustesDeApi(version: String) = AjustesDeApi(
        prefijoPais = prefijoPais,
        politica = politicaDeDestinos,
        maxPartesPorMensaje = maxPartesPorMensaje,
        maxMensajesPorLote = maxMensajesPorLote,
        cuotaDiariaGlobal = cuotaDiariaGlobal,
        pausaEntreMensajesSegundos = pausaEntreMensajesSegundos,
        version = version
    )

    companion object {
        /** Valor de [subscriptionId] que deja elegir la SIM al sistema. */
        const val SIM_POR_DEFECTO = -1
    }
}
