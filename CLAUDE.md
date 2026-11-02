# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Toda la documentación de este proyecto está en español, igual que los comentarios y el KDoc
> del código. La línea de arriba se conserva en inglés porque es la cabecera estándar que
> identifica el fichero.

## Qué es APItoSMS

Un teléfono Android con SIM publica una **API HTTPS en la wifi** de la oficina; los sistemas de la
empresa le entregan mensajes y él los manda **en serie**, uno tras otro, desde su SIM. El resultado
se consulta por sondeo (`GET /api/v1/mensajes/{id}`).

Es la sexta aplicación de la casa y **la primera que va al revés**: las cinco anteriores empiezan
en un SMS que llega y terminan en otro sitio. Nació el 1 nov 2026 **copiando ficheros de SMSRelay
`4b884d3`, sin su historia** (decisión A del masterplan), porque la mitad de SMSRelay —recepción,
reglas, tres canales, seis formatos de copia— no tenía nada que hacer aquí.

El plan es `MASTERPLAN-APITOSMS.md`, **el único del repositorio**: los masterplans de las
aplicaciones anteriores no se copiaron (decisión K). La historia de por qué `SmsSender` y los
workers son como son se lee en SMSRelay. **Lo que diga este fichero gana sobre el masterplan.**

## Comandos

> **Toolchain:** el mismo que SMSRelay. JVM 17, AGP 9.4.1, Gradle 9.8.2 ejecutado con
> **Temurin 25** (`gradle/gradle-daemon-jvm.properties`), SDK con la **plataforma 37**. Para
> `dotnet/`, el SDK de .NET 10.

```bash
./gradlew assembleDebug
./gradlew installDebug                     # com.capicua.apitosms.debug, convive con la release
./gradlew test                             # 88 tests JVM, nueve clases
./gradlew test --tests "com.capicua.apitosms.domain.api.ApiDeMensajesTest"

# Release: claves en local.properties, igual que las otras cinco
#   KEYSTORE_PATH=../../Claves/capicua.jks   KEY_ALIAS=capicua
./gradlew assembleRelease                  # → app/build/outputs/apk/release/apitosms-<ver>.apk
apksigner verify --print-certs app/build/outputs/apk/release/apitosms-<ver>.apk   # 1C:6C:F6:…:EC:CA

# .NET
cd dotnet && dotnet test                   # 38 tests de Capicua.ApiToSms.Cliente
dotnet run --project dotnet/Capicua.ApiToSms.Pruebas.Web   # http://127.0.0.1:5080

# Preparar el teléfono
adb shell pm grant com.capicua.apitosms android.permission.SEND_SMS
adb shell pm grant com.capicua.apitosms android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +com.capicua.apitosms
adb shell settings put global sms_outgoing_check_max_count 10000    # Android pide confirmación
adb shell settings put global sms_outgoing_check_interval_ms 60000  # pasados 30 SMS en 30 min

adb logcat -s "ServidorHttps" "ColaWorker" "SmsSender" "OrphanRescueWorker" "ApiServerService"
```

## El estado

**Fases 1 a 9 del masterplan cerradas el 1 nov 2026; la 10, a medias.** En el teléfono (Xiaomi,
HyperOS, Android 16, SIM Telcel) pasaron TLS con huella fijada, `401` uniforme, `422` por campo,
idempotencia, lotes, pausa, cancelación, el bloqueo por fuerza bruta, la pantalla de Claves con
`FLAG_SECURE` y SMS reales recibidos. Faltan las pruebas de resistencia (lote de 50, pantalla
apagada, reinicio con cola, matar la app enviando, quitar la wifi) y la importación de un `.p12`
de la CA de la empresa. La tabla, fila a fila, está en la fase 10.

