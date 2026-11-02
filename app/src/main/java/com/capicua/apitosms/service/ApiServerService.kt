// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.capicua.apitosms.BuildConfig
import com.capicua.apitosms.MainActivity
import com.capicua.apitosms.R
import com.capicua.apitosms.data.config.AppConfig
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.data.servidor.CertificadoDelServidor
import com.capicua.apitosms.data.servidor.RedLocal
import com.capicua.apitosms.data.servidor.ServidorHttps
import com.capicua.apitosms.domain.api.ApiDeMensajes
import com.capicua.apitosms.domain.cola.RegistroDeAuditoria
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.domain.red.Enlace
import com.capicua.apitosms.domain.red.Enlaces
import com.capicua.apitosms.domain.seguridad.BloqueoPorFallos
import com.capicua.apitosms.domain.seguridad.ListaDeRedes
import com.capicua.apitosms.data.repository.MensajeRepository
import com.capicua.apitosms.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.net.InetAddress
import java.time.Instant
import javax.inject.Inject

/**
 * El servicio en primer plano que mantiene la API escuchando.
 *
 * ## Tipo `specialUse`
 * Un servidor que escucha en la red local no encaja en ninguno de los tipos con nombre de
 * Android 14. `dataSync` sería lo más parecido, pero desde Android 15 tiene un tope de seis
 * horas al día, y esto tiene que estar encendido siempre. `specialUse` no tiene tope y pide
 * explicar el uso en el manifiesto, que es lo que hace la `property` del servicio.
 *
 * ## Lo que mantiene despierto
 * - Un `WifiLock`: con la pantalla apagada, la wifi entra en ahorro y deja de contestar a las
 *   conexiones entrantes aunque la aplicación siga viva. Es el fallo más desconcertante de una
 *   API en un teléfono: funciona en la mesa y deja de hacerlo en cuanto la pantalla se apaga.
 * - Un `WakeLock` parcial: la CPU, para que el servidor atienda.
 * - La exención de Doze sigue haciendo falta, para WorkManager.
 *
 * ## La red
 * Un [ServidorHttps] por cada sitio en el que escuchar ([Enlace]), nunca en `0.0.0.0`:
 * - La IP wifi o Ethernet ([RedLocal.actual]), que se reata si cambia.
 * - Si `escucharPorUsb`, la del anclaje de red USB ([RedLocal.anclajeUsb]). Android no avisa al
 *   encenderlo ni al apagarlo, así que con la opción encendida se vuelve a mirar cada
 *   [VIGILANCIA_USB_MS].
 * - Si `escucharPorAdb`, `127.0.0.1`, para `adb forward`. No depende de ninguna red.
 *
 * Qué arrancar y qué parar lo decide [Enlaces.planificar], en Kotlin puro. Sin ningún enlace no
 * escucha en nada: queda en [EstadoServidor.Estado.EsperandoRed].
 */
@AndroidEntryPoint
class ApiServerService : Service() {

    @Inject lateinit var configDataStore: ConfigDataStore
    @Inject lateinit var certificado: CertificadoDelServidor
    @Inject lateinit var redLocal: RedLocal
    @Inject lateinit var api: ApiDeMensajes
    @Inject lateinit var bloqueo: BloqueoPorFallos
    @Inject lateinit var registro: RegistroDeAuditoria
    @Inject lateinit var mensajes: MensajeRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cambio = Mutex()

    /** Un servidor por enlace. Solo se toca con [cambio] cogido. */
    private val servidores = mutableMapOf<Enlace, ServidorHttps>()
    /** El puerto con el que se arrancaron los de [servidores]. */
    private var puertoActual: Int? = null
    /** Los enlaces cuyo fallo al arrancar ya está en el registro: no se repite cada pocos segundos. */
    private val fallosAnotados = mutableSetOf<Enlace>()
    private var dejarDeObservarRed: () -> Unit = {}
    private var observacionConfig: Job? = null
    private var vigilanciaUsb: Job? = null

    /** La configuración vigente, para el filtro de conexiones, que corre en el hilo del socket. */
    @Volatile private var config = AppConfig()

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanal()
        ponerEnPrimerPlano("Arrancando…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ponerEnPrimerPlano(textoDeEstado())
        if (intent?.action == ACCION_REINICIAR) {
            scope.launch { reiniciar("certificado o ajustes cambiados") }
        } else if (observacionConfig == null) {
            arrancar()
        }
        return START_STICKY
    }

