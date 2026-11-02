// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.capicua.apitosms.data.update.ComprobadorDeActualizaciones
import com.capicua.apitosms.data.update.ResultadoActualizacion
import com.capicua.apitosms.databinding.ActivityMainBinding
import com.capicua.apitosms.presentation.home.Pestana
import com.capicua.apitosms.presentation.home.PestanasViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Activity principal y única de la aplicación.
 *
 * Responsabilidades:
 * 1. Atar la barra inferior al ViewPager2 de pestañas que vive en HomeFragment.
 * 2. Repartir los insets del sistema, porque con `targetSdk 35` Android 15 dibuja la ventana
 *    de borde a borde y no hay forma de desactivarlo.
 * 3. Solicitar los permisos en tiempo de ejecución necesarios para enviar SMS.
 *
 * En un dispositivo dedicado estos permisos se pueden pre-conceder vía MDM o ADB:
 *   adb shell pm grant com.capicua.apitosms android.permission.SEND_SMS
 *   adb shell pm grant com.capicua.apitosms android.permission.READ_PHONE_STATE
 *   adb shell pm grant com.capicua.apitosms android.permission.POST_NOTIFICATIONS
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    /**
     * El comprobador de versiones. Se usa aquí, y no solo en «Acerca de», para que enterarse de
     * que hay una versión nueva no dependa de que alguien entre en esa pantalla.
     */
    @Inject
    lateinit var comprobadorDeActualizaciones: ComprobadorDeActualizaciones

    /** Pestaña visible. La barra la escribe al pulsar y la lee cuando el usuario desliza. */
    private val pestanasViewModel: PestanasViewModel by viewModels()

    /**
     * true mientras [observarPestana] está marcando un item por código.
     *
     * La barra inferior no distingue una pulsación de un `selectedItemId =`: avisa igual en los
     * dos casos. Esta bandera es lo que diferencia «el usuario ha pulsado una pestaña», que sí
     * debe abandonar la subpantalla en la que esté, de «la barra se está poniendo al día», que
     * no debe tocar la pila de navegación. Sin ella, restaurar la aplicación tras una muerte de
     * proceso cerraría la pantalla de claves que estuviera abierta: el ViewModel vuelve a la
     * primera pestaña mientras la barra restaura la que tenía guardada, y el ajuste resultante
     * se leería como una pulsación.
     */
    private var sincronizandoBarra = false

    // ── Launcher de permisos ──────────────────────────────────────────────────

    private val solicitarPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        resultados.forEach { (permiso, concedido) ->
            Timber.d("Permiso '$permiso': ${if (concedido) "CONCEDIDO" else "DENEGADO"}")
        }

        // SEND_SMS es el imprescindible: sin él la cola se queda bloqueada (los mensajes no
        // gastan intentos, esperan). READ_PHONE_STATE solo sirve para elegir SIM.
        if (resultados[Manifest.permission.SEND_SMS] == false) {
            mostrarDialogoPermisoObligatorio()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarInsets()
        configurarIconosDeBarras()
        configurarNavegacion()
        verificarYSolicitarPermisos()
        comprobarSiHayVersionNueva()
    }

    // ── Versión nueva ─────────────────────────────────────────────────────────

    /**
     * Pregunta —como mucho una vez al día, y solo si el aviso está encendido— si hay publicada
     * una versión más nueva, y lo enseña como un punto en la pestaña de «Acerca de», que es
     * donde está la tarjeta que lo cuenta y deja instalarla.
     *
     * Un punto y nada más: **ni diálogo ni notificación**. Esto corre en un teléfono que reenvía
     * SMS sin que nadie lo mire, y un aviso que se pone delante al arrancar taparía justo lo que
     * se viene a ver. Si no hay red o no toca preguntar, no pasa nada y no se dice nada.
     */
    private fun comprobarSiHayVersionNueva() {
        lifecycleScope.launch {
            val resultado = comprobadorDeActualizaciones.comprobar(forzar = false)
            if (resultado !is ResultadoActualizacion.Disponible) return@launch

            Timber.i("Versión nueva publicada: ${resultado.etiqueta}")
            binding.bottomNavigation.getOrCreateBadge(Pestana.ACERCA_DE.idMenu)
        }
    }

    // ── Insets ────────────────────────────────────────────────────────────────

    /**
     * Reparte los insets del sistema entre la raíz y la barra inferior.
     *
     * Con `targetSdk 35` Android 15 ignora `fitsSystemWindows` y dibuja siempre de borde a
     * borde: sin esto, el recorte de la cámara se come la cabecera de cada pantalla y la barra
     * de navegación del sistema queda **encima** del Bottom Navigation, que deja de poder
     * pulsarse. Eso es lo que hacía que no se pudiera salir de las pantallas que no están en
     * la barra inferior.
     *
     * Se usa `systemBars or displayCutout` porque en horizontal el recorte cae en un lateral,
     * donde no hay ninguna barra de sistema que lo cubra.
     */
    private fun configurarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { raiz, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            raiz.updatePadding(left = barras.left, top = barras.top, right = barras.right)
            // El padding inferior va en la barra, no en la raíz: así el fondo de la barra sigue
            // llegando hasta el borde de la pantalla y solo su contenido se aparta.
            binding.bottomNavigation.updatePadding(bottom = barras.bottom)
            insets
        }
    }

    /**
     * Pide iconos oscuros en las barras del sistema cuando el tema es claro.
     *
     * Es la otra mitad del borde a borde: al dibujar **bajo** la barra de estado, el fondo que
     * hay detrás de la hora y los iconos de señal pasa a ser el de la aplicación, y el sistema
     * por sí solo mantiene los iconos claros. Sobre el fondo casi blanco del tema eso deja la
     * barra de estado ilegible, que es justo como se veía en el primer teléfono.
     *
     * Se consulta el modo noche en lugar de fijarlo a `true` porque el tema es `DayNight`: si
     * algún día el fondo se vuelve oscuro, los iconos tienen que volver a ser claros solos.
     */
    private fun configurarIconosDeBarras() {
        val esModoNoche = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !esModoNoche
            isAppearanceLightNavigationBars = !esModoNoche
        }
    }

    // ── Navegación ────────────────────────────────────────────────────────────

    /**
     * Ata la barra inferior a las pestañas.
     *
     * No se usa `setupWithNavController`: las cinco pantallas de primer nivel no son destinos
     * del grafo, sino páginas del `ViewPager2` de `HomeFragment`, y el grafo solo conserva ese
     * destino y la subpantalla de claves. La barra y el pager se comunican
     * a través de [PestanasViewModel] porque no viven lo mismo: la barra pertenece a esta
     * Activity y el pager se destruye al navegar al editor.
     */
    private fun configurarNavegacion() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val pestana = Pestana.porIdMenu(item.itemId)
                ?: return@setOnItemSelectedListener false

            // Pulsar una pestaña desde la pantalla de claves tiene que volver
            // primero al pager: estando en una subpantalla, cambiar de página no se vería.
            if (!sincronizandoBarra && navController.currentDestination?.id != R.id.homeFragment) {
                navController.popBackStack(R.id.homeFragment, false)
            }
            pestanasViewModel.seleccionar(pestana)
            true
        }

        observarPestana()
    }

    /**
     * Refleja en la barra la pestaña activa, que es lo que mantiene el icono marcado en su
     * sitio cuando el cambio viene de un deslizamiento y no de una pulsación.
     *
     * Escribir `selectedItemId` vuelve a disparar el listener de arriba, pero eso no se
     * realimenta: `seleccionar` recibiría la pestaña que ya está y un `StateFlow` no reemite un
     * valor igual al actual. Lo que sí hay que neutralizar es el salto a la pila de navegación,
     * y de eso se encarga [sincronizandoBarra]. La primera emisión es la que deja el panel
     * marcado al arrancar.
     */
    private fun observarPestana() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pestanasViewModel.pestanaActual.collect { pestana ->
                    if (binding.bottomNavigation.selectedItemId == pestana.idMenu) return@collect

                    // El listener se ejecuta dentro de la asignación, de forma sincrónica, así
                    // que basta con rodearla para que vea la bandera levantada.
                    sincronizandoBarra = true
                    binding.bottomNavigation.selectedItemId = pestana.idMenu
                    sincronizandoBarra = false
                }
            }
        }
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    private fun verificarYSolicitarPermisos() {
        val permisosPendientes = permisosNecesarios().filter { permiso ->
            ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED
        }

        if (permisosPendientes.isEmpty()) {
            Timber.d("Todos los permisos ya concedidos")
            return
        }

        Timber.d("Solicitando ${permisosPendientes.size} permiso(s): $permisosPendientes")
        solicitarPermisos.launch(permisosPendientes.toTypedArray())
    }

    /**
     * Lista de permisos en tiempo de ejecución que la aplicación necesita.
     * POST_NOTIFICATIONS solo es obligatorio en Android 13+ (API 33).
     */
    private fun permisosNecesarios(): List<String> = buildList {
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun mostrarDialogoPermisoObligatorio() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permiso_sms_titulo)
            .setMessage(R.string.permiso_sms_mensaje)
            .setPositiveButton(R.string.permiso_entendido, null)
            .show()
    }
}