**El repositorio es `github.com/jbautistamartin/APItoSMS`** (público, creado el 1 nov 2026 antes
de la 1.0.0; decisión G). Hasta la release la rama por defecto es `develop`, porque `main` solo
tiene el commit vacío de inicio; al publicar, `main` recibe el código y vuelve a serlo. Sin
releases, el comprobador de actualizaciones no encuentra ninguna y no avisa. El trabajo va en
`develop`.

## Arquitectura

```
Computadora ── HTTPS + Bearer ats_… ──► ServidorHttps (data/servidor, en ApiServerService)
                                          filtro de IP ANTES del saludo TLS
                                          LectorHttp → PeticionHttp
                                        ApiDeMensajes (domain/api, Kotlin puro)
                                          ruta → clave → red → ritmo → ámbito → cuerpo
                                          INSERT en Room                ← outbox: antes del 202
                                          ColaDeEnvios.despertar()
                                        ColaWorker (un solo trabajo único)
                                          ProcesadorDeCola.procesarSiguiente()  ← Kotlin puro
                                          SmsSender.enviar(), esperando cada sentIntent
                                          pausa entre mensajes, y otra vuelta
                                        EntregaReceiver: informe de entrega → ENTREGADO
```

### El servidor (`data/servidor/`)

- **Es propio, sobre `SSLServerSocket`, no Ktor.** El motor CIO no tiene TLS de servidor, y Netty
  o Jetty pesan varios MB por algo que atiende a unas pocas computadoras. `LectorHttp` es
  HTTP/1.1 mínimo con límites: línea de 8 KB, 64 cabeceras, 16 KB de cabeceras, 1 MB de cuerpo, 8
  conexiones, 10 s de saludo TLS y 30 s de inactividad.
- **`Transfer-Encoding` se rechaza con `501`.** Sin `Content-Length` el cuerpo mide cero
  (RFC 9112, 6.3). Las dos cosas se aprendieron en el teléfono: un `411` para un `POST` sin cuerpo
  dejó **sin pausar** la cola en la primera prueba, y `JsonContent` de .NET manda *chunked*.
- **El filtro de IP va al aceptar la conexión**, antes del saludo TLS: IP bloqueada por fuerza
  bruta, o fuera de la lista global de redes (vacía = la subred de la wifi). Un rechazo cierra
  el socket sin contestar.
- **Se ata a la IP wifi** (`RedLocal`), nunca a `0.0.0.0`: con IPv6 los datos móviles pueden tener
  IP pública. Si cambia la red, se reata.
- **`ApiServerService`** es un ForegroundService `specialUse` (no `dataSync`, que Android 15 corta
  a las 6 h) con `WifiLock` de alto rendimiento y `WakeLock` parcial.
- **El certificado** (`CertificadoDelServidor`) vive siempre en `AndroidKeyStore`: generado allí
  (EC P-256, autofirmado) o importado de un `.p12` con `KeyProtection`. Al importar se comprueba la
  caducidad, el tamaño de la clave (RSA ≥ 2048, EC ≥ P-256) y se avisa si el SAN no lleva la IP
  wifi. `Huellas` calcula la del certificado (hex con `:`) y la de la clave pública
  (`sha256//base64`, la de `curl --pinnedpubkey`).
- **No hay generador de certificados** (decisión L): la empresa tiene su propia CA.

### La API (`domain/api/ApiDeMensajes.kt`)

Kotlin puro, probado en la JVM con los dobles de `app/src/test/.../Dobles.kt`, que reproducen las
consultas del DAO. El contrato está en `docs/openapi.yaml` y los errores en `docs/errores.md`;
**`ContratoOpenApiTest` falla si `ApiDeMensajes.RUTAS` y el YAML no tienen las mismas rutas**, o si
los estados del YAML no son los del enum. Una ruta nueva va en los dos sitios y en
`Capicua.ApiToSms.Cliente`.

- **Los nombres del JSON (`domain/api/Json.kt`) y los de `EstadoMensaje` son el contrato.** Viajan
  tal cual, y los estados se guardan como texto en Room: renombrar uno rompe a los clientes y a la
  base a la vez.
