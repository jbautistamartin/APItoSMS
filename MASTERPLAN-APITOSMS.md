# MASTERPLAN — APItoSMS

> La sexta aplicación de la casa, y la primera que **invierte la dirección**. Las cinco anteriores
> empiezan en la SIM —un SMS que llega— y terminan en otro sitio. APItoSMS empieza en otro sitio
> —una petición HTTP desde una computadora de la red local— y termina en la SIM: un SMS que sale.
>
> Escrito el 1 nov 2026 y revisado el mismo día. **Fases 1 a 9 cerradas el 1 nov 2026**; la 10
> está a medias: las pruebas de seguridad y de protocolo pasaron en el teléfono, y faltan las de
> resistencia (lote largo, pantalla apagada, reinicio) y la publicación. El repositorio está en
> GitHub desde el 1 nov 2026, antes de la 1.0.0 (decisión G, cambiada), con `develop` como rama
> por defecto hasta la release. Las decisiones del punto 9 están tomadas,
> incluidas las dos que salieron por el camino (L y M).

---

## 1. Idea central

Una empresa necesita mandar SMS en serie —avisos, códigos, recordatorios— desde sus propios
sistemas, sin contratar un proveedor de SMS. Tiene un teléfono con SIM y una red local. APItoSMS
convierte ese teléfono en una **pasarela de envío**: expone una API HTTPS en la wifi de la
oficina, cualquier computadora autorizada le entrega mensajes, y el teléfono los manda **uno tras
otro**, por orden de llegada, con la radio propia.

```
Computadora (C#, curl, ERP…)
    │  HTTPS + API key, red local
    ▼
Servidor HTTP embebido (ForegroundService)        ← nuevo
    → validar, autenticar, limitar
    → INSERT en Room                               ← outbox: persistido ANTES de responder
    → 202 Accepted + id
    ▼
Cola serie (WorkManager, un solo trabajo único)    ← nuevo: FIFO, un SMS en vuelo
    → SmsSender (SmsManager, espera confirmación)  ← de SMSRelay, tal cual
    → actualizar Room (ENVIADO / FALLIDO / …)
    → informe de entrega → ENTREGADO
    ▲
Computadora: GET /mensajes/{id}                    ← consulta asíncrona del resultado
```

### Por qué es «el mismo diseño que SMSRelay»

Porque lo que hace fiable a SMSRelay no tiene nada que ver con la dirección del mensaje:

- **Outbox.** La API no responde `202` hasta que el mensaje está en Room. Si el teléfono se
  reinicia un segundo después, el mensaje sigue ahí y se envía al volver.
- **El transporte espera la confirmación real.** `SmsSender` ya suspende hasta que el operador
  confirma cada parte por `PendingIntent`. Sin eso, la API diría «enviado» de mensajes que nunca
  salieron.
- **Idempotencia de cliente**, ahora en las dos puntas: el worker relee la fila antes de enviar,
  y la API acepta un `Idempotency-Key` para que un reintento HTTP no duplique un SMS.
- **`OrphanRescueWorker`, `HealthMonitorWorker`, `BootReceiver`**, con el mismo papel.
- **Migraciones de Room explícitas**, esquemas exportados.

Lo que cambia es **la entrada** (un servidor HTTP en vez de un `BroadcastReceiver`) y **la
forma de la cola** (en serie en vez de un worker por reenvío).

### Lo que no es

- **No es un servicio de envíos masivos de marketing.** El operador y Android limitan el ritmo
  (punto 6), y un teléfono no compite con un agregador. Es para volúmenes de cientos al día, no
  de cientos de miles.
- **No recibe SMS ni respuestas.** Eso es SMSRelay. Las dos pueden convivir en el mismo teléfono.
- **No está expuesta a internet.** Solo red local (decisión del 1 nov 2026); el servidor se ata a
  la interfaz wifi y no escucha en datos móviles.

---

## 2. Lo que ya está hecho

- **La aplicación entera**, en `develop`: servidor HTTPS propio, cola en serie, claves con
  ámbitos, las seis pantallas, copia de ajustes. 82 tests JVM.
- **`dotnet/`**: la biblioteca cliente y la web de pruebas, con 38 tests xUnit. Sin generador de
  certificados (decisión L).
- **`docs/openapi.yaml` y `docs/errores.md`**, con un test que compara las rutas con las reales.
- **Probado en el teléfono** (Xiaomi, HyperOS, Android 16, SIM Telcel): ver la tabla de la
  fase 10.

Lo que la puesta en marcha enseñó y no estaba previsto:

- **Ktor no sirve.** El motor CIO de Ktor no tiene TLS de servidor, y Netty o Jetty pesan varios
  MB por un servidor que atiende a unas pocas computadoras. El *spike* acabó en un servidor
  HTTP/1.1 propio sobre `SSLServerSocket` (`data/servidor/`): unas 400 líneas, con límites de
  tamaño, tiempo de saludo y número de conexiones.
- **Telcel no manda informes de entrega** cuando el SMS sale por VoWiFi (IMS/IWLAN): el registro
  de la radio no trae ningún `StatusReport`. Con ese operador `ENVIADO` es el estado final, y la
  biblioteca y los documentos lo dicen.
