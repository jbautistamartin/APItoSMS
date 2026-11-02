// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.servidor

import com.capicua.apitosms.domain.api.ManejadorHttp
import com.capicua.apitosms.domain.api.RespuestaHttp
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Servidor HTTPS mínimo sobre `SSLServerSocket`, para la API.
 *
 * ## Por qué no Ktor
 * Fue lo primero que se miró (fase 4 del masterplan). El motor ligero de Ktor, CIO, no trae TLS
 * en la 3.6 —solo conectores en claro y sockets Unix—, y los motores que sí lo traen (Netty,
 * Jetty) añaden varios megas y un arranque lento a un teléfono que solo tiene que contestar JSON.
 * Para una API de nueve rutas, un servidor propio de pocos cientos de líneas sobre el TLS de la
 * plataforma (Conscrypt en Android, JSSE en la JVM de los tests) es menos código que configurar.
 *
 * ## Qué hace al aceptar una conexión
 * 1. Pregunta a [filtro] por la IP remota **antes del saludo TLS**. Una IP bloqueada o fuera de la
 *    red permitida se cierra sin gastar CPU en criptografía.
 * 2. Saludo TLS 1.2 o 1.3. Nada anterior.
 * 3. Bucle *keep-alive*: hasta [MAX_PETICIONES_POR_CONEXION] peticiones, con [TIEMPO_INACTIVIDAD_MS]
 *    de espera entre una y otra. Un cliente C# que manda un lote de cien y consulta su estado
 *    reutiliza la conexión, y el saludo TLS, que es lo caro, se paga una vez.
 *
 * Las conexiones se atienden en un grupo de [MAX_CONEXIONES] hilos. Pasado ese número, las nuevas
 * esperan en una cola corta y, si la cola se llena, se cierran: el teléfono no es un servidor web
 * y no debe intentar parecerlo.
 *
 * @param sslContext null solo en los tests que prueban el protocolo sin TLS.
 */
class ServidorHttps(
    private val direccion: InetAddress,
    private val puerto: Int,
    private val sslContext: SSLContext?,
    private val manejador: ManejadorHttp,
    private val filtro: (InetAddress) -> Boolean = { true },
    private val alFallar: (Throwable) -> Unit = {}
) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var hilos: ExecutorService? = null
    private var aceptador: Thread? = null

    /** El puerto real: útil cuando se pide el 0 en los tests. */
    val puertoEscuchando: Int get() = socket?.localPort ?: -1

    val escuchando: Boolean get() = socket?.isClosed == false

    @Synchronized
    fun iniciar() {
        check(socket == null) { "el servidor ya está en marcha" }
        val servidor = if (sslContext != null) {
            (sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                enabledProtocols = supportedProtocols.filter { it in PROTOCOLOS }.toTypedArray()
                needClientAuth = false
            }
        } else {
            ServerSocket()
        }
        servidor.reuseAddress = true
        servidor.bind(InetSocketAddress(direccion, puerto), COLA_ACEPTACION)
        socket = servidor

        hilos = ThreadPoolExecutor(
            MAX_CONEXIONES, MAX_CONEXIONES, 30, TimeUnit.SECONDS,
            LinkedBlockingQueue(COLA_CONEXIONES)
        ) { tarea -> Thread(tarea, "apitosms-http").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }

        aceptador = Thread({ aceptar(servidor) }, "apitosms-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun detener() {
        runCatching { socket?.close() }
        socket = null
        hilos?.shutdownNow()
        hilos = null
        aceptador = null
    }

    private fun aceptar(servidor: ServerSocket) {
        while (!servidor.isClosed) {
            val cliente = try {
                servidor.accept()
            } catch (e: SocketException) {
                break // cerrado por detener()
            } catch (e: IOException) {
                alFallar(e)
                continue
            }
            val ip = cliente.inetAddress
            if (!filtro(ip)) {
                runCatching { cliente.close() }
                continue
            }
            try {
                hilos?.execute { atender(cliente) } ?: cliente.close()
            } catch (e: RejectedExecutionException) {
                runCatching { cliente.close() }
            }
        }
    }

    private fun atender(cliente: Socket) {
        cliente.use { s ->
            try {
                s.soTimeout = TIEMPO_SALUDO_MS
                if (s is SSLSocket) s.startHandshake()
                s.soTimeout = TIEMPO_INACTIVIDAD_MS

                val entrada = BufferedInputStream(s.getInputStream())
                val salida = BufferedOutputStream(s.getOutputStream())
                val lector = LectorHttp(entrada, s.inetAddress)

                repeat(MAX_PETICIONES_POR_CONEXION) { n ->
                    val peticion = try {
                        lector.leer(continuar = { LectorHttp.escribirContinue(salida) })
                    } catch (e: LectorHttp.ErrorDeProtocolo) {
                        LectorHttp.escribir(salida, errorDeProtocolo(e), mantenerAbierta = false)
                        return
                    } ?: return

                    val respuesta = try {
                        runBlocking { manejador.manejar(peticion) }
                    } catch (e: Exception) {
                        alFallar(e)
                        RespuestaHttp(500, RespuestaHttp.PROBLEMA,
                            """{"type":"about:blank","title":"Error interno","status":500,"codigo":"error-interno"}"""
                                .toByteArray())
                    }

                    val ultima = n == MAX_PETICIONES_POR_CONEXION - 1
                    val cerrar = ultima || peticion.version == "HTTP/1.0" ||
                        peticion.cabecera("connection")?.equals("close", ignoreCase = true) == true
                    LectorHttp.escribir(salida, respuesta, mantenerAbierta = !cerrar)
                    if (cerrar) return
                }
            } catch (e: SocketTimeoutException) {
                // Inactividad entre peticiones, o un saludo que no llega: se cierra sin más.
            } catch (e: SSLException) {
                // Un cliente que no confía en el certificado corta el saludo. Es lo esperado
                // cuando la huella no coincide, y no merece una traza.
            } catch (e: IOException) {
                // El cliente se fue.
            }
        }
    }

    private fun errorDeProtocolo(e: LectorHttp.ErrorDeProtocolo): RespuestaHttp {
        val cuerpo = """{"type":"about:blank","title":"Petición HTTP no válida","status":${e.estado},"detail":"${
            e.message.orEmpty().replace("\"", "'")
        }","codigo":"http-no-valido"}"""
        return RespuestaHttp(e.estado, RespuestaHttp.PROBLEMA, cuerpo.toByteArray(Charsets.UTF_8))
    }

    companion object {
        val PROTOCOLOS = setOf("TLSv1.2", "TLSv1.3")

        const val MAX_CONEXIONES = 8
        const val COLA_CONEXIONES = 16
        const val COLA_ACEPTACION = 32
        const val MAX_PETICIONES_POR_CONEXION = 1000
        const val TIEMPO_SALUDO_MS = 10_000
        const val TIEMPO_INACTIVIDAD_MS = 30_000
    }
}