- **Campo desconocido = `400`** (`ignoreUnknownKeys = false`): quien escribe `destinatario` tiene que
  enterarse en ese momento.
- **Un `401` es igual para los cuatro motivos** (sin cabecera, mal formada, inexistente, revocada).
  El motivo real va solo al registro.
- **Las admisiones se serializan con un `Mutex`**: la idempotencia y la cuota leen y luego
  insertan, y dos reintentos simultáneos crearían dos mensajes.
- **Idempotencia**: misma `Idempotency-Key` y mismo contenido (huella SHA-256 de destino, texto,
  referencia y caducidad) devuelve el original con `Idempotent-Replayed`; otro contenido, `409`.
  En un lote, cada mensaje guarda `clave#índice`. La clave vive lo que vive el mensaje.
- **La cuota se cuenta en partes SMS**, no en mensajes, y se reinicia a medianoche del teléfono.
- **Paginación por cursor** `base64url("ms|id")`, del más reciente al más antiguo.

### La cola (`domain/cola/ProcesadorDeCola.kt`, `worker/`)

- **Un solo trabajo único**, `cola_envios`, encolado con `APPEND_OR_REPLACE`: si el worker está
  terminando cuando entra un mensaje, queda otra pasada detrás en vez de esperar al rescate.
- `ProcesadorDeCola.procesarSiguiente()` devuelve un `Paso`: `Procesado`, `NadaPendiente`,
  `Pausada` o **`Bloqueada`**. Este último es la falta del permiso `SEND_SMS`: la pasada para **sin
  gastar intentos**, porque un permiso retirado no debe convertir la cola entera en `FALLIDO`.
- **`ColaWorker` corta a los 8 minutos** (`Constants.MAX_PASADA_COLA_MS`) y deja otra pasada
  encadenada, antes de que WorkManager lo corte a los 10 a mitad de un envío. No usa
  `setForeground()`: sería una segunda notificación fija.
- **Un error transitorio no bloquea la cola** (decisión B): backoff lineal con tope, y pasa el
  siguiente. **Un `ENVIANDO` huérfano se reenvía** (decisión C): al menos una vez, no como mucho una.
- **`OrphanRescueWorker`** (cada 30 s) devuelve a `EN_COLA` lo que se quedó `ENVIANDO`, relanza la
  cola si hay algo elegible **y levanta el servidor si debería estar encendido y no lo está**. (Lo
  que pasa su `caducaEn` lo marca `CADUCADO` el propio `ProcesadorDeCola` al llegarle el turno.) Esto último es lo que lo recupera en HyperOS, que tras `adb install` no entrega
  `MY_PACKAGE_REPLACED` a tiempo. Cada ciclo registra un «interrupted reason 1» de WorkManager: es
  el propio rescate reemplazándose al reencolarse, y es inofensivo.
- **`ENVIADO` puede ser el estado final.** Telcel no manda informes de entrega cuando el SMS sale por
  VoWiFi (IMS/IWLAN); el registro de la radio no trae ningún `StatusReport`. No es un fallo de la
  app, y la biblioteca C# no espera para siempre.

### Seguridad

- **Claves** `ats_` + 43 caracteres base62 (256 bits). Se guarda **solo el SHA-256**, comparado en
  tiempo constante; con esa entropía no hace falta un hash lento. El secreto se enseña una vez.
- **Ámbitos** `ENVIAR`, `CONSULTAR`, `ADMIN`. Sin `ADMIN` una clave solo ve sus mensajes, y uno
  ajeno da `404`, no `403`.
- **Por clave**: redes CIDR permitidas, ritmo (token bucket, `429` + `Retry-After`) y cuota diaria.
- **Fuerza bruta**: 10 fallos en 5 min bloquean la IP 15 min, a nivel TCP. El Panel la desbloquea.
- **Destinos**: normalización E.164 con el prefijo de país de los ajustes, prefijos permitidos y
  bloqueados (`+52900` bloquea tarificación especial), tope de partes por mensaje.