- **Un `POST` sin cuerpo no lleva `Content-Length`**, y el servidor lo rechazaba con `411`. La
  primera pausa de la cola en el teléfono falló así y la cola siguió enviando. Ahora un cuerpo
  sin longitud es un cuerpo vacío (RFC 9112).
- **`JsonContent` de .NET manda el cuerpo *chunked***, y el servidor contesta `501`. La biblioteca
  serializa antes y manda `Content-Length`; `docs/errores.md` lo avisa para quien escriba su
  propio cliente.
- **HyperOS no reabre el servidor tras `adb install`** (no entrega `MY_PACKAGE_REPLACED` a
  tiempo). El rescate de huérfanos, que también vigila el servidor, lo levanta en menos de un
  minuto.

---

## 3. Decisiones cerradas

- **Nombre `APItoSMS`, `applicationId` `com.capicua.apitosms`**, APK `apitosms-<ver>.apk`.
  Sigue el patrón «origen → destino» de SMStoSMS, SMStoTelegram y SMStoWhats, y es literalmente
  SMSGateway al revés. Variante debug `.debug`, así que convive con las otras cinco.
- **Solo red local.** Nada de túneles, nada de puerto abierto a internet.
- **API asíncrona.** El `POST` responde `202` con un id en cuanto el mensaje está persistido; el
  resultado se consulta después.
- **Cola en serie.** Hay como mucho **un SMS en vuelo**: el siguiente no empieza hasta que el
  anterior ha confirmado, fallado o agotado su tiempo. Entre uno y otro, una pausa configurable.
- **Herramientas de prueba en C#**, en el mismo repositorio: biblioteca cliente y web de pruebas
  (fase 8). Sin generador de certificados (decisión L).
- **El certificado TLS, por dos vías** (1 nov 2026): por defecto lo genera el teléfono, y en
  Ajustes se puede **importar uno propio** (`.p12`), firmado por la CA de la empresa. Detalle en
  el punto 6.
- **Mismo toolchain que SMSRelay**: Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21, compileSdk 37,
  targetSdk 35, minSdk 26, JVM 17, Temurin 25. Y `net10.0` para lo de C#, como el `test-server`
  de SMSRelay.
- **Firma con `Claves/capicua.jks`, alias `capicua`**, como las cinco.
- **Esquema de Room a v1 limpio.** `applicationId` nuevo, base nueva, `Migraciones.TODAS` vacío.
- **Repositorio nuevo, no copia con historial** (decisión A). Es la primera vez en la casa: las
  cinco anteriores nacieron como copia con todo el `git log`. Aquí se copian **ficheros**, no
  historia, porque la mitad de SMSRelay (recepción, reglas, tres canales, seis formatos de copia)
  no tiene nada que hacer en esta aplicación. **Tampoco viajan los cinco masterplans
  anteriores** (decisión K): este es el único plan del repositorio, y la historia de por qué
  `SmsSender` y los workers son como son se lee en SMSRelay. El primer commit cita el de origen.

---

## 4. Lo que se hereda y lo que no

El repositorio es nuevo (decisión A), así que no se poda nada: **se copia solo la columna de la
izquierda**, desde SMSRelay `develop` en `4b884d3` (25 oct 2026). La de la derecha queda para que
se vea qué se dejó atrás a propósito.

| Se copia | No se copia |
|---|---|
| `data/sms/SmsSender.kt`, `ProveedorDeSims.kt` — el transporte entero | `SmsReceiver`, `SmsIngestionService` — no hay SMS entrantes |
| `EnviadorDeMensajes`, `ResultadoEnvio` — la juntura | `data/telegram/`, `data/whatsapp/`, `data/api/` — otros canales |
| `OrphanRescueWorker`, `HealthMonitorWorker`, `BootReceiver` | `domain/rules/`, pantalla de reglas — no hay reglas |
| `ConfigDataStore`, Hilt, Room, Timber | Formatos de copia 1-6 e importación de las cuatro viejas |
| Comprobador de versiones, «Acerca de», sistema de iconos | `Canal` y el *multibinding* (un solo transporte) |
| | Los cinco masterplans anteriores (decisión K) |
| `local.properties` de firma, `libs.versions.toml` | `RECEIVE_SMS`, `READ_SMS` del manifiesto |

La juntura **se conserva aunque haya un solo transporte**: es la línea que separa «qué se manda y
cuándo» de «cómo sale», y gracias a ella la cola se puede probar con un `EnviadorDeMensajes`
falso, sin radio.

---

## 5. Fases

### Fase 1 — Nacimiento: repositorio nuevo, esqueleto heredado e icono · `[x]`

- `git init` con `main` y `develop` (git-flow, como las cinco); el trabajo en `develop`. Sin
  remoto hasta el 1 nov 2026 (decisión G).
- Esqueleto Gradle copiado de SMSRelay: `settings.gradle.kts`, `build.gradle.kts`, `gradle/`
  (wrapper, `libs.versions.toml`, `gradle-daemon-jvm.properties`), `gradle.properties`,
  `.gitignore`, `LICENSE`, `local.properties` de firma (`../../Claves/capicua.jks`, alias
  `capicua`).
- Ficheros de la columna izquierda del punto 4, ya en el paquete `com.capicua.apitosms` y con su
  cabecera LGPL apuntando a APItoSMS; `ApiToSmsApplication`, `app_name` → `APItoSMS`.
