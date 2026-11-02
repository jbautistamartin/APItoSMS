# Registro de cambios

Todas las versiones publicadas de **APItoSMS**, con lo que cambió en cada una.
El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones,
[SemVer](https://semver.org/lang/es/).

> **APItoSMS es la sexta aplicación de la familia, y la primera que va al revés**: las otras cinco
> reciben un SMS y lo llevan a otro sitio; esta recibe una petición HTTPS de la red local y manda
> el SMS. Nace de ficheros de [SMSRelay](https://github.com/jbautistamartin/SMSRelay) (`4b884d3`),
> pero sin su historia, y es una aplicación distinta, con `applicationId` y base de datos propios.
> El plan está en `MASTERPLAN-APITOSMS.md`.

---

## [No publicado]

### Añadido

- **API HTTPS en la red local** (`/api/v1`): enviar un mensaje o un lote, consultar su estado,
  listarlos con filtros y cursor, cancelar lo que sigue en cola, ver la cola y pausarla. Responde
  `202` en cuanto el mensaje está guardado; el resultado se consulta por sondeo. Contrato en
  `docs/openapi.yaml`, errores en `docs/errores.md`.
- **Cola en serie**: un SMS en vuelo como mucho, con pausa configurable entre uno y otro. Un error
  transitorio reprograma el mensaje sin parar la cola; un mensaje que se quedó enviando al morir la
  app se reenvía; un permiso retirado para la cola sin gastar intentos.
- **Estados** `EN_COLA`, `ENVIANDO`, `ENVIADO`, `ENTREGADO`, `NO_ENTREGADO`, `FALLIDO`,
  `CANCELADO` y `CADUCADO`, con informe de entrega cuando el operador lo manda.
- **La IP de origen** de cada mensaje (`ipOrigen`): se guarda con el mensaje, la devuelve la API,
  la enseña el detalle de la pestaña Mensajes y la llevan las líneas del Registro de su envío y su
  entrega.
- **Idempotencia**: la misma `Idempotency-Key` con el mismo contenido devuelve el mensaje ya
  creado y no manda otro SMS.
- **Certificado TLS** generado en el teléfono (EC P-256 en `AndroidKeyStore`) o importado de un
  `.p12`, por ejemplo de la CA de la empresa. El Panel enseña la huella del certificado y la de la
  clave pública para fijarlas en los clientes.
- **Claves de la API** con nombre, ámbitos (`ENVIAR`, `CONSULTAR`, `ADMIN`), redes permitidas,
  ritmo y cuota diaria. Solo se guarda su SHA-256 y el secreto se enseña una vez. La pantalla de
  claves pide la credencial del teléfono y no admite capturas.
- **Defensas**: bloqueo de la IP tras 10 fallos de autenticación, redes permitidas aplicadas antes
  del saludo TLS, cuotas diarias en partes SMS, prefijos de destino permitidos y bloqueados, y tope
  de partes por mensaje.
- **Pantallas** Panel, Mensajes, Registro, Ajustes y Acerca de, con avisos de lo que falta por
  preparar en el teléfono (permiso, batería, límite de SMS de Android).
- **Copia de ajustes**, con la casilla **«Incluir los datos confidenciales»** de SMSRelay. Sin
  marcar, no lleva secretos: las claves llegan revocadas, para regenerarlas. Marcada, lleva el
  hash de cada clave y el `.p12` importado con su contraseña, y un teléfono de repuesto se monta
  importando un solo fichero: los sistemas no cambian ni la clave ni la huella. El certificado
  generado en el teléfono no viaja nunca.
- **Biblioteca C# `Capicua.ApiToSms.Cliente`** (`dotnet/`): fija el certificado, pone la
  `Idempotency-Key`, reintenta lo que es seguro reintentar, convierte cada error en una excepción
  y espera el resultado de un mensaje. Y una **web de pruebas** que la usa.