    private fun arrancar() {
        adquirirLocks()
        dejarDeObservarRed = redLocal.observar { scope.launch { recalcular() } }

        observacionConfig = scope.launch {
            configDataStore.config.collect { nueva ->
                val anterior = config
                config = nueva
                when {
                    nueva.puerto != anterior.puerto && puertoActual != null ->
                        reiniciar("puerto cambiado a ${nueva.puerto}")
                    nueva.escucharPorUsb != anterior.escucharPorUsb ||
                        nueva.escucharPorAdb != anterior.escucharPorAdb -> {
                        vigilarUsb(nueva.escucharPorUsb)
                        recalcular()
                    }
                }
            }
        }
        scope.launch {
            // Notificación al día con la cola, para ver de un vistazo que avanza.
            combine(
                EstadoServidor.estado,
                mensajes.observarEnEstado(EstadoMensaje.EN_COLA)
            ) { _, enCola -> enCola }
                .map { textoDeEstado(it) }
                .distinctUntilChanged()
                .collect { actualizarNotificacion(it) }
        }
        scope.launch {
            config = configDataStore.config.first()
            vigilarUsb(config.escucharPorUsb)
            recalcular()
        }
    }

    /**
     * Con la opción del anclaje USB encendida, vuelve a mirar cada [VIGILANCIA_USB_MS] si el
     * anclaje apareció o desapareció: Android no avisa. Mirar es barato (leer las interfaces), y
     * [recalcular] no toca nada si nada cambió.
     */
    @Synchronized
    private fun vigilarUsb(activa: Boolean) {
        if (!activa) {
            vigilanciaUsb?.cancel()
            vigilanciaUsb = null
            return
        }
        if (vigilanciaUsb?.isActive == true) return
        vigilanciaUsb = scope.launch {
            while (true) {
                delay(VIGILANCIA_USB_MS)
                recalcular()
            }
        }
    }

    /**
     * Pone los servidores de acuerdo con lo que hay ahora: arranca los enlaces nuevos, para los que
     * ya no están y vuelve a arrancar los que murieron solos. No hace nada si nada cambió.
     */
    private suspend fun recalcular() = cambio.withLock {
        val c = config
        val deseados = Enlaces.deseados(
            local = redLocal.actual(),
            usb = if (c.escucharPorUsb) redLocal.anclajeUsb() else null,
            escucharPorUsb = c.escucharPorUsb,
            escucharPorAdb = c.escucharPorAdb
        )
        val vivos = servidores.filterValues { it.escuchando }.keys
        val plan = Enlaces.planificar(servidores.keys, vivos, deseados)
        if (plan.vacio && EstadoServidor.estado.value !is EstadoServidor.Estado.Detenido) return@withLock

        for (enlace in plan.parar) {
            servidores.remove(enlace)?.detener()
            if (enlace !in deseados) anotar("Deja de escuchar por ${enlace.tipo.etiqueta} (${enlace.ip})")
        }
        fallosAnotados.retainAll(deseados.toSet())

        val errores = mutableListOf<String>()
        for (enlace in plan.arrancar) {
            try {
                val nuevo = ServidorHttps(
                    direccion = enlace.direccion,
                    puerto = c.puerto,
                    sslContext = certificado.sslContext().also { anotarCertificadoSiNuevo() },
                    manejador = api,
                    filtro = { ip -> admitirConexion(ip, enlace) },
                    alFallar = { Timber.e(it, "ServidorHttps") },
                    alCaer = { scope.launch { reatarTrasCaida(enlace) } }
                )
                nuevo.iniciar()
                servidores[enlace] = nuevo
                fallosAnotados.remove(enlace)
                anotar("Servidor escuchando en https://${enlace.ip}:${c.puerto} (${enlace.tipo.etiqueta})")
            } catch (e: Exception) {
                Timber.e(e, "ApiServerService: no se pudo escuchar por ${enlace.tipo.etiqueta}")
                val motivo = "no se pudo escuchar en ${enlace.ip}:${c.puerto} (${enlace.tipo.etiqueta}): ${e.message}"
                errores += motivo
                if (fallosAnotados.add(enlace)) anotar("Error del servidor: $motivo")
            }
        }
        puertoActual = c.puerto
        publicarEstado(deseados, c.puerto, errores)
    }

    private suspend fun publicarEstado(deseados: List<Enlace>, puerto: Int, errores: List<String>) {
        val puntos = deseados.filter { it in servidores }
            .map { EstadoServidor.Punto(it.tipo, it.ip, puerto) }
        val anterior = EstadoServidor.estado.value
        val nuevo = when {
            puntos.isNotEmpty() -> EstadoServidor.Estado.Escuchando(puntos)
            errores.isNotEmpty() -> EstadoServidor.Estado.Error(errores.joinToString("; "))
            else -> EstadoServidor.Estado.EsperandoRed
        }
        EstadoServidor.cambiar(nuevo)
        if (nuevo is EstadoServidor.Estado.EsperandoRed && anterior !is EstadoServidor.Estado.EsperandoRed) {
            anotar("Sin wifi ni cable: el servidor espera a que haya red local")
        }
    }