- `versionCode = 1`, `versionName = "1.0.0"`, `CHANGELOG.md` a cero, `URL_PROYECTO` y la URL del
  comprobador de versiones al repositorio que existirá. Hasta la 1.0.0 recibe un 404, que ya se
  trata como «no hay releases».
- **Icono magenta/frambuesa, tono 40** (decisión E), y `md_theme_primary` a juego, **en esta
  fase**: SMSRelay documenta las dos veces que se olvidó y por qué. El glifo es una flecha que
  **entra** al bocadillo, la inversa de las demás. Con su capa monocroma.
- Esquema Room v1, `app/schemas/` exportado.
- Primer commit, que cita el origen: «copiado de SMSRelay `4b884d3`».
- **Cierre:** compila, los tests heredados pasan, instala junto a SMSRelay y se distinguen en el
  lanzador.

### Fase 2 — Lo heredado, adaptado · `[x]`

- Manifiesto: solo `SEND_SMS` de los restringidos; se añaden `INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE`, `WAKE_LOCK` y `FOREGROUND_SERVICE_SPECIAL_USE`.
- El KDoc de `EnviadorDeMensajes` se reescribe para esta aplicación: un comentario que habla de
  canales y reglas que aquí no existen es peor que ninguno. Igual con cualquier KDoc copiado que
  remita a un masterplan que aquí no está.
- `OrphanRescueWorker`, `HealthMonitorWorker` y `BootReceiver` se quedan compilando contra el
  modelo nuevo (vacío hasta la fase 3), sin lógica de reglas.
- **Cierre:** la app arranca con pantallas vacías y `SmsSender` manda un SMS desde un botón de
  depuración.

### Fase 3 — El mensaje y la cola en serie · `[x]`

El núcleo. Todo con tests JVM y un `EnviadorDeMensajes` falso.

**Entidad `MensajeSaliente`** (la outbox):

| Campo | Para qué |
|---|---|
| `id` | UUID, el que devuelve la API |
| `destino`, `texto` | Lo que se manda; destino normalizado a E.164 |
| `clave_id` | Qué API key lo pidió (auditoría y cuotas) |
| `idempotency_key`, `huella_peticion` | Deduplicar reintentos HTTP (ver abajo) |
| `referencia` | Texto libre del cliente, para cruzarlo con su sistema |
| `estado`, `intentos`, `ultimo_error` | Ciclo de vida |
| `partes` | Cuántos SMS se facturan |
| `admitido_en`, `proximo_intento_en`, `enviado_en`, `entregado_en`, `caduca_en` | Tiempos |

**Estados:**

```
EN_COLA ──► ENVIANDO ──► ENVIADO ──► ENTREGADO
   │            │           └──────► (sin informe: se queda en ENVIADO)
   │            ├─► EN_COLA (error transitorio, con proximo_intento_en)
   │            └─► FALLIDO (permanente, o intentos agotados)
   ├─► CANCELADO (DELETE antes de salir)
   └─► CADUCADO  (caduca_en vencido sin haber salido)
```

**La cola en serie:**
- Un solo trabajo único de WorkManager, `cola-envios`. El `POST` lo encola con
  `ExistingWorkPolicy.APPEND_OR_REPLACE`: si el worker está terminando justo cuando entra un
  mensaje, queda otra pasada detrás, en vez de perderse hasta el siguiente rescate (que es lo que
  pasaría con `KEEP`).
- El worker **drena**: toma el `EN_COLA` elegible más antiguo (`admitido_en`, y
  `proximo_intento_en <= ahora`), lo pasa a `ENVIANDO`, llama a `SmsSender.enviar()`, espera,
  actualiza, duerme la pausa entre mensajes y sigue. Cuando no queda nada elegible, termina.
- ~~Usa `setForeground()` para pasar de los 10 minutos~~. **Hecho de otra forma:** sin
  `setForeground()`, que obligaría a otra notificación fija además de la del servidor. La pasada
  termina sola a los 8 minutos y deja otra encadenada (`Constants.MAX_PASADA_COLA_MS`), antes de
  que WorkManager la corte a mitad de un envío.
- **Paso `Bloqueada`** (no estaba en el plan): si falta el permiso `SEND_SMS`, la pasada termina
  **sin gastar intentos** de los mensajes, y el Panel enseña el motivo. Un permiso retirado no
  debe convertir la cola entera en `FALLIDO`.
- **Un error transitorio no bloquea la cola** (decisión B): el mensaje vuelve a `EN_COLA` con
  backoff y pasa el siguiente.
- `OrphanRescueWorker` adaptado: un `ENVIANDO` sin trabajo activo vuelve a `EN_COLA`
  (decisión C), y si hay `EN_COLA` y la cola está parada, la relanza.
- **Informe de entrega**: `SmsSender` gana un `deliveryIntent` opcional; su broadcast, que puede
  llegar minutos después o nunca, pasa `ENVIADO` → `ENTREGADO`. No forma parte de la espera del
  transporte, porque muchos operadores no lo mandan.
- **Pausa global** (ajuste y endpoint de administración): la cola deja de sacar mensajes y la API
  sigue admitiendo.

