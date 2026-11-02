# APItoSMS

[![Última versión](https://img.shields.io/github/v/release/jbautistamartin/APItoSMS?label=descargar&color=A4005F)](https://github.com/jbautistamartin/APItoSMS/releases/latest)
[![Licencia](https://img.shields.io/badge/licencia-LGPL--2.1-blue)](LICENSE)
[![Donar con PayPal](https://img.shields.io/badge/Donar-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/jbautistamartin)

Aplicación Android que convierte un teléfono con SIM en una **pasarela de envío de SMS para la red
local**. El teléfono publica una API HTTPS en la wifi de la oficina, los sistemas de la empresa (un
ERP, un servicio en C#, un script) le entregan mensajes, y él los manda **uno tras otro**, por orden
de llegada y desde su propia SIM.

```
Computadora de la red local                     Teléfono con APItoSMS
  POST /api/v1/mensajes  ── HTTPS + clave ──►   guarda el mensaje → 202 + id
                                                cola en serie → SMS por la SIM
  GET /api/v1/mensajes/{id}  ◄── estado ────    EN_COLA → ENVIADO → ENTREGADO
```

> **APItoSMS es la sexta aplicación de la familia, y la primera que va al revés.**
> [SMSRelay](https://github.com/jbautistamartin/SMSRelay) y sus antecesoras reciben un SMS y lo
> llevan a otro sitio; esta recibe una petición y manda el SMS. Las dos pueden estar instaladas en
> el mismo teléfono.

**La 1.0.0 está publicada** y se descarga desde la
[última release](https://github.com/jbautistamartin/APItoSMS/releases/latest): el APK para el
teléfono, el paquete NuGet de la biblioteca C# y la web de pruebas para Windows y Linux. Se probó
en un teléfono real (Xiaomi con HyperOS y SIM Telcel), con pruebas de resistencia incluidas: un
lote de 50, media hora en Doze, reinicios con la cola llena y la wifi que se va y vuelve. Lo que
cambia en cada versión está en [`CHANGELOG.md`](CHANGELOG.md).

No está en Google Play: `SEND_SMS` pertenece a un grupo de permisos restringido cuya publicación
exige una declaración aprobada.

---

## Para qué sirve

Para mandar avisos, códigos o recordatorios desde sistemas propios **sin contratar un proveedor de
SMS**: basta un teléfono Android con una SIM y una wifi. Está pensada para **cientos de mensajes al
día, no para cientos de miles**: el operador y Android limitan el ritmo, y un teléfono no compite
con un agregador.

No es un servicio expuesto a internet. El servidor escucha **solo en la wifi**, nunca en los datos
móviles, y por defecto solo admite conexiones de la misma subred.

---

## Características principales

- **API asíncrona.** El teléfono contesta `202` en cuanto el mensaje está guardado en su base de
  datos, y lo envía después. El resultado se consulta por sondeo.
- **Cola en serie.** Como mucho un SMS en vuelo, con una pausa configurable entre uno y otro. Un
  error transitorio reprograma ese mensaje y la cola sigue con el siguiente.
- **Nada se pierde.** El mensaje se guarda antes de contestar: si el teléfono se reinicia un
  segundo después, al volver lo envía.
- **Idempotencia.** Repetir una petición con la misma `Idempotency-Key` devuelve el mensaje ya
  creado y no manda otro SMS. Un cliente puede reintentar sin miedo a duplicar.
- **Lotes** de hasta 100 mensajes (configurable), todo o nada, que salen en el orden en que vienen.
- **HTTPS con certificado fijado.** El teléfono genera su propio certificado, o se importa uno de la
  CA de la empresa. Los clientes fijan su huella.
- **Claves de la API** con nombre, ámbitos, redes permitidas, ritmo y cuota diaria. El teléfono
  solo guarda su hash.
- **Defensas contra el abuso**: bloqueo de la IP tras 10 fallos de autenticación, prefijos de
  destino permitidos (por ejemplo, solo `+52`), tope de partes por mensaje y cuotas diarias.
- **Biblioteca C#** para integrarla en una línea, y una **web de pruebas**.

---

## Requisitos

- Un teléfono **Android 8.0 o superior** con SIM, dedicado a esto, y conectado a la wifi de la
  oficina.
- Una **reserva DHCP** en el router para el teléfono: si su IP cambia, los clientes dejan de
  encontrarlo. Con un certificado de la CA de la empresa es **obligatoria**, porque la IP va dentro
  del certificado.
- Para la biblioteca y la web de pruebas, el **SDK de .NET 10**.

---

## Instalar y preparar el teléfono

1. Descarga `apitosms-<versión>.apk` de la
   [última versión](https://github.com/jbautistamartin/APItoSMS/releases/latest) e instálalo.
   **Ábrelo una vez**: una app recién instalada que nadie ha abierto no recibe el aviso de
   reinicio y no arrancaría sola.
2. Concede el permiso de **SMS** cuando lo pida. Sin él la cola se queda parada (sin gastar
   intentos) y el Panel lo avisa.
3. **Quita la optimización de batería** y activa el **inicio automático** (en Ajustes →
   Android). Sin lo primero, con la pantalla apagada, Android pospone los envíos; sin lo
   segundo, Xiaomi, Huawei, Oppo y Vivo no dejan arrancar la app tras un reinicio.
4. **Sube el límite de SMS de Android.** Android pide confirmación cuando una app manda más de
   30 SMS en 30 minutos, y mientras nadie toca el diálogo la cola no avanza. Con el teléfono por
   USB:

   ```bash
   adb shell settings put global sms_outgoing_check_max_count 10000
   adb shell settings put global sms_outgoing_check_interval_ms 60000
   adb shell dumpsys deviceidle whitelist +com.capicua.apitosms
   ```

   El Panel avisa mientras el límite siga en su valor de fábrica.
5. En el **Panel**, enciende el servidor. Enseña la URL (`https://192.168.1.50:8443`) y las dos
   huellas del certificado.
6. En el Panel → **Claves**, crea una clave por cada sistema que vaya a enviar. El teléfono pide
   su desbloqueo antes de entrar. **El secreto se enseña una sola vez**: cópialo entonces.

> **Telcel y otros operadores no mandan informes de entrega** cuando el SMS sale por VoWiFi. Con
> ellos un mensaje se queda en `ENVIADO`, que en ese caso es el estado final: el SMS salió, pero
> el operador no dice si llegó.

---

## Usar la API

La referencia completa está en [`docs/openapi.yaml`](docs/openapi.yaml), y los errores en
[`docs/errores.md`](docs/errores.md).

### curl

```bash
URL=https://192.168.1.50:8443
CLAVE=ats_…                                      # la de Panel → Claves
PIN='sha256//dY88…xdM='                           # «Huella de la clave pública» del Panel

# Enviar
curl --pinnedpubkey "$PIN" -k "$URL/api/v1/mensajes" \
  -H "Authorization: Bearer $CLAVE" -H "Content-Type: application/json" \
  -H "Idempotency-Key: pedido-7-aviso" \
  -d '{"destino":"+523311298770","texto":"Su pedido 7 ha salido","referencia":"pedido-7"}'

# Consultar
curl --pinnedpubkey "$PIN" -k "$URL/api/v1/mensajes/<id>" -H "Authorization: Bearer $CLAVE"
```

`-k` desactiva la validación de la cadena porque el certificado es autofirmado, y
`--pinnedpubkey` la sustituye por algo más estricto: **solo** se acepta ese certificado. Sin
`--pinnedpubkey`, `-k` aceptaría cualquiera.

### C#

```csharp
using Capicua.ApiToSms.Cliente;

using var cliente = ClienteApiToSms.Crear(new OpcionesApiToSms
{
    Url = new Uri("https://192.168.1.50:8443"),
    Clave = Environment.GetEnvironmentVariable("APITOSMS_CLAVE"),
    Huella = "99:48:7A:…:88:06",          // la del Panel, cualquiera de las dos
});

var mensaje = await cliente.EnviarAsync(
    new NuevoMensaje("+523311298770", "Su pedido 7 ha salido", Referencia: "pedido-7"),
    idempotencyKey: "pedido-7-aviso");

var final = await cliente.EsperarResultadoAsync(mensaje.Id);
Console.WriteLine(final.Estado);          // Enviado, Entregado, Fallido…
```

Con inyección de dependencias, `builder.Services.AddApiToSms(o => { … })` registra
`ClienteApiToSms` sobre `IHttpClientFactory`. La biblioteca reintenta los fallos de red, los `5xx`
y los `429` por ritmo, siempre con la misma `Idempotency-Key`, y convierte cada error de la API
en una excepción (`ValidacionException`, `LimiteExcedidoException`…).

La biblioteca va adjunta a cada release como paquete NuGet,
`Capicua.ApiToSms.Cliente.<versión>.nupkg`, con su XML de documentación dentro. **No está en
nuget.org**: se descarga del release y se instala desde una carpeta local.

```bash
dotnet nuget add source C:\paquetes --name local    # una vez, la carpeta donde dejas el .nupkg
dotnet add package Capicua.ApiToSms.Cliente
```

Está firmada con nombre seguro: `PublicKeyToken=a313609336f4f9ff`. Depende de
`Microsoft.Extensions.Http` 10, que NuGet trae solo.

### PowerShell

```powershell
$cuerpo = @{ destino = '+523311298770'; texto = 'Prueba' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'https://192.168.1.50:8443/api/v1/mensajes' `
  -Headers @{ Authorization = "Bearer $env:APITOSMS_CLAVE"; 'Idempotency-Key' = [guid]::NewGuid() } `
  -ContentType 'application/json' -Body $cuerpo -SkipCertificateCheck
```

Es PowerShell 7 (`-SkipCertificateCheck` no existe en Windows PowerShell 5.1). PowerShell no fija
huellas: `-SkipCertificateCheck` acepta **cualquier** certificado. Sirve para
una prueba, no para un sistema en producción; para eso, la biblioteca C# o un certificado de la CA
de la empresa en el almacén de confianza.

> **Si escribes tu propio cliente:** el cuerpo tiene que ir con `Content-Length`. El teléfono
> rechaza `Transfer-Encoding: chunked` con un `501`, que es lo que manda `JsonContent` de .NET.

---

## El certificado

| | Generado por el teléfono (por defecto) | Importado (`.p12`) |
|---|---|---|
| **Origen** | La app crea la clave en `AndroidKeyStore`, de donde no sale nunca | La CA de la empresa emite el certificado, con la IP del teléfono en el SAN, y se importa en Ajustes → Certificado TLS |
| **Cómo confía el cliente** | Fijando la huella | Fijando la huella, o confiando en la CA |
| **Cambiar de teléfono** | Huella nueva: hay que actualizar todos los clientes | Se importa el mismo `.p12` y los clientes no cambian |

Tras importar, **borra el `.p12`** o guárdalo en un sitio seguro: quien tenga el fichero y su
contraseña puede hacerse pasar por el teléfono.

Regenerar o importar otro certificado cambia la huella **a propósito**: todos los clientes que
fijaban la anterior dejan de conectar hasta que se les pone la nueva.

---

## La web de pruebas

Cada release la trae lista para usar, sin instalar .NET: `Capicua.ApiToSms.Pruebas.Web-<versión>-win-x64.zip`
y `…-linux-x64.tar.gz`. Se descomprime y se ejecuta `Capicua.ApiToSms.Pruebas.Web`; luego se abre
<http://127.0.0.1:5080>. `conexion.json` se crea junto al ejecutable. Desde el código:

```bash
cd dotnet/Capicua.ApiToSms.Pruebas.Web
dotnet run
# → http://127.0.0.1:5080
```

Se configura con la URL, la clave y la huella, que se guardan en `conexion.json`. Ese fichero
**lleva la clave en claro y no se versiona**. Desde la página se envía un mensaje o un lote de N
mensajes repartidos entre varios destinos, se ve el estado de cada uno en vivo, y se pausa o
reanuda la cola. Solo escucha en `127.0.0.1`: no tiene autenticación propia y manda SMS de verdad.

---

## Compilar desde el código

```bash
./gradlew assembleDebug            # APK debug (com.capicua.apitosms.debug, convive con la release)
./gradlew test                     # 91 tests JVM
./gradlew assembleRelease          # → app/build/outputs/apk/release/apitosms-<versión>.apk

cd dotnet
dotnet test                        # 38 tests de la biblioteca
dotnet build -c Release Capicua.ApiToSms.Cliente
dotnet publish Capicua.ApiToSms.Pruebas.Web -c Release -r win-x64 --self-contained -p:PublishSingleFile=true
```

Los ensamblados se firman con nombre seguro si existe `../../Claves/capicua.snk` (fuera del
repositorio, junto al almacén de los APK); sin ella se compilan sin firmar, sin ningún error.

Gradle necesita el **JDK 25** (Temurin) y el SDK con la **plataforma 37**. La firma de release se
configura en `local.properties`: ver [`CLAUDE.md`](CLAUDE.md).

---

## Permisos

| Permiso | Para qué |
|---|---|
| `SEND_SMS` | Enviar. Es un permiso restringido: por eso la app no está en Google Play |
| `READ_PHONE_STATE` | Elegir la SIM de envío en un teléfono con dos |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | El servidor, y saber en qué IP wifi escuchar |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK` | Mantener el servidor vivo con la pantalla apagada |
| `RECEIVE_BOOT_COMPLETED` | Volver a encender el servidor tras un reinicio |
| `POST_NOTIFICATIONS` | La notificación fija del servidor |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | El botón que abre la exención de batería |
| `USE_BIOMETRIC` | Pedir el desbloqueo antes de ver o crear claves |
| `REQUEST_INSTALL_PACKAGES` | Instalar una versión nueva desde «Acerca de» |

---

## Apoyar el proyecto

APItoSMS es gratuito y de código abierto. Si te resulta útil, puedes invitarme a un café:

[![Donar con PayPal](https://img.shields.io/badge/Donar-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/jbautistamartin)

---

## Licencia

Este proyecto se distribuye bajo la **GNU Lesser General Public License v2.1**.
Consulta el fichero [`LICENSE`](LICENSE) para más detalles.

---

## Aviso legal

Este software se proporciona **"tal cual"**, sin garantía de ningún tipo, expresa o implícita.

Esta aplicación **guarda y envía datos de terceros**: los números y los textos que le entregan los
sistemas de la empresa. Antes de desplegarla, considera que:

- Los SMS **salen de tu SIM**, los cobra tu operador según tu tarifa, y quien los recibe ve tu
  número como remitente: ante él y ante el operador, el emisor eres tú.
- Muchos SMS iguales y seguidos **parecen spam**, y el operador puede cortar la línea. La pausa
  entre mensajes lo atenúa, pero no hay forma técnica de evitarlo del todo.
- El teléfono guarda el texto de cada mensaje durante el periodo de retención configurado (30 días
  por defecto), y el número y el resultado durante más tiempo (90 días). El registro de auditoría
  guarda las IP y las claves que hicieron cada petición.
- Quien tenga una clave de la API puede mandar SMS en tu nombre, dentro de los límites que le
  pongas. Revoca las que ya no se usen.

El autor no se hace responsable de:

- Pérdida, retraso o no entrega de mensajes, ni del tratamiento que tu operador haga de ellos.
- El importe que tu operador te cobre, ni de ningún bloqueo de la línea.
- Uso indebido de la aplicación para enviar mensajes no solicitados.
- Daños directos o indirectos derivados del uso, mal uso o imposibilidad de uso del software.
- Incumplimientos normativos (RGPD, LFPDPPP u otras regulaciones de privacidad o de comunicaciones
  comerciales) derivados de una configuración o despliegue inadecuados.

**El operador es el único responsable** de garantizar que el uso de esta aplicación cumple con la
legislación vigente en su jurisdicción, incluido el consentimiento de los destinatarios.

---

## Desarrollo asistido por IA

El diseño, la arquitectura y el código de este proyecto se desarrollaron con asistencia de
[Claude](https://claude.ai) (Anthropic). El código fue revisado, validado y adaptado por el autor
antes de cada commit.