    /**
     * El socket de [enlace] murió sin que se parase: casi siempre, la wifi se fue y se llevó la IP,
     * o se desenchufó el cable. Se espera un poco a que [RedLocal] lo refleje y se recalcula: si el
     * enlace sigue ahí se vuelve a atar, y si no, se quita.
     */
    private suspend fun reatarTrasCaida(enlace: Enlace) {
        anotar("El servidor dejó de escuchar por ${enlace.tipo.etiqueta}: se vuelve a mirar la red")
        delay(ESPERA_TRAS_CAIDA_MS)
        recalcular()
    }

    private suspend fun reiniciar(motivo: String) {
        Timber.i("ApiServerService: reinicio ($motivo)")
        cambio.withLock {
            servidores.values.forEach { it.detener() }
            servidores.clear()
            puertoActual = null
        }
        recalcular()
    }

    /**
     * El filtro de cada conexión, **antes del saludo TLS**: una IP bloqueada por fuerza bruta se
     * cierra sin más, y el resto lo decide [Enlaces.admitir] según por dónde entró.
     */
    private fun admitirConexion(ip: InetAddress, enlace: Enlace): Boolean {
        val texto = ip.hostAddress ?: return false
        if (bloqueo.estaBloqueada(texto)) return false
        val (globales, _) = ListaDeRedes.leer(ListaDeRedes.separar(config.redesPermitidas))
        return Enlaces.admitir(ip, enlace, globales)
    }

    /** La primera vez que se sirve, el certificado se genera: queda anotado su origen. */
    private suspend fun anotarCertificadoSiNuevo() {
        if (config.origenCertificado == null) {
            configDataStore.cambiarOrigenCertificado(CertificadoDelServidor.Origen.GENERADO.name)
            val huella = certificado.info(CertificadoDelServidor.Origen.GENERADO)?.huellas?.certificado
            registro.registrar(LogEntry(
                tipo = LogTipo.SEGURIDAD, timestamp = Instant.now(),
                detalle = "Certificado generado en el teléfono. Huella SHA-256: $huella"
            ))
        }
    }

    // ── Locks ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun adquirirLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        // HIGH_PERF está marcado como obsoleto en Android 14, que recomienda LOW_LATENCY; pero
        // LOW_LATENCY solo actúa con la aplicación en primer plano y la pantalla encendida, que es
        // justo cuando no hace falta. HIGH_PERF sigue siendo el que mantiene la radio atenta.
        wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "APItoSMS:servidor")
            ?.apply { setReferenceCounted(false); acquire() }
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APItoSMS:servidor")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun liberarLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    override fun onDestroy() {
        dejarDeObservarRed()
        vigilarUsb(false)
        servidores.values.forEach { it.detener() }
        servidores.clear()
        liberarLocks()
        EstadoServidor.cambiar(EstadoServidor.Estado.Detenido)
        val registroFinal = registro
        CoroutineScope(Dispatchers.IO).launch {
            registroFinal.registrar(LogEntry(tipo = LogTipo.SISTEMA, timestamp = Instant.now(), detalle = "Servidor detenido"))
        }
        scope.cancel()
        super.onDestroy()
    }

    // ── Notificación ──────────────────────────────────────────────────────

    private fun textoDeEstado(enCola: Int? = null): String {
        val base = when (val e = EstadoServidor.estado.value) {
            is EstadoServidor.Estado.Escuchando ->
                if (e.puntos.size == 1) e.url else "${e.url} (+${e.puntos.size - 1})"
            EstadoServidor.Estado.EsperandoRed -> "Sin wifi ni cable: esperando a la red local"
            is EstadoServidor.Estado.Error -> "Error: ${e.motivo}"
            EstadoServidor.Estado.Detenido -> "Arrancando…"
        }
        return if (enCola != null && enCola > 0) "$base · $enCola en cola" else base
    }

    private fun crearCanal() {
        val canal = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            Constants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Estado del servidor de la API" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(canal)
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ponerEnPrimerPlano(texto: String) {
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Constants.NOTIFICATION_ID_FOREGROUND, notificacion(texto), tipo)
    }

    private fun actualizarNotificacion(texto: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(Constants.NOTIFICATION_ID_FOREGROUND, notificacion(texto))
    }

    private suspend fun anotar(detalle: String) {
        registro.registrar(LogEntry(tipo = LogTipo.SISTEMA, timestamp = Instant.now(), detalle = detalle))
    }

    companion object {
        private const val ESPERA_TRAS_CAIDA_MS = 2_000L
        private const val VIGILANCIA_USB_MS = 3_000L
        private const val ACCION_REINICIAR = "${BuildConfig.APPLICATION_ID}.REINICIAR_SERVIDOR"

        fun iniciar(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ApiServerService::class.java))
        }

        /** Vuelve a montar el servidor: tras cambiar el certificado, por ejemplo. */
        fun reiniciar(context: Context) {
            if (EstadoServidor.estado.value is EstadoServidor.Estado.Detenido) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, ApiServerService::class.java).setAction(ACCION_REINICIAR)
            )
        }

        fun detener(context: Context) {
            context.stopService(Intent(context, ApiServerService::class.java))
        }
    }
}