**Idempotencia de la API** (al estilo de Stripe): la misma `Idempotency-Key` de la misma clave
con el mismo cuerpo devuelve el mensaje original y no crea otro; con un cuerpo distinto,
`409 Conflict`. ~~Las claves de idempotencia se guardan 48 h.~~ **Hecho:** la clave se guarda
en el propio mensaje y vive lo que vive él (la retención de mensajes, 30 días por defecto). Un lote
guarda `clave#índice` en cada mensaje.

### Fase 4 — El servidor HTTP · `[x]`

- **Primero un *spike* de medio día**: Ktor server en Android con TLS. El motor CIO es el ligero
  pero hay que comprobar que admite TLS en la versión que toque; si no, Netty (más pesado) o
  Jetty. Hay que medir el tamaño del APK y el arranque, y que el servidor pueda servir una clave
  de `AndroidKeyStore` **en los dos casos**: generada allí e importada de un `.p12` (punto 6, TLS).
  El resultado del *spike* se anota aquí antes de seguir.
- **Resultado del *spike*:** CIO no tiene TLS de servidor, y Netty y Jetty eran demasiado para
  esto. Servidor propio sobre `SSLServerSocket`, con el `KeyManager` de `AndroidKeyStore`, que
  sirve las dos claves (generada e importada) sin sacarlas de allí. El filtro de IP (bloqueadas y
  redes permitidas) va **antes del saludo TLS**, al aceptar la conexión.
- **`ApiServerService`**: ForegroundService de tipo `specialUse` (no `dataSync`, que en Android
  15 tiene un tope de 6 h). Notificación fija con la dirección y el estado de la cola.
- **`WifiLock` de alto rendimiento y `WakeLock` parcial** mientras el servidor esté encendido:
  sin ellos, con la pantalla apagada la wifi se duerme y la API deja de contestar aunque la app
  esté viva.
- **Se ata a la IP wifi**, no a `0.0.0.0`. Si cambia la red, se reata, y si no hay wifi, no
  escucha. Esto evita que la API quede accesible por datos móviles (con IPv6 puede haber IP
  pública).
- `BootReceiver` levanta también el servidor si estaba encendido.

**Endpoints** (`/api/v1`, JSON, errores como `application/problem+json`, RFC 9457):

| Método | Ruta | Ámbito | Qué hace |
|---|---|---|---|
| `POST` | `/mensajes` | `enviar` | Un mensaje → `202` `{id, estado, posicion}` |
| `POST` | `/lotes` | `enviar` | Hasta N mensajes en una petición, todo o nada → `202` con los ids |
| `GET` | `/mensajes/{id}` | `consultar` | Estado, intentos, error, tiempos |
| `GET` | `/mensajes?estado=&desde=&referencia=&cursor=` | `consultar` | Listado paginado |
| `DELETE` | `/mensajes/{id}` | `enviar` | Cancela si sigue `EN_COLA`; si no, `409` |
| `GET` | `/cola` | `consultar` | Longitud, en vuelo, pausada, ritmo, cuota restante |
| `POST` | `/cola/pausa` · `/cola/reanudar` | `admin` | Pausa de emergencia |
| `GET` | `/salud` | ninguno | `200` si el servidor vive; sin datos |

Una clave solo ve sus propios mensajes, salvo que tenga el ámbito `admin`.

- `docs/openapi.yaml`, escrito a mano y comprobado por un test que recorre las rutas reales.
- **Cierre:** `curl -k` desde la computadora manda un SMS y consulta su estado.

### Fase 5 — Seguridad · `[x]`

Ver el punto 6 completo. Aquí se implementa: claves con ámbitos, lista de IPs por clave, límites
de ritmo y cuotas, bloqueo por fallos de autenticación, lista de destinos permitidos, auditoría
y retención. Cada mecanismo con su test.

### Fase 6 — Interfaz · `[x]`

| Pestaña | Contenido |
|---|---|
| **Panel** | Servidor encendido/apagado, `https://IP:puerto`, **huella SHA-256 del certificado y de la clave pública**, con botón de copiar (sin QR: decisión M), longitud de la cola, enviados y fallidos hoy, botón de pausa, avisos de lo que falta por preparar |
| **Mensajes** | Historial con filtro por estado; detalle con todos los tiempos y el error, y cancelar si sigue en cola |
| **Claves** | **Subpantalla desde el Panel**, no pestaña: detrás de la credencial del teléfono y con `FLAG_SECURE`. Crear (se enseña **una sola vez**), nombre, ámbitos, IPs permitidas, cuotas, regenerar, revocar, último uso |
| **Ajustes** | Puerto, SIM, pausa entre mensajes, intentos, tiempo de espera, cuotas globales, prefijos permitidos, retención. **Certificado**: origen (generado o importado), sujeto, caducidad y huella; regenerar; importar `.p12` con su contraseña |
| **Registro** | Log de auditoría y de sistema |
| **Acerca de** | Las mismas tarjetas, en el mismo orden, que las otras cinco |

La guía de preparación del teléfono (punto 6, «Límite de Android») va en el Panel como aviso
mientras no esté hecha.

### Fase 7 — Copia de seguridad · `[x]`

- Exporta los ajustes, los prefijos permitidos y las claves **sin su secreto** (nombre, ámbitos,
  IPs y cuotas). Al importar, las claves llegan revocadas y hay que regenerarlas: un secreto no
  sale nunca del teléfono, con la misma línea que en SMSRelay («¿identifica a *este* teléfono?»).