- **La pantalla de Claves** es una subpantalla del Panel, no una pestaña: `PuertaDeCredencial` pide
  `BiometricPrompt` (`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`) con 5 minutos de vigencia en memoria,
  y la pantalla lleva `FLAG_SECURE`. Copiar el secreto lo marca `EXTRA_IS_SENSITIVE`.
- **`allowBackup="false"`** y `data_extraction_rules.xml` excluye todo: la base no sale en la copia
  de Google. **La copia propia (`CopiaDeAjustes`, formato 2) no lleva secretos salvo que se marque
  «Incluir los datos confidenciales»** (decisión N), como las credenciales en SMSRelay:
  - Sin marcar, las claves llegan revocadas, con un hash al azar, para regenerarlas.
  - Marcada, viajan el hash de cada clave y si estaba activa, y el `.p12` importado con su
    contraseña: el teléfono de repuesto acepta las mismas claves y enseña la misma huella. Para
    eso, **al importar un `.p12` se guarda cifrado** (`CopiaDelCertificado`: AES-GCM con una clave
    de `AndroidKeyStore`, en `noBackupFilesDir`); generar un certificado borra esa copia. El
    certificado generado no viaja nunca. Una clave cuyo hash ya está no se duplica al importar.
  - Se acota con los mismos rangos que el formulario de Ajustes (`ValidadorDeAjustes`); si se
    cambia un rango, se cambia en los dos.

### Interfaz

Cinco pestañas en un `ViewPager2` (`Pestana.kt` es la fuente de verdad del orden): **Panel,
Mensajes, Registro, Ajustes, Acerca de**. **Las pantallas siguen el orden y el estilo de las de
SMSRelay**: título y resumen de una línea en el Panel; título y recuento en Mensajes y Registro;
y Ajustes con sus mismas secciones y en el mismo orden —el interruptor que manda (aquí el
servidor; allí la recepción), fiabilidad en segundo plano, el «destino» (conexión, certificado,
claves y SMS), límites, envío y reintentos, Guardar y la copia de seguridad—, con los títulos de
sección fuera de las tarjetas. Si se cambia una, se mira la otra. `nav_graph.xml` solo tiene `homeFragment` y
`clavesFragment`. El Panel enseña avisos de lo que falta (permiso, batería, límite de SMS de
Android, IPs bloqueadas, cola bloqueada, ninguna clave). **No hay QR** (decisión M).

«Acerca de» lleva las seis tarjetas de la familia en el mismo orden; la primera, que en las otras
explica los marcadores, aquí es «La API en dos líneas».

## Los ficheros compartidos con SMSRelay

**No hay biblioteca común**, y desde la decisión A tampoco historia común: nada recuerda que un
arreglo en SMSRelay puede hacer falta aquí, salvo esta lista. Si tocas uno de estos ficheros, mira
su gemelo en `SMSRelay/` (y viceversa; su `CLAUDE.md` debería tener la lista inversa).

| Idénticos o casi (≥ 0,95) | Divergieron pero son los mismos (0,8–0,95) | Mismo nombre, otro contenido |
|---|---|---|
| `data/sms/ProveedorDeSims` | `data/sms/SmsSender` (informe de entrega) | `domain/transport/EnviadorDeMensajes`, `ResultadoEnvio` |
| `data/update/*` (las cuatro) | `receiver/BootReceiver` | `worker/OrphanRescueWorker`, `HealthMonitorWorker`, `ColaDeEnvios` |
| `presentation/about/*` | `presentation/home/Pestana` | `data/config/AppConfig`, `ConfigDataStore` |
| `presentation/home/HomeFragment`, `PestanasAdapter`, `PestanasViewModel` | `presentation/logs/*` | `domain/model/LogEntry`, `LogTipo` |
| `presentation/common/NestedScrollableHost` | `di/DatabaseModule` | `di/TransportModule`, `RepositoryModule` |
| `util/Extensions`, `InicioAutomatico`, `OptimizacionBateria` | | `presentation/settings/*` |
| `MainActivity`, `di/ConfigModule`, `di/WorkerModule` | | |