- El certificado no viaja en la copia. Con uno **generado**, el teléfono nuevo tiene otra huella
  y hay que actualizar los clientes. Con uno **importado**, basta con volver a importar el mismo
  `.p12` y los clientes no cambian: es el principal motivo para usar esa vía.
- **Cambiado el 1 nov 2026 (decisión N): «Incluir los datos confidenciales»**, la casilla de
  SMSRelay. Marcada, la copia lleva el **hash** de cada clave y si estaba activa, y el **`.p12`
  importado con su contraseña**: el teléfono de repuesto se monta importando un fichero, y los
  sistemas de la empresa no cambian ni la clave ni la huella. Para que el `.p12` pueda viajar, al
  importarlo se guarda cifrado (`CopiaDelCertificado`: AES-GCM con una clave de `AndroidKeyStore`,
  en `noBackupFilesDir`). El certificado **generado** sigue sin viajar: su clave no ha salido nunca
  del almacén. Desmarcada, la copia es la de siempre. Formato 2; el 1 se sigue leyendo.
- `android:allowBackup="false"`, para que la copia de Google no se lleve la base.

### Fase 8 — Herramientas C# · `[x]`

Carpeta `dotnet/`, solución `Capicua.ApiToSms.slnx`, `net10.0`, con la cabecera LGPL en cada `.cs`.
**Sin simulador del teléfono** (decisión F): la biblioteca se prueba con tests unitarios y la web,
contra el teléfono.

| Proyecto | Qué es |
|---|---|
| **`Capicua.ApiToSms.Cliente`** | Biblioteca: `ClienteApiToSms` con `EnviarAsync`, `EnviarLoteAsync`, `ConsultarAsync`, `ListarAsync`/`ListarTodosAsync`, `EsperarResultadoAsync` (sondeo con backoff), `CancelarAsync`, `EstadoColaAsync`, `PausarAsync`/`ReanudarAsync`, y `AddApiToSms` para inyección de dependencias. Hace el *pinning* de la huella (la del certificado o la de la clave pública) en el `RemoteCertificateValidationCallback`, pone el `Idempotency-Key` y reintenta solo lo que es seguro reintentar. La que copiaría la empresa en sus sistemas |
| **`Capicua.ApiToSms.Pruebas.Web`** | La web de pruebas. ASP.NET Core con `wwwroot` estático, igual que el `test-server` de SMSRelay. Configurar URL, clave y huella; enviar uno; **generar lotes** (N mensajes a una lista de números); tabla con el estado de cada uno, en vivo (el backend sondea y lo empuja por SSE); ver la cola; pausar y reanudar. El navegador no habla con el teléfono: lo hace el backend con la biblioteca, porque un navegador no puede fijar un certificado autofirmado |
| ~~**`ApiToSms.Certificado`**~~ | **No se hace** (decisión L): la empresa tiene su propia CA, y el `.p12` que emite se importa en Ajustes |
| **`Capicua.ApiToSms.Cliente.Tests`** | xUnit de la biblioteca: el protocolo con un `HttpMessageHandler` falso (reintentos, `Idempotency-Key`, `Retry-After`, `Content-Length`, errores a excepciones, sondeo), y el *pinning* contra un Kestrel en proceso con un certificado creado en el propio test (huella buena acepta, huella mala rechaza) |

- **Distribución** (decisión I): `Capicua.ApiToSms.Cliente.dll` se adjunta al release de GitHub, junto
  al APK, con su XML de documentación; y la web de pruebas, **autocontenida** para `win-x64` y
  `linux-x64` (un zip cada una), para probar el teléfono sin instalar .NET. Los tests no van.
  **Corregido el 2 nov 2026:** la DLL y su XML sueltos pasan a ser un paquete NuGet,
  `Capicua.ApiToSms.Cliente.<versión>.nupkg`, adjunto al release y **no publicado en nuget.org**.
  Trae el XML dentro y declara su dependencia de `Microsoft.Extensions.Http`, que suelta había que
  añadir a mano. El release 1.0.0 se rehízo con él.
- **Espacio de nombres `Capicua.`** (2 nov 2026): todo proyecto .NET de la empresa empieza por
  `Capicua.`; se renombraron carpetas, `.csproj`, solución y *namespaces* de `ApiToSms.*` a
  `Capicua.ApiToSms.*`. Las clases (`ClienteApiToSms`, `AddApiToSms`) no cambiaron.
- **Cierre:** los tests pasan, y la web de pruebas manda un lote de 20 al teléfono y los ve
  terminar.
- **Cerrada con un lote de 3, no de 20:** todo SMS de prueba va al número del dueño, así que se
  mandó el lote con la cola pausada, se cancelaron dos y salió uno, seguido en vivo por SSE. El
  lote de 50 de la fase 10 sigue pendiente.

### Fase 9 — Documentación y privacidad · `[x]`

- `README.md` con la puesta en marcha completa (teléfono, red, primera clave, primer `curl`).
- `CLAUDE.md` del proyecto con el porqué de cada decisión.
- `docs/openapi.yaml` al día; un ejemplo por lenguaje: C# (la biblioteca), `curl` y PowerShell.
- Aviso de privacidad: aquí la app guarda **textos y números de terceros**, que la empresa le
  entrega. Retención y purga explicadas.
- `CLAUDE.md` raíz de `C:\Proyectos\SMS`: la sexta fila de la tabla, el linaje y el color.

### Fase 10 — Dispositivo real y release 1.0.0 · `[x]` (salvo la 9b)

Pruebas en el teléfono (Xiaomi con HyperOS, Android 16, SIM Telcel), el 1 nov 2026:

| # | Prueba | Se espera | Resultado |
|---|---|---|---|
| 1 | Un SMS desde la web de pruebas | `ENVIADO`, y `ENTREGADO` si el operador lo informa | ✅ `ENVIADO` y recibido. Telcel no manda el informe (punto 2) |
| 2 | Lote de 50 | Salen en orden, de uno en uno, con la pausa configurada | ✅ 2 nov: en orden, uno cada ~4,5 s (pausa de 3 s + envío), los 50 en 3 min 44 s |
| 3 | Pantalla apagada 30 min y otro lote | La API contesta y la cola avanza (Doze, `WifiLock`) | ✅ 2 nov: 30 min con la pantalla apagada, la batería «desenchufada» (`dumpsys battery unplug`) y Doze profundo forzado; la API contestó en 0,36 s y el lote de 3 salió sin despertar la pantalla |
| 4 | Reiniciar el teléfono con 20 en cola | Al volver, el servidor escucha y la cola sigue | ✅ 2 nov, con la cola pausada: los 20 siguen `EN_COLA` y la pausa se conserva. **El servidor no arranca hasta el primer desbloqueo** (la app no es *directBootAware* y Room vive en el almacenamiento cifrado); desbloqueado, escucha en ~20 s sin abrir la app |
| 5 | Matar la app con un mensaje `ENVIANDO` | Se rescata según la decisión C | ⚠️ 2 nov, con `am crash` en el 11 de 50: se reenvió, pero **el último**, porque el rescate solo actúa sin pasada en marcha y la pasada relanzada siguió con el 12. Arreglado: cada pasada de `ColaWorker` empieza rescatando. ✅ Repetida en Doze con un lote de 3: el 2 se rescató y salió en su sitio, antes que el 3 (~27 s de hueco hasta que WorkManager relanzó la pasada) |
| 6 | Mismo `Idempotency-Key` dos veces | Un solo SMS | ✅ `Idempotent-Replayed: true` |
| 7 | Clave revocada, IP no permitida, prefijo no permitido | `401`, `403`, `422` | ✅ `401` uniforme y `422` por campo; `403` por IP, en tests |
| 8 | 10 fallos de autenticación | IP bloqueada | ✅ Corte a nivel TCP; el botón del Panel la desbloquea |
| 9 | Huella equivocada en el cliente | El cliente se niega a conectar | ✅ `curl --pinnedpubkey` y la biblioteca C#, con las dos formas de huella |
| 9b | Importar el `.p12` de la CA de la empresa | El servidor lo sirve y la huella del Panel coincide con la del certificado | ⏳ Pendiente: falta un `.p12` de esa CA |
| 10 | Desactivar la wifi | El servidor deja de escuchar y no se ata a datos móviles | ✅ 2 nov, con la release: sin wifi no escucha en nada (tampoco en la IP de datos), y al volver se reata en ~5 s. **La primera vez no volvió**: Android destruye el socket al quitar la IP y `escuchando` seguía en `true`; arreglado en `ServidorHttps` (`alCaer`) |
| 11 | Destino inexistente | `FALLIDO` con el error del operador, y la cola sigue | ⚠️ Fallo genérico del operador, visto por accidente (punto 2); **no se repite con números inventados** |

Y la migración v1 → v2 (`ip_origen`) sobre la base del teléfono, con 84 mensajes: se leen todos, y
uno nuevo, encolado con la cola pausada y cancelado, guarda la IP del PC.

Y otras que no estaban en la tabla: pausa y reanudación por la API (tras arreglar el `411`),
cancelación, la pantalla de Claves con `FLAG_SECURE` (la captura sale negra) y la credencial,
y las pestañas Mensajes y Registro.

**Regla para las pruebas:** los SMS reales solo van a un número del dueño, y una prueba que deba
no enviar se hace con la cola pausada, **comprobando que la pausa se aplicó** antes de mandar
nada.

Y después, la publicación, **hecha el 2 nov 2026**:

1. ~~Crear `github.com/jbautistamartin/APItoSMS`~~ — hecho el 1 nov 2026, antes de la release
   (decisión G). Al publicar, `main` recibe el código y vuelve a ser la rama por defecto.
2. El procedimiento de siempre: `CHANGELOG`, `assembleRelease`, huella del APK con `apksigner`.
3. `dotnet build -c Release` de `Capicua.ApiToSms.Cliente`, firmada con nombre seguro
   (`capicua.snk`, token `a313609336f4f9ff`), y `dotnet publish -c Release -r <rid> --self-contained`
   de `Capicua.ApiToSms.Pruebas.Web`, en un solo ejecutable, para `win-x64` (zip) y `linux-x64` (`.tar.gz`).
4. `gh release create v1.0.0` con **el APK, la DLL con su XML y los dos paquetes de la web**.
5. Copiarlo también a `C:\Proyectos\releases\SMS` y poner al día su `README.md`.