Las dos columnas de la izquierda son las que importan: **`SmsSender`** (la espera de cada
`sentIntent`), **el comprobador de actualizaciones** y **`OptimizacionBateria`/`InicioAutomatico`**
son donde un arreglo en un lado casi seguro hace falta en el otro.

## Convenciones

Las de la familia: miembros en español, sufijos de framework en inglés (`ColaWorker`,
`ApiServerService`), KDoc y comentarios en español. **Todo `.kt` lleva la cabecera LGPL-2.1 de
cuatro líneas** con `APItoSMS para Android` en la primera; todo `.cs`, la misma con
`APItoSMS — <proyecto>`. La licencia de `dotnet/` está en `Directory.Build.props`
(`LGPL-2.1-only`), igual que el `test-server` de SMSRelay.

- **El icono es magenta/frambuesa, tono 40** (`#A4005F`; el degradado del fondo, `#C8287A` →
  `#64003A`), con la flecha que **entra** al bocadillo. **El fondo del icono y `md_theme_primary` se
  cambian juntos**, y la capa monocroma también.
- **`Migraciones.TODAS` está vacío y el esquema es v1.** Desde que la app se instaló en el teléfono
  de pruebas con datos dentro, cambiar la v1 sin migración hace que Room aborte. Todo cambio de
  esquema sube la versión y escribe su `Migration`.
- **Los tests son JVM puro.** Lo que decide —la API, la cola, la seguridad, el lector HTTP, la copia,
  el validador— está separado de Android para poder probarse. Mantén esa separación.

## `dotnet/`

Todo proyecto .NET empieza por **`Capicua.`**, el nombre de la empresa, en carpeta, ensamblado y
*namespace*. Los tests no van al release.

| Proyecto | Qué es |
|---|---|
| `Capicua.ApiToSms.Cliente` | La biblioteca. `ClienteApiToSms` + `AddApiToSms`. Va al release como DLL con su XML (decisión I) |
| `Capicua.ApiToSms.Pruebas.Web` | La web de pruebas. Solo `127.0.0.1:5080`; `conexion.json` lleva la clave en claro y está en `.gitignore`. Va al release autocontenida, `win-x64` y `linux-x64` |
| `Capicua.ApiToSms.Cliente.Tests` | xUnit: protocolo con `ManejadorFalso`, *pinning* contra un Kestrel con TLS |

- **El *pinning* no mira cadena ni nombre**: solo que la huella (del certificado o de la clave
  pública) coincida, en tiempo constante. Sin huella, validación normal del sistema.
- **Los cuerpos se serializan antes de enviarlos** (`Cuerpo<T>()`) para que lleven
  `Content-Length`. `JsonContent` los manda *chunked* y el teléfono contesta `501`; un test lo
  vigila.
- **Reintenta** fallos de red, `5xx` y `429` con `Retry-After` ≤ 60 s (el ritmo); no un `429` de
  cuota, que pide horas. Todos los envíos llevan `Idempotency-Key`, así que reintentar es seguro.
- **Sin simulador del teléfono** (decisión F): lo que no cubren los tests se prueba contra el
  teléfono.

## Probar en el teléfono

- **Los SMS de prueba van solo a un número del dueño.** Nunca a números inventados: en la primera
  prueba, dos números falsos llegaron al operador porque una pausa no se aplicó.
- **Antes de mandar algo que no deba salir, pausa la cola y comprueba que `pausada` es `true`.**
- Las credenciales de prueba no se guardan en el repositorio ni en la memoria.
- Los ajustes globales del límite de SMS del teléfono de pruebas están subidos (`10000` / `60000`).