---

## 6. Seguridad

Es una aplicación que **gasta dinero y habla en nombre de la empresa** a cualquier número: el
riesgo principal no es que lean algo, es que **alguien la use para mandar SMS**. Cada capa
responde a una forma concreta de abuso.

### Transporte: HTTPS con certificado fijado

Sin TLS, la API key viajaría en claro por la wifi de la oficina, que es el sitio donde más gente
puede escucharla. TLS 1.2 como mínimo. El certificado tiene **dos orígenes**, y en los dos la
clave privada acaba en **`AndroidKeyStore`**, de donde no se puede extraer:

| | Generado por el teléfono (por defecto) | Importado (`.p12`) |
|---|---|---|
| **Cómo** | La app crea el par de claves en `AndroidKeyStore`, con certificado autofirmado | Lo firma la CA de la empresa, y se importa desde Ajustes con su contraseña |
| **La clave privada** | No ha existido nunca fuera del teléfono | Existió en un fichero: **hay que borrar el `.p12`** o guardarlo en un sitio seguro. La app lo recuerda al terminar la importación |
| **Cómo confía el cliente** | Fijando la **huella exacta** (Panel: la del certificado y la de la clave pública) | Fijando la huella, o, si lo firmó una CA de la empresa, **validando contra esa CA** sin fijar nada |
| **Cambiar de teléfono** | Huella nueva: hay que actualizar todos los clientes | Se importa el mismo `.p12` y los clientes no cambian |

- Que sea autofirmado no es un problema: el cliente no confía en una CA pública sino en la huella.
- **Con una CA de empresa, el certificado lleva la IP** (o un nombre DNS interno) en el SAN y el
  cliente valida el nombre de host. Por eso, en ese caso, la reserva DHCP del router es
  **obligatoria** y no solo recomendable.
- Al importar, la app comprueba que el certificado no está caducado, que la clave es RSA ≥ 2048 o
  EC P-256, y avisa si la IP del SAN no coincide con la IP wifi actual.
- Regenerar o importar otro certificado invalida a propósito todos los clientes que fijaban la
  huella anterior.
- `curl` fija la huella con `--pinnedpubkey`; la biblioteca C#, en el
  `RemoteCertificateValidationCallback`, comparando la huella exacta en tiempo constante y
  **nunca** devolviendo `true` sin más.

### Autenticación: API keys con ámbitos

- `Authorization: Bearer ats_<32 bytes aleatorios en base62>`. El prefijo `ats_` hace que los
  escáneres de secretos las reconozcan si acaban en un repositorio.
- **Se guarda solo el hash** (SHA-256: con 256 bits de entropía no hace falta un hash lento). La
  clave se enseña una vez, al crearla.
- Varias claves con nombre, una por sistema cliente («ERP», «Web de pruebas»). Revocar una no
  afecta a las demás, y la auditoría dice quién mandó qué.
- **Ámbitos:** `enviar`, `consultar`, `admin`. La web de pruebas puede tener los tres; el ERP,
  solo `enviar` y `consultar`.
- La comparación del hash se hace en tiempo constante.

### Red

- **Lista de IPs/CIDR permitidas por clave**, y una global. Vacía = solo la subred wifi actual.
- Servidor **atado a la IP wifi**, nunca a datos móviles (fase 4).
- **Bloqueo por fuerza bruta:** 10 autenticaciones fallidas en 5 min desde una IP la bloquean 15
  min. Queda en la auditoría.

### Abuso de envío

- **Límite de ritmo** por clave (peticiones/minuto) → `429` con `Retry-After`.
- **Cuotas diarias** de SMS (contando partes) por clave y global → `429` con la cuota restante
  en la respuesta.
- **Prefijos permitidos** (por ejemplo, solo `+52`): un destino fuera de la lista → `422`. Es lo
  que impide que una clave robada mande SMS a números internacionales de tarificación especial.
  Lista de bloqueo opcional además.
- **Tope de partes por mensaje** (por defecto 3): un texto enorme no se convierte en 20 SMS.
- **Validación estricta**: destino en E.164 tras normalizar, texto no vacío, cuerpo JSON con
  tamaño máximo, campos desconocidos rechazados.

### En el teléfono

- **Auditoría**: cada petición autenticada o rechazada, con la clave, la IP, el resultado y el
  id. No se registra el texto en el log, solo en el mensaje.
- **Retención**: los textos se purgan a los N días (por defecto 30) y queda el metadato.
  `HealthMonitorWorker` ya tiene ese papel.
- Base de Room fuera de la copia de Google; la exportación propia no lleva secretos salvo que se
  marque «Incluir los datos confidenciales», con aviso en rojo (fase 7, decisión N).
- Abrir la pestaña Claves pide el bloqueo del dispositivo (`BiometricPrompt` con credencial del
  dispositivo): quien coge el teléfono no puede crear una clave para sí.

### Límite de Android (no es seguridad, pero se descubre como si lo fuera)

Android pide confirmación al usuario cuando una app manda **más de 30 SMS en 30 minutos**, y
mientras nadie toca el diálogo, la cola se queda parada. Para una pasarela hay que subirlo al
preparar el teléfono:

```bash
adb shell settings put global sms_outgoing_check_max_count 10000
adb shell settings put global sms_outgoing_check_interval_ms 60000
```

Va en el README, en el Panel como aviso y en la fila 2 de la fase 10. Junto con el de siempre:
`dumpsys deviceidle whitelist +com.capicua.apitosms`.

---

## 7. Riesgos

| Riesgo | Qué pasa | Mitigación |
|---|---|---|
| **El operador bloquea la SIM** | Muchos SMS iguales seguidos parecen spam y el operador corta la línea | Pausa entre mensajes configurable, con un valor por defecto prudente; documentarlo. No hay forma técnica de evitarlo del todo |
| **Límite de 30/30 min de Android** | La cola se para esperando un diálogo que nadie ve | Ajuste por `adb` (punto 6) y aviso en el Panel |
| **La wifi se duerme** | La API deja de contestar con la pantalla apagada | `WifiLock`, Doze, fila 3 de la fase 10 |
| **La IP del teléfono cambia** | Los clientes apuntan a la IP vieja | Reserva DHCP en el router (README); el Panel enseña la IP actual |
| **Duplicado tras caída** | El SMS salió pero la app murió antes de la confirmación | Decisión C; documentado |
| **El `.p12` se queda por ahí** | Quien tenga el fichero y su contraseña puede hacerse pasar por el teléfono | La app lo recuerda tras importar; README; la vía por defecto no tiene fichero |
| ~~**El servidor HTTP pesa**~~ | Netty puede añadir varios MB y tardar en arrancar | **Resuelto:** servidor propio (punto 2) |
| **Operador sin informes de entrega** | `ENVIADO` es el estado final y el cliente no sabe si llegó | Documentado en la API, la biblioteca y el README; `EsperarResultadoAsync` no se queda esperando para siempre |
| **El linaje vuelve a tener dos ramas vivas** | Un arreglo en `SmsSender` o los workers de SMSRelay no llega aquí, y sin historial común ni masterplans compartidos nada lo recuerda | Dos aplicaciones, no cinco; anotarlo en los dos `CLAUDE.md`, con la lista de ficheros compartidos |
| **El icono se olvida** | Ver la fase 1 | Cierre de la fase 1 |

---

## 8. Orden y dependencias

1 → 2 → 3, en ese orden. **4 depende de 3**; 5 de 4. **La 8 puede empezar en cuanto
`docs/openapi.yaml` esté fijado** (al principio de la 4): la biblioteca y sus tests unitarios no
necesitan teléfono. La web de pruebas, en cambio, solo se cierra contra el teléfono, tras la 5.
6 y 7 tras 5. 9 y 10 al final.

---

## 9. Decisiones

Tomadas el 1 nov 2026.

| | Decisión | Elegida | Descartada |
|---|---|---|---|
| **A** | Cómo nace el repositorio | **Repositorio nuevo**, copiando solo los ficheros útiles de SMSRelay | Copia de SMSRelay con historial y poda: el linaje en `git log`, pero media aplicación que borrar |
| **B** | Un error transitorio, ¿bloquea la cola? | **No**: el mensaje se reprograma y pasa el siguiente | Orden estricto: un número problemático para la cola entera |
| **C** | Mensaje `ENVIANDO` cuando la app muere | **Reenviar (al menos una vez)**, como SMSRelay | Marcarlo `INCIERTO` (como mucho una vez) |
| **D** | Avisar al cliente del resultado | **Solo sondeo** (`GET`) en la 1.0.0 | *Webhook* firmado con HMAC; queda para más adelante |
| **E** | Color del icono | **Magenta / frambuesa**, tono 40 | Gris pizarra, rojo |
| **F** | Simulador del teléfono en C# | **No**: la biblioteca se prueba con tests unitarios y la web contra el teléfono | Simulador que imita la API |
| **G** | Repositorio en GitHub | **Al acabar la fase 9** (1 nov 2026; se pensó para la 1.0.0 y el usuario lo adelantó), público, con `develop` por defecto hasta la release | Al cerrar la fase 1, o al publicar |
| **H** | SIM de salida | **Un ajuste global**, como en SMSRelay | Campo `sim` por petición |
| **I** | Cómo llega la biblioteca C# a la empresa | **Paquete NuGet adjunto al release de GitHub** (al principio, la DLL suelta), sin publicar en nuget.org, junto al APK, con la web de pruebas autocontenida (`win-x64`, `linux-x64`) | NuGet público, o copiar el proyecto |
| **J** | Origen del certificado TLS | **Los dos**: generado por el teléfono por defecto, o importado (`.p12`) | Solo uno de ellos |
| **K** | Los cinco masterplans anteriores | **No se copian**: este es el único plan del repositorio | Copiarlos como historia |
| **L** | Generador de certificados en C# | **No** (1 nov 2026): la empresa tiene su propia CA | `ApiToSms.Certificado`, que llegó a escribirse y se retiró antes del primer commit |
| **M** | QR en el Panel con URL y huella | **No** (1 nov 2026): los clientes son computadoras, que no escanean; la huella se copia con un botón | QR con zxing, que se llegó a hacer y se quitó |
| **N** | Datos confidenciales en la copia | **Opcionales**, con la casilla de SMSRelay (1 nov 2026): hashes de las claves y el `.p12` importado, guardado cifrado para poder exportarlo | Copia siempre sin secretos, que era lo de la fase 7 |